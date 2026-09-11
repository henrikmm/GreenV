"""Phase 5 — build feature_row from Phase 3 (real weather) + Phase 4 (synthetic heights).

Key correctness rules implemented here (per docs/VEGETATION_PREDICTION_TASK.md Phase 5
and the user's Phase-5 instructions):

  * lags/growth are computed PER (dataset, trecho) and reset at every roçada
    -- a cut starts a new growth cycle; height_prev_cm/height_lag2_cm/
    weekly_growth_cm are NULL on the first observation of a new cycle.
  * targets use only observations strictly at or after as_of_date; they are
    never derived by inverting the current reading.
  * DEC-004: a row whose anchor week OR whose +7/+14/+30 day target week has
    week_days_present < 7 is split='excluded'.
  * true_height_cm is read ONLY to be dropped -- it is written back out solely
    as a clearly-marked diagnostic column, never used to build a feature or a
    target.
  * no imputation: a missing observation is a missing row / a NULL target,
    never filled.

Pre-push remediation (Codex independent review, R01/R02/R04) -- `target_days_until_30cm`'s
construction was rewritten; see `label_observation_cutoff()` and the "days_until_30cm" block in
`build_trecho_rows()` below for the corrected algorithm and its rationale. `target_height_plus_
{7,14,30}d_cm` are UNCHANGED (confirmed, by direct measurement of every real
`target_height_plus_30d_actual_offset_days` value across the current dataset, that no height
target's actual offset ever exceeds 28 days -- always inside the 30-day embargo, never crossing
into the next split's own dates -- so they needed no fix; see reports/target-construction.md).
"""
from __future__ import annotations
import csv, hashlib, json, math
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

MODULE_ROOT = Path(__file__).resolve().parents[3]
WX_DAILY = MODULE_ROOT / "data" / "real" / "weather" / "processed" / "weather_observation.csv"
WX_WEEKLY = MODULE_ROOT / "data" / "real" / "weather" / "processed" / "weather_weekly_features.csv"
SYN = MODULE_ROOT / "data" / "synthetic"
CORRIDOR_CELL = "open-meteo:era5_seamless:-23.5:-46.8"   # weather-v1 DEC-001 canonical source

MAX_DAYS_UNTIL_30CM = 120     # right-censoring horizon (matches synthetic-model.md / data-dictionary)
HORIZONS = (7, 14, 30)

FEATURE_SPEC_VERSION = "weather-v1+features-v1"

TRAIN_END = date(2024, 12, 31)
EMBARGO_1 = (date(2025, 1, 1), date(2025, 1, 30))     # purged, both splits' boundary effects
VALID_START, VALID_END = date(2025, 1, 31), date(2025, 8, 31)
EMBARGO_2 = (date(2025, 9, 1), date(2025, 9, 30))
TEST_START = date(2025, 10, 1)
EMBARGO_DAYS = 30   # >= the longest height target horizon (30d); protects against a train-anchored
                    # row's target and a validation-anchored row's lag referencing overlapping dates


