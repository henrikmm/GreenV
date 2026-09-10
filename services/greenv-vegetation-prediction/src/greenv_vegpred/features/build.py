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
"""
from __future__ import annotations
import csv, hashlib, json, math
from datetime import date, datetime, timezone
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


def blocked_split(trecho_id: str) -> str:
    h = int(hashlib.md5(trecho_id.encode("utf-8")).hexdigest(), 16) % 20
    if h < 14:
        return "train"
    if h < 17:
        return "validation"
    return "test"


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

        # days_until_30cm: forward search over this trecho's own observation sequence,
        # starting at as_of (t=0 allowed), first READY reading with height > 30, within horizon.
        days_until = None
        censored = 1
        for j in range(i, len(obs_rows)):
            dj = dates[j]
            delta = (dj - as_of).days
            if delta > MAX_DAYS_UNTIL_30CM:
                break
            if ready[j] and heights[j] > 30.0:
                days_until = delta
                censored = 0
                break

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

            true_height_cm_DIAGNOSTIC_ONLY=float(r["true_height_cm"]),  # never a feature or target
            nivel_true_DIAGNOSTIC_ONLY=finto(r["nivel_true"]),

            generator_version=r["generator_version"], generator_seed=r["generator_seed"],
            generator_params_hash=r["generator_params_hash"],
            feature_spec_version=FEATURE_SPEC_VERSION,
            built_at=None,  # filled by caller
        )

        if is_dev:
            sp, reason = temporal_split(as_of)
            rec["split"] = sp
            rec["split_reason"] = reason
            rec["split_blocked_trecho"] = blocked_split(trecho_id)
            rec["split_scheme"] = "temporal-v1+blocked-by-trecho-v1"
        else:
            rec["split"] = "excluded"
            rec["split_reason"] = "ood_holdout"
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
