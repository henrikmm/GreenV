"""Phase 6 — statistical baselines.

Every baseline here is simple, interpretable and honestly scoped. They are what
Phase 7's Random Forest / Gradient Boosting / linear models are obligated to
beat -- this file implements ONLY baselines, no learned model of any kind.

Fitting (where there is any) uses train rows only. scripts/evaluate_baselines.py
evaluates on validation only -- test and OOD are never read by that script.

All four read feature_row_dev.csv rows (dicts of strings, as produced by
scripts/build_features.py) and the frozen columns from
greenv_vegpred.features.feature_spec. None of them reads
true_height_cm_DIAGNOSTIC_ONLY, any target_* column as an input, or any
EXCLUDE_LEAKAGE column.
"""
from __future__ import annotations
from collections import defaultdict
from datetime import date
from statistics import median

# Physical caps (documented, not arbitrary):
#   MAX_GROWTH_CM_PER_WEEK: Phase 4 synthetic-model.md DH_MAX = 5 cm/day physical cap on true
#   growth -> 35 cm/week. Any observed weekly_growth_cm above this is measurement noise
#   (Phase 5 EDA found 59 such outliers), never extrapolated as-is.
MAX_GROWTH_CM_PER_WEEK = 35.0
#   MIN_RATE_CM_PER_DAY: below this, "days until 30cm" is undefined-in-practice (would blow up
#   toward the horizon anyway) -- treated as beyond-horizon, never as a divide-by-near-zero result.
MIN_RATE_CM_PER_DAY = 0.02
#   HORIZON_DAYS: matches the TRUE target's own right-censoring horizon (Phase 5), so a
#   baseline's censoring call can be compared like-for-like against the true one.
HORIZON_DAYS = 120


def fnum(x):
    return None if x in (None, "") else float(x)


def clipped_growth_rate_cm_per_week(row: dict):
    """The SAME-CYCLE weekly growth rate this row's own history supports (Phase 5:
    weekly_growth_cm is already NULL on the first observation of a new roçada cycle, so this
    never extrapolates across a cut). None if no same-cycle history exists yet.

    growth <= 0 handling (explicit, per instruction): Phase 4 established that TRUE height never
    shrinks between roçadas -- only the OBSERVED reading can dip, from measurement noise. So any
    non-positive observed growth is treated as 0 for extrapolation: roadside grass is not modelled
    as naturally receding. This is a real behaviour that is deliberately discarded here, not lost --
    scripts/evaluate_baselines.py separately counts how often it happens (a Phase 6 "hard case").
    """
    g = fnum(row.get("weekly_growth_cm"))
    if g is None:
        return None
    return max(0.0, min(MAX_GROWTH_CM_PER_WEEK, g))


class PersistenceBaseline:
    """height(t+N) = height(t). Zero parameters; nothing is fit."""
    name = "persistence"

    def fit(self, train_rows):
        return self  # parameter-free by construction; kept for a uniform .fit() interface

    def predict_height(self, row: dict, horizon_days: int):
        return float(row["height_cm"]), {}


class LastGrowthBaseline:
    """height(t+N) = height(t) + clipped_same_cycle_weekly_rate * N/7.
    Falls back to persistence (rate=0) when no same-cycle rate exists (first obs of a cycle) --
    every such prediction is flagged used_fallback=True, never silently defaulted."""
    name = "last_growth"

    def fit(self, train_rows):
        return self

    def predict_height(self, row: dict, horizon_days: int):
        rate = clipped_growth_rate_cm_per_week(row)
        used_fallback = rate is None
        rate = rate if rate is not None else 0.0
        pred = float(row["height_cm"]) + rate * (horizon_days / 7.0)
        return max(0.0, min(150.0, pred)), {"used_fallback": used_fallback}