# --------------------------------------------------------------- corridor extras (DEC-002)
def load_corridor_extras():
    """water_deficit_90d (trailing, daily) and relative_humidity_7d_pct (weekly), corridor cell."""
    dates, bal = [], []
    with WX_DAILY.open(encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            if r["grid_cell_id"] != CORRIDOR_CELL:
                continue
            dates.append(r["obs_date"])
            bal.append(float(r["precip_mm"]) - float(r["et0_mm"]))
    idx = {d: i for i, d in enumerate(dates)}

    def deficit_90d(as_of_date: str):
        if as_of_date not in idx:
            return None
        i = idx[as_of_date]
        lo = max(0, i - 89)
        return round(sum(bal[lo:i + 1]), 2)

    rh = {}
    with WX_WEEKLY.open(encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            if r["grid_cell_id"] == CORRIDOR_CELL:
                rh[r["as_of_date"]] = float(r["rh_week_pct"]) if r.get("rh_week_pct") not in (None, "") else None

    return deficit_90d, rh


# --------------------------------------------------------------- load one synthetic dataset
def load_dataset(name: str):
    d = SYN / name
    obs = list(csv.DictReader((d / "height_observations.csv").open(encoding="utf-8")))
    roc = list(csv.DictReader((d / "rocada_events.csv").open(encoding="utf-8")))
    manifest = json.loads((d / "manifest.json").read_text(encoding="utf-8"))
    return obs, roc, manifest


def fnum(x):
    return None if x in (None, "") else float(x)


def finto(x):
    return None if x in (None, "") else int(float(x))


# --------------------------------------------------------------- split assignment (dev only)
def temporal_split(as_of: date) -> tuple[str, str]:
    """Returns (split, reason). 'excluded' with an embargo reason purges boundary rows so a
    train-anchored row's target window and a validation-anchored row's lag window cannot
    reference overlapping dates (no information from the same future in two splits)."""
    if as_of <= TRAIN_END:
        return "train", "temporal"
    if EMBARGO_1[0] <= as_of <= EMBARGO_1[1]:
        return "excluded", "embargo"
    if VALID_START <= as_of <= VALID_END:
        return "validation", "temporal"
    if EMBARGO_2[0] <= as_of <= EMBARGO_2[1]:
        return "excluded", "embargo"
    if as_of >= TEST_START:
        return "test", "temporal"
    return "excluded", "embargo"


def label_observation_cutoff(split: str) -> date | None:
    """The LAST date a `target_days_until_30cm` forward search is allowed to look at for a row
    already assigned to `split`, per the Codex review's R01 correction. `None` means "no
    artificial cutoff -- look all the way to the real end of this (dataset, trecho) series".

    The embargo periods exist precisely so a label CAN legitimately be formed from them --
    EMBARGO_1/EMBARGO_2 are dead zones that are never themselves a training/validation/test
    EXAMPLE, so a TRAIN or VALIDATION row's label consuming an embargo date costs nothing. What
    must never happen is a label consuming a date that belongs to the NEXT split's own examples
    (a VALIDATION anchor's own feature row, or a TEST anchor's own feature row) -- that is the
    actual cross-split information leak. Hence the cutoff sits at the day *before* the next
    split's own start, not at the end of the embargo:
        TRAIN      -> the day before VALID_START (i.e. all of EMBARGO_1 is fair game)
        VALIDATION -> the day before TEST_START   (i.e. all of EMBARGO_2 is fair game)
        TEST       -> no cutoff; TEST is the last split, evaluated once, so "how far it can look"
                      is bounded only by the real end of the series (see `dataset_end` below).
        excluded (embargo-anchored dev rows, and the OOD holdout, which is always 'excluded' and
        also evaluated once like TEST) -> no cutoff; these rows are never read by any
        train/validation/test-filtered script (`r["split"] == "train"/"validation"/"test"`), so
        there is nothing to protect them from leaking into.
    """
    if split == "train":
        return VALID_START - timedelta(days=1)
    if split == "validation":
        return TEST_START - timedelta(days=1)
    return None


def blocked_split(trecho_id: str) -> str:
    h = int(hashlib.md5(trecho_id.encode("utf-8")).hexdigest(), 16) % 20
    if h < 14:
        return "train"
    if h < 17:
        return "validation"
    return "test"


# ------------------------------------------------------- days_until_30cm (R01/R02/R04 fix)
def build_days_until_30cm(i, as_of, dates, heights, ready, roc_dates_sorted, split):
    """The corrected `target_days_until_30cm` construction. Answers, honestly, the operational
    question *"if no new intervention occurs, in how many days does this trecho reach 30cm?"* by
    racing four mutually exclusive outcomes, in chronological order, over this trecho's own
    observation sequence starting at `as_of` (t=0 allowed):

        EVENT                a ready reading with height > 30cm occurs BEFORE any new roçada, and
                              within the observable window below. This is the only outcome that
                              gets a numeric `target_days_until_30cm` value.
        CENSORED_INTERVENTION a new roçada occurs before any such crossing is seen. The search
                              STOPS at the roçada -- it never looks past it for a later, causally
                              unrelated post-cut crossing (that would be answering a different
                              question than the one asked at `as_of`). The true counterfactual
                              "how long would it have taken without that roçada" is unknown and is
                              never invented.
        CENSORED_HORIZON      the full 120-day horizon was actually, observably cleared -- no
                              event, no roçada, no split boundary, no dataset end -- with neither
                              a crossing nor a roçada found. This is a genuine "we watched for 120
                              days and nothing happened", not an assumption.
        CENSORED_END_OF_FOLLOWUP  the observable window closes before 120 days for a reason that
                              has nothing to do with the vegetation itself: either this row's own
                              split is not allowed to look any further (`split_boundary` -- see
                              `label_observation_cutoff`, the R01 fix) or the real dataset simply
                              has no more observations yet (`dataset_end` -- the R04 fix). Neither
                              sub-case is reported as ">120 days"; both mean "we don't know what
                              happens after this point", and get NO numeric
                              `target_days_until_30cm` value.

    Returns (days_until, outcome, censoring_time_days, followup_end_cause):
      days_until            float days to the crossing, ONLY for outcome == "event"; else None.
      outcome               one of "event" / "censored_intervention" / "censored_horizon" /
                             "censored_end_of_followup".
      censoring_time_days   for "censored_intervention": days to the intervening roçada.
                             for "censored_horizon": always 120 (the full horizon was cleared).
                             for "censored_end_of_followup": days actually, observably cleared
                             before the window closed (< 120 by construction -- this is exactly
                             the number this outcome exists to NOT let get confused with 120).
                             None for "event" (use `days_until` instead).
      followup_end_cause    "split_boundary" or "dataset_end", ONLY for
                             "censored_end_of_followup"; else None.
    """
    as_of_ord = as_of.toordinal()
    horizon_end_ord = as_of_ord + MAX_DAYS_UNTIL_30CM
    dataset_end_ord = dates[-1].toordinal()   # last real observation of this (dataset, trecho)

    cutoff = label_observation_cutoff(split)
    cutoff_ord = cutoff.toordinal() if cutoff is not None else None

    if cutoff_ord is not None and cutoff_ord < dataset_end_ord:
        end_of_followup_ord, end_of_followup_cause = cutoff_ord, "split_boundary"
    elif cutoff_ord is not None:  # cutoff_ord >= dataset_end_ord: the dataset itself is tighter
        end_of_followup_ord, end_of_followup_cause = dataset_end_ord, "dataset_end"
    else:  # test / excluded: no artificial cutoff at all
        end_of_followup_ord, end_of_followup_cause = dataset_end_ord, "dataset_end"

    horizon_fully_observable = horizon_end_ord <= end_of_followup_ord
    observable_end_ord = min(horizon_end_ord, end_of_followup_ord)

    # A roçada is a known event on its own recorded date -- it does not need a later observation
    # to "confirm" it happened. B1.1 fix: the old version only ever noticed a roçada while
    # visiting an observation dated on/after it, so a roçada that fell inside the observable
    # window but had no observation between it and `observable_end_ord` was invisible and fell
    # through to censored_horizon/censored_end_of_followup instead. The roçada's own date is now
    # compared directly against the crossing's date, independent of observation spacing.
    next_rocada_ord = next((rd.toordinal() for rd in roc_dates_sorted if rd > as_of), None)
    if next_rocada_ord is not None and next_rocada_ord > observable_end_ord:
        next_rocada_ord = None  # known, but outside the window we are allowed to look at -- moot

    crossing_ord = None
    j = i
    while j < len(dates) and dates[j].toordinal() <= observable_end_ord:
        if ready[j] and heights[j] > 30.0:
            crossing_ord = dates[j].toordinal()
            break
        j += 1

    # Chronological race: a crossing strictly before the roçada is a clean event; a roçada on or
    # before the crossing (including a same-day tie, or no crossing found at all) censors by
    # intervention -- exactly the already-documented "intervention wins a tie" convention.
    if crossing_ord is not None and (next_rocada_ord is None or crossing_ord < next_rocada_ord):
        return float(crossing_ord - as_of_ord), "event", None, None
    if next_rocada_ord is not None:
        return None, "censored_intervention", next_rocada_ord - as_of_ord, None

    if horizon_fully_observable:
        return None, "censored_horizon", float(MAX_DAYS_UNTIL_30CM), None
    return None, "censored_end_of_followup", float(observable_end_ord - as_of_ord), end_of_followup_cause


# --------------------------------------------------------------- per-(dataset,trecho) builder
def build_trecho_rows(dataset_name, trecho_id, obs_rows, roc_dates, deficit_90d, rh_weekly, is_dev):
    obs_rows = sorted(obs_rows, key=lambda r: r["observation_date"])
    dates = [date.fromisoformat(r["observation_date"]) for r in obs_rows]
    heights = [float(r["observed_height_cm"]) for r in obs_rows]
    ready = [r["operational_status"] == "ready" for r in obs_rows]
    by_date = {r["observation_date"]: r for r in obs_rows}
    wdp = {r["observation_date"]: int(r["week_days_present"]) for r in obs_rows}

    roc_dates_sorted = sorted(roc_dates)

    def cycle_index(d: date) -> int:
        return sum(1 for rd in roc_dates_sorted if rd <= d)

    cyc = [cycle_index(d) for d in dates]

    rows = []
    for i, r in enumerate(obs_rows):
        as_of = dates[i]
        as_of_s = r["observation_date"]

        # ---- split classification, moved up from the end of the loop body: the days_until_30cm
        # block below needs to know which split this row belongs to BEFORE it can pick the right
        # label_observation_cutoff() (R01 fix) ----
        if is_dev:
            sp, split_reason = temporal_split(as_of)
        else:
            sp, split_reason = "excluded", "ood_holdout"

        # ---- DEC-004: anchor week must be full ----
        if wdp.get(as_of_s, 7) < 7:
            continue  # not even materialised; see report for the count

        new_cycle = 1 if (i == 0 or cyc[i] != cyc[i - 1]) else 0
        if new_cycle or i == 0:
            height_prev = height_lag2 = weekly_growth = None
        else:
            height_prev = heights[i - 1]
            weekly_growth = round(heights[i] - height_prev, 2)
            height_lag2 = heights[i - 2] if i >= 2 and cyc[i - 2] == cyc[i] else None

        weeks_obs_8 = sum(1 for dd in dates if 0 <= (as_of - dd).days <= 56)

        # ---- targets: strictly future observations of the SAME (dataset,trecho) series ----
        # Observations sit on a fixed 7-day (ISO-week) grid, so a +7d / +14d target lands
        # exactly on a grid date, but +30d never does (30 is not a multiple of 7). Every
        # horizon is therefore resolved the same way: the nearest observation to
        # as_of+h, within a +-3 day tolerance, preferring the closer date. This is also
        # more realistic than an exact-date match would be for a future real capture
        # cadence that will not be perfectly regular either.
        TOL_DAYS = 3
        targets = {}
        target_actual_offset = {}
        excluded_by_dec004 = False
        for h in HORIZONS:
            target_at = as_of.toordinal() + h
            best_d, best_dist = None, None
            for cand in dates:
                dist = abs(cand.toordinal() - target_at)
                if dist <= TOL_DAYS and (best_dist is None or dist < best_dist):
                    best_d, best_dist = cand, dist
            if best_d is None:
                targets[f"target_height_plus_{h}d_cm"] = None
                target_actual_offset[h] = None
            else:
                tgt_date = best_d.isoformat()
                if wdp.get(tgt_date, 7) < 7:
                    excluded_by_dec004 = True
                    targets[f"target_height_plus_{h}d_cm"] = None
                else:
                    targets[f"target_height_plus_{h}d_cm"] = float(by_date[tgt_date]["observed_height_cm"])
                target_actual_offset[h] = (best_d - as_of).days
        if excluded_by_dec004:
            continue

        # days_until_30cm (Codex review, R01/R02/R04 remediation -- see build_days_until_30cm's
        # own docstring for the full algorithm and the four-way outcome taxonomy).
        days_until, outcome, censoring_time_days, followup_end_cause = build_days_until_30cm(
            i, as_of, dates, heights, ready, roc_dates_sorted, sp,
        )
        censored = 0 if outcome == "event" else 1  # legacy binary field, kept for the scripts
                                                    # this phase does not touch yet (Phase 6-9) --
                                                    # 0 only for a genuine event, never a coarsening
                                                    # that turns a censored row into an event.

        rec = dict(
            feature_row_id=f"{dataset_name}:{trecho_id}:{as_of_s}",
            trecho_id=trecho_id, rodovia=r["rodovia"], sentido=r["sentido"],
            iso_year=r["iso_year"], iso_week=r["iso_week"], as_of_date=as_of_s,
            dataset=dataset_name, scenario=r["scenario"],
            data_source=r["data_source"], provenance=r["provenance"],

            height_cm=float(r["observed_height_cm"]),
            height_prev_cm=height_prev, height_lag2_cm=height_lag2,
            weekly_growth_cm=weekly_growth, weeks_observed_last_8=weeks_obs_8,
            rocada_in_new_cycle=new_cycle,
            days_since_rocada=int(r["days_since_rocada"]),

            gdd_week_tb10_c=fnum(r["gdd_week_tb10_c"]), gdd_week_tb15_c=fnum(r["gdd_week_tb15_c"]),
            gdd_week_tb17_c=fnum(r["gdd_week_tb17_c"]),
            tmin_week_c=fnum(r["tmin_week_c"]), tmean_week_c=fnum(r["tmean_week_c"]), tmax_week_c=fnum(r["tmax_week_c"]),
            rain_7d_mm=fnum(r["rain_7d_mm"]), rain_14d_mm=fnum(r["rain_14d_mm"]), rain_30d_mm=fnum(r["rain_30d_mm"]),
            shortwave_7d_mj_m2=fnum(r["shortwave_7d_mj_m2"]), et0_7d_mm=fnum(r["et0_7d_mm"]),
            water_deficit_30d=fnum(r["water_deficit_30d"]), water_deficit_90d=deficit_90d(as_of_s),
            relative_humidity_7d_pct=rh_weekly.get(as_of_s),

            week_of_year=finto(r["week_of_year"]), season_sin=fnum(r["season_sin"]), season_cos=fnum(r["season_cos"]),
            dry_season_flag=finto(r["dry_season_flag"]),
            vegetation_type=r["vegetation_type"],
            km_mid=fnum(r["km_mid"]), centroid_lat=fnum(r["centroid_lat"]), centroid_lon=fnum(r["centroid_lon"]),
            grid_cell_id=r["grid_cell_id"],
            operational_status=r["operational_status"], blockers=r["blockers"],
            cell_coverage=fnum(r["cell_coverage"]), frame_count=finto(r["frame_count"]),
            nivel_observed=finto(r["nivel_observed"]),
            week_days_present=wdp.get(as_of_s, 7),

            target_height_plus_7d_cm=targets["target_height_plus_7d_cm"],
            target_height_plus_14d_cm=targets["target_height_plus_14d_cm"],
            target_height_plus_30d_cm=targets["target_height_plus_30d_cm"],
            target_height_plus_7d_actual_offset_days=target_actual_offset[7],
            target_height_plus_14d_actual_offset_days=target_actual_offset[14],
            target_height_plus_30d_actual_offset_days=target_actual_offset[30],
            target_days_until_30cm=days_until,
            target_days_until_30cm_censored=censored,
            target_days_until_30cm_horizon_days=MAX_DAYS_UNTIL_30CM,
            target_days_until_30cm_outcome=outcome,
            target_days_until_30cm_censoring_time_days=censoring_time_days,
            target_days_until_30cm_followup_end_cause=followup_end_cause,

            true_height_cm_DIAGNOSTIC_ONLY=float(r["true_height_cm"]),  # never a feature or target
            nivel_true_DIAGNOSTIC_ONLY=finto(r["nivel_true"]),

            generator_version=r["generator_version"], generator_seed=r["generator_seed"],
            generator_params_hash=r["generator_params_hash"],
            feature_spec_version=FEATURE_SPEC_VERSION,
            built_at=None,  # filled by caller
        )

        rec["split"] = sp
        rec["split_reason"] = split_reason
        if is_dev:
            rec["split_blocked_trecho"] = blocked_split(trecho_id)
            rec["split_scheme"] = "temporal-v1+blocked-by-trecho-v1"
        else:
            rec["split_blocked_trecho"] = "excluded"
            rec["split_scheme"] = "ood_holdout"

        rows.append(rec)
    return rows


def build_all(built_at: str | None = None):
    built_at = built_at or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    deficit_90d, rh_weekly = load_corridor_extras()

    dev_rows, ood_rows = [], []
    stats = {"per_dataset": {}}
    for name, is_dev in (("main", True), ("seed2", True), ("ood", False)):
        obs, roc, manifest = load_dataset(name)
        by_trecho = {}
        roc_by_trecho = {}
        for r in obs:
            by_trecho.setdefault(r["trecho_id"], []).append(r)
        for rr in roc:
            roc_by_trecho.setdefault(rr["trecho_id"], []).append(date.fromisoformat(rr["occurred_on"]))

        out = []
        excluded_dec004 = 0
        total_obs = len(obs)
        for trecho_id, tobs in by_trecho.items():
            rws = build_trecho_rows(name, trecho_id, tobs, roc_by_trecho.get(trecho_id, []),
                                    deficit_90d, rh_weekly, is_dev)
            out.extend(rws)
        for rec in out:
            rec["built_at"] = built_at
        stats["per_dataset"][name] = {
            "n_observations": total_obs, "n_feature_rows": len(out),
            "excluded_dec004_or_anchor": total_obs - len(out),
        }
        if is_dev:
            dev_rows.extend(out)
        else:
            ood_rows.extend(out)

    return dev_rows, ood_rows, stats, built_at