class SeasonalClimatologyBaseline:
    """height(t+N) = the TRAIN-period mean height_cm for the calendar month of (as_of_date+N),
    pooled across ALL dev trechos and both roçada cycles. Deliberately ignores the row's own
    current state.

    WHY THIS FORM, NOT "same trecho, same week last year": a literal per-trecho 52-week lookback
    (TASK.md's original wording) is NOT defensible in this domain. Phase 4 QC: roçada happens
    ~5.96 times per trecho per year, median inter-cut interval 51 days -- so in the 364 days
    between "now" and "the same week last year", a given trecho has typically been through ~7
    independent growth cycles. The value 52 weeks back describes an unrelated cycle, not a
    genuine seasonal repeat, and using it would be a baseline invented to fill a checklist slot,
    not a defensible one. Pooling across trechos and using the calendar-month mean instead keeps
    the "typical height for this time of year" idea (real seasonality, DEC-001/DEC-003) while
    being robust to any single trecho's own cutting history.
    """
    name = "seasonal_climatology"

    def fit(self, train_rows):
        by_month = defaultdict(list)
        for r in train_rows:
            by_month[int(r["as_of_date"][5:7])].append(float(r["height_cm"]))
        self.month_mean = {m: (sum(v) / len(v)) for m, v in by_month.items() if v}
        allv = [float(r["height_cm"]) for r in train_rows]
        self.overall_mean = sum(allv) / len(allv) if allv else 0.0
        self.month_n = {m: len(v) for m, v in by_month.items()}
        return self

    def predict_height(self, row: dict, horizon_days: int):
        d = date.fromisoformat(row["as_of_date"])
        target_month = date.fromordinal(d.toordinal() + horizon_days).month
        val = self.month_mean.get(target_month)
        used_fallback = val is None
        val = val if val is not None else self.overall_mean
        return val, {"used_fallback": used_fallback}


class NaiveDaysUntil30Baseline:
    """days_until_30cm, from as_of_date's own height and the SAME cycle-safe, clipped growth
    rate as LastGrowthBaseline -- never a rate that crosses a roçada.

    Rules (all explicit, per instruction):
      * no local (same-cycle) rate available -> fall back to a TRAIN-fit rate: the median of
        clipped_growth_rate_cm_per_week() over all train rows with a positive same-cycle rate.
        Flagged used_fallback=True.
      * rate below MIN_RATE_CM_PER_DAY -> if already >=30cm, predict 0 (no growth needed);
        otherwise the projection is undefined in practice -> predict None, censored=True
        (mirrors the TRUE target's own right-censoring, never a divide-by-near-zero number).
      * projected days > HORIZON_DAYS -> None, censored=True (same horizon as the true target).
      * otherwise: days = max(0, (30 - height_cm) / rate_per_day).

    Known, stated limitation: this assumes NO roçada happens between as_of_date and the
    predicted crossing. A future cut is fundamentally unknowable to a naive baseline (it would
    need to know the operator's future schedule) -- this is not hidden, it is the single largest
    reason this baseline should be beatable by anything that models the roçada policy.
    """
    name = "naive_days_until_30cm"

    def fit(self, train_rows):
        rates = [clipped_growth_rate_cm_per_week(r) for r in train_rows]
        rates = [r for r in rates if r is not None and r > 0]
        self.fallback_rate_cm_per_week = median(rates) if rates else 0.0
        self.fallback_rate_n = len(rates)
        return self

    def predict(self, row: dict):
        h = float(row["height_cm"])
        rate = clipped_growth_rate_cm_per_week(row)
        used_fallback = rate is None
        rate = rate if rate is not None else self.fallback_rate_cm_per_week
        rate_per_day = rate / 7.0

        if rate_per_day < MIN_RATE_CM_PER_DAY:
            if h >= 30.0:
                return 0.0, {"used_fallback": used_fallback, "censored": False, "already_tall": True}
            return None, {"used_fallback": used_fallback, "censored": True, "already_tall": False}

        days = max(0.0, (30.0 - h) / rate_per_day)
        if days > HORIZON_DAYS:
            return None, {"used_fallback": used_fallback, "censored": True, "already_tall": False}
        return days, {"used_fallback": used_fallback, "censored": False, "already_tall": h >= 30.0}


ALL_HEIGHT_BASELINES = [PersistenceBaseline, LastGrowthBaseline, SeasonalClimatologyBaseline]
DAYS_UNTIL_30CM_BASELINE = NaiveDaysUntil30Baseline


# ===========================================================================================
# Mechanistic baseline (closes the Phase 6 "mechanistic baseline" completion criterion).
#
# TASK.md's original wording: "linear growth since last roçada at a per-trecho fitted rate;
# a GDD-scaled variant." Implemented as ONE class with both parts:
#
#   rate_i        = the per-(dataset,trecho) FITTED "since-cut" rate (cm/day), estimated on
#                   TRAIN ONLY from that entity's own history -- this is the literal
#                   "linear growth since last roçada at a per-trecho fitted rate".
#   gdd_multiplier = the GDD-SCALED VARIANT: rate_i is scaled up/down by how the CURRENT
#                   week's GDD compares to the train-period average daily GDD.
#   lag_damping    = uses days_since_rocada directly in the prediction (not just the fit): a
#                   fixed, documented discount while still inside the literature's regrowth-lag
#                   reference window (Phase 1/4: LU/H, ~8-14 days).
#
# No water-deficit term: TASK.md's Phase 6 wording does not ask for one, and the instruction
# for this pass said to add it only if the original plan required it or there was a clear
# justification for a "simple, interpretable" baseline -- neither applied, so it stays out.
# ===========================================================================================

GDD_MULTIPLIER_CLIP = (0.2, 3.0)   # avoids both division blow-ups on a near-zero GDD week and an
                                    # absurd multiplier on an extreme one
LAG_DAMPING_DAYS = 14              # matches the "recent roçada" stratification bucket already
                                    # used elsewhere in Phase 6; also close to the Phase 1/4
                                    # regrowth-lag reference (~8-14 d, literature-unconfirmed)
LAG_DAMPING_FACTOR = 0.6           # a fixed, documented, literature-informed discount -- NOT
                                    # fitted against validation (see module docstring / Phase 6
                                    # instruction: never tune a formula against validation)
MIN_TRECHO_HISTORY_N = 5           # below this many same-cycle train observations for an entity,
                                    # fall back to the global rate


def _since_cut_rates(train_rows):
    """Per-trecho median 'growth since the cut' rate (cm/day), fit on TRAIN only.

    Computed purely from feature_row's own OBSERVED columns (height_cm, days_since_rocada,
    rocada_in_new_cycle) -- no rocada_events.csv re-read, no true_height_cm. Individual rate
    SAMPLES are computed within one dataset's own observation sequence (never mixing a main-
    dataset row with a seed2-dataset row inside one before/after pair -- each sample's numerator
    and denominator come from the SAME (dataset,trecho) sorted sequence, so a cycle boundary is
    never crossed and two different synthetic realisations never contaminate a single sample).
    Samples are then POOLED BY trecho_id ACROSS main+seed2 for the median: a real deployment has
    one physical trecho, not a "main-trecho-X" and a "seed2-trecho-X", and pooling both
    realisations' samples gives a more stable per-trecho median (118/118 trechos reach the
    MIN_TRECHO_HISTORY_N floor this way, vs far fewer if kept split by dataset) than treating the
    two stochastic draws as separate entities would.

    For each (dataset,trecho) sorted observation sequence: the first row of a cycle
    (rocada_in_new_cycle=1) anchors that cycle's start (height, days_since_rocada); every LATER
    row in the SAME cycle gives one rate sample =
    (height_now - height_at_cycle_start) / (days_since_rocada_now - days_since_rocada_at_start).
    Cycle 0 (no roçada observed yet in the record) has no anchor and is skipped -- never
    fabricates a "since cut" rate with no cut in view.

    A rate < 0 (the anchor read taller than a later same-cycle reading -- measurement noise,
    since true height cannot shrink without a roçada, Phase 4) is clipped to 0, per the explicit
    growth<=0 rule this module already documents for every other baseline. A denominator < 3 days
    is skipped, to avoid a near-zero-denominator noise blow-up.
    """
    by_entity = defaultdict(list)
    for r in train_rows:
        by_entity[(r["dataset"], r["trecho_id"])].append(r)

    per_trecho = defaultdict(list)
    all_rates = []
    for (_, trecho_id), rows in by_entity.items():
        rows.sort(key=lambda r: r["as_of_date"])
        cycle_start = None
        for r in rows:
            if r["rocada_in_new_cycle"] == "1":
                cycle_start = r
                continue
            if cycle_start is None:
                continue
            dt = int(r["days_since_rocada"]) - int(cycle_start["days_since_rocada"])
            if dt < 3:
                continue
            dh = float(r["height_cm"]) - float(cycle_start["height_cm"])
            rate = max(0.0, dh / dt)
            rate = min(rate, MAX_GROWTH_CM_PER_WEEK / 7.0)
            per_trecho[trecho_id].append(rate)
            all_rates.append(rate)

    trecho_rate = {t: median(v) for t, v in per_trecho.items() if len(v) >= MIN_TRECHO_HISTORY_N}
    global_rate = median(all_rates) if all_rates else 0.0
    stats = {"n_entities_with_local_rate": len(trecho_rate), "n_entities_total": len(per_trecho),
             "n_rate_samples": len(all_rates), "global_rate_cm_per_day": round(global_rate, 4)}
    return trecho_rate, global_rate, stats


class MechanisticHeightBaseline:
    """height(t+N) = height(t) + adjusted_rate * N, where:
        adjusted_rate = rate_i * gdd_multiplier * lag_damping   (cm/day, clamped to [0, DH_MAX])
        rate_i         : the per-trecho since-cut rate fit on TRAIN, pooled across the main/seed2
                         realisations (see _since_cut_rates)
        gdd_multiplier : (this week's mean daily GDD at Tb=15C) / (the TRAIN-period average daily
                         GDD), clipped to [0.2, 3.0]. Tb=15C is the params.yaml REFERENCE value
                         (Villa Nova et al. 2007, literature_derived) -- NOT asserted as the true
                         base temperature of SP-021 vegetation (DEC-003). This is a persistence-of-
                         climate assumption for the forecast window: it assumes the recent week's
                         thermal conditions continue, which uses only information available at
                         as_of_date, never a future GDD value.
        lag_damping    : 0.6 if days_since_rocada < 14 (still inside the literature's regrowth-lag
                         reference window), else 1.0. Uses days_since_rocada directly in the
                         prediction, not only in the fit.
    No water-deficit term (see module note above).
    """
    name = "mechanistic"

    def fit(self, train_rows):
        self.trecho_rate, self.global_rate, self.fit_stats = _since_cut_rates(train_rows)
        daily_gdd = [fnum(r["gdd_week_tb15_c"]) / 7.0 for r in train_rows if fnum(r["gdd_week_tb15_c"]) is not None]
        self.train_avg_daily_gdd = (sum(daily_gdd) / len(daily_gdd)) if daily_gdd else 1.0
        if self.train_avg_daily_gdd <= 0:
            self.train_avg_daily_gdd = 1.0
        return self

    def _adjusted_rate(self, row):
        used_fallback = row["trecho_id"] not in self.trecho_rate
        base_rate = self.trecho_rate.get(row["trecho_id"], self.global_rate)

        gdd_now = fnum(row.get("gdd_week_tb15_c"))
        gdd_daily_now = (gdd_now / 7.0) if gdd_now is not None else self.train_avg_daily_gdd
        gdd_mult = gdd_daily_now / self.train_avg_daily_gdd
        gdd_mult = max(GDD_MULTIPLIER_CLIP[0], min(GDD_MULTIPLIER_CLIP[1], gdd_mult))

        tau = int(row["days_since_rocada"])
        lag_mult = LAG_DAMPING_FACTOR if tau < LAG_DAMPING_DAYS else 1.0

        rate = base_rate * gdd_mult * lag_mult
        rate = max(0.0, min(rate, MAX_GROWTH_CM_PER_WEEK / 7.0))
        return rate, used_fallback

    def predict_height(self, row: dict, horizon_days: int):
        rate, used_fallback = self._adjusted_rate(row)
        pred = float(row["height_cm"]) + rate * horizon_days
        return max(0.0, min(150.0, pred)), {"used_fallback": used_fallback}


class MechanisticDaysUntil30Baseline:
    """Same adjusted_rate as MechanisticHeightBaseline, inverted to a days-until-30cm answer with
    the identical censoring convention as NaiveDaysUntil30Baseline (0 if already >=30cm and the
    rate is negligible; None/censored if the rate is negligible and still below 30cm, or the
    projection exceeds the 120-day horizon) -- so the two days_until_30cm baselines are directly
    comparable."""
    name = "mechanistic_days_until_30cm"

    def fit(self, train_rows):
        self._height_model = MechanisticHeightBaseline().fit(train_rows)
        return self

    def predict(self, row: dict):
        h = float(row["height_cm"])
        rate, used_fallback = self._height_model._adjusted_rate(row)
        if rate < MIN_RATE_CM_PER_DAY:
            if h >= 30.0:
                return 0.0, {"used_fallback": used_fallback, "censored": False, "already_tall": True}
            return None, {"used_fallback": used_fallback, "censored": True, "already_tall": False}
        days = max(0.0, (30.0 - h) / rate)
        if days > HORIZON_DAYS:
            return None, {"used_fallback": used_fallback, "censored": True, "already_tall": False}
        return days, {"used_fallback": used_fallback, "censored": False, "already_tall": h >= 30.0}


ALL_HEIGHT_BASELINES_V2 = ALL_HEIGHT_BASELINES + [MechanisticHeightBaseline]
