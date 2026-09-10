#!/usr/bin/env python3
"""
Phase 9 — calibrate split-conformal prediction intervals on VALIDATION (only, and only after the
Phase 7 model is already frozen), freeze them, THEN measure coverage on TEST once, THEN look at
OOD purely as a robustness diagnostic. No hyperparameter, feature, or method choice in this file
is ever made by looking at TEST or OOD -- the calibration block below (§1) runs and WRITES its
output to disk before this script's own code ever opens `split == 'test'` or the OOD file.

Reuses, WITHOUT RETRAINING OR RESELECTING ANYTHING:
  - models/artifacts/days_until_30cm__random_forest__keep_plus_candidate.joblib  (framing B, frozen)
  - models/artifacts/height__random_forest__keep_plus_candidate.joblib          (frozen)

Writes:
  data/models/interval_calibration.json     (frozen calibration: q_hat per confidence level)
  data/models/phase9_interval_evaluation.json  (TEST coverage, OOD diagnostic, sanity checks)
  reports/forecast-method.md
"""
from __future__ import annotations
import csv, json, sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
from joblib import load

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from greenv_vegpred.evaluate.harness import rocada_bucket, season_bucket, nivel_bucket, fnum  # noqa: E402
from greenv_vegpred.forecast.interval import (  # noqa: E402
    conformal_quantile, build_days_forecast, build_height_forecast, CRITICAL_HEIGHT_CM,
)
from greenv_vegpred.models.ml_common import DAYS_HORIZON  # noqa: E402

from train_and_evaluate_ml import FEAT, HORIZONS  # noqa: E402

MODULE_ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = MODULE_ROOT / "models" / "artifacts"
OOD_FEAT = MODULE_ROOT / "data" / "features" / "feature_row_ood_holdout.csv"
CALIB_JSON = MODULE_ROOT / "data" / "models" / "interval_calibration.json"
OUT_JSON = MODULE_ROOT / "data" / "models" / "phase9_interval_evaluation.json"
FORECAST_METHOD_MD = MODULE_ROOT / "reports" / "forecast-method.md"

ALPHAS = {0.80: 0.20, 0.90: 0.10}  # confidence -> miscoverage
MODEL_NAME = "random_forest__keep_plus_candidate__framing_b"


def load_rows(path):
    return list(csv.DictReader(Path(path).open(encoding="utf-8")))


def days_calibration_population(rows):
    """The population an individual days-until-30cm interval is actually used for: height not
    already at/above threshold (that case is deterministic, no interval needed), and a KNOWN,
    non-censored true value to compare against. Calibrating on the full population (including the
    trivially-easy already-critical rows, whose residual is ~0) would bias q_hat downward for the
    population the interval actually serves."""
    return [r for r in rows if fnum(r.get("height_cm")) is not None and fnum(r["height_cm"]) < CRITICAL_HEIGHT_CM
           and r["target_days_until_30cm_censored"] == "0" and fnum(r["target_days_until_30cm"]) is not None]


def main():
    dev = load_rows(FEAT)
    train = [r for r in dev if r["split"] == "train"]
    val = [r for r in dev if r["split"] == "validation"]
    print(f"train={len(train)} validation={len(val)} (frozen artifacts only, no refitting here)")

    days_model = load(ARTIFACTS / "days_until_30cm__random_forest__keep_plus_candidate.joblib")
    height_model = load(ARTIFACTS / "height__random_forest__keep_plus_candidate.joblib")

    # ============================================================ 1. CALIBRATION (VALIDATION only)
    print("\n=== 1. Calibration on VALIDATION (frozen model, no TEST/OOD read yet) ===")
    calib_pop = days_calibration_population(val)
    day_points = days_model.predict_batch(calib_pop)  # plain float array, capped at DAYS_HORIZON internally
    # predict_batch clips at 0 but does NOT return None for >120 (only .predict() does, row-wise);
    # exclude any row whose point sits AT the horizon cap -- that is the batch-path's equivalent of
    # "beyond_horizon", and including it would treat a censored-by-construction point as if it were
    # a normal residual.
    keep_mask = day_points < DAYS_HORIZON
    n_excluded_capped = int((~keep_mask).sum())
    calib_pop_used = [r for r, keep in zip(calib_pop, keep_mask) if keep]
    day_points_used = day_points[keep_mask]
    day_residuals = np.abs(np.array([float(r["target_days_until_30cm"]) for r in calib_pop_used]) - day_points_used)

    days_q = {conf: conformal_quantile(day_residuals, alpha) for conf, alpha in ALPHAS.items()}
    print(f"  days calibration: n_population={len(calib_pop)} n_excluded_beyond_horizon={n_excluded_capped} "
         f"n_used={len(calib_pop_used)}  q80={days_q[0.80]:.2f}  q90={days_q[0.90]:.2f}")

    height_q = {}
    height_calib_n = {}
    for h in HORIZONS:
        tgt = f"target_height_plus_{h}d_cm"
        rows_h = [r for r in val if fnum(r[tgt]) is not None]
        preds_h = height_model.predict_height_batch(rows_h, h)
        resid_h = np.abs(np.array([float(r[tgt]) for r in rows_h]) - preds_h)
        height_calib_n[h] = len(rows_h)
        for conf, alpha in ALPHAS.items():
            height_q[(h, conf)] = conformal_quantile(resid_h, alpha)
        print(f"  height +{h}d calibration: n={len(rows_h)}  q80={height_q[(h,0.80)]:.2f}  q90={height_q[(h,0.90)]:.2f}")

    calibration = {
        "method": "split conformal (Vovk et al. 2005 / Lei et al. 2018): symmetric band "
                 "[point - q, point + q], q = ceil((n+1)(1-alpha))-th smallest |residual| on a "
                 "held-out calibration sample never used to fit the model.",
        "calibration_split": "validation",
        "model_frozen_from": "Phase 7 (data/models/ml_validation_metrics.json -> "
                            "best_overall_height_config / best_overall_days_config)",
        "feature_spec_version": "weather-v1+features-v1",
        "random_state": 42,
        "days_until_30cm": {
            "n_calibration_population": len(calib_pop),
            "n_excluded_beyond_horizon_point": n_excluded_capped,
            "n_used": len(calib_pop_used),
            "censoring_treatment": "censored VALIDATION rows (unknown true value) excluded from "
                                  "calibration entirely; rows whose OWN point estimate already "
                                  "sits at the 120-day cap are also excluded (undefined residual "
                                  "against a censored point) -- both counts reported above, not "
                                  "hidden.",
            "population_restriction": "height_cm < 30 at the anchor row (the >=30 case is "
                                     "deterministic and uses no interval)",
            "q_by_confidence": {str(c): round(q, 3) for c, q in days_q.items()},
        },
        "height": {
            str(h): {"n_calibration": height_calib_n[h],
                    "q_by_confidence": {str(c): round(height_q[(h, c)], 3) for c in ALPHAS}}
            for h in HORIZONS
        },
    }
    CALIB_JSON.write_text(json.dumps(calibration, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"  Wrote {CALIB_JSON} (frozen -- nothing below may change these numbers)")

    results = {"calibration": calibration}

    # ============================================================ 2. TEST coverage (opened once)
    print("\n=== 2. TEST coverage (frozen interval, measured once) ===")
    test = [r for r in dev if r["split"] == "test"]

    def days_coverage(rows, label):
        pop = days_calibration_population(rows)
        points = days_model.predict_batch(pop)
        keep = points < DAYS_HORIZON
        n_model_beyond_horizon_but_truth_known = int((~keep).sum())
        pop_used = [r for r, k in zip(pop, keep) if k]
        pts_used = points[keep]
        truths = np.array([float(r["target_days_until_30cm"]) for r in pop_used])
        out = {}
        for conf in ALPHAS:
            q = days_q[conf]
            lower = np.clip(pts_used - q, 0.0, None)
            upper = np.clip(pts_used + q, None, DAYS_HORIZON)
            covered = (truths >= lower) & (truths <= upper)
            widths = upper - lower
            out[str(conf)] = {
                "n": len(pop_used), "coverage_pct": round(100 * float(covered.mean()), 1),
                "mean_width_days": round(float(widths.mean()), 2),
                "median_width_days": round(float(np.median(widths)), 2),
                "n_not_covered": int((~covered).sum()),
            }
        n_true_censored = sum(1 for r in rows if r["target_days_until_30cm_censored"] == "1")
        return out, {"n_population": len(pop), "n_true_censored_in_split": n_true_censored,
                    "n_model_beyond_horizon_but_truth_known": n_model_beyond_horizon_but_truth_known}

    test_days_cov, test_days_meta = days_coverage(test, "test")
    results["test_days_coverage"] = {**test_days_cov, "meta": test_days_meta}
    print(f"  days coverage: {json.dumps(test_days_cov, indent=1)}")
    print(f"  meta: {test_days_meta}")

    def stratified_days_coverage(rows, key_fn, confidence=0.90, min_n=30):
        pop = days_calibration_population(rows)
        points = days_model.predict_batch(pop)
        keep = points < DAYS_HORIZON
        pop_used = [r for r, k in zip(pop, keep) if k]
        pts_used = points[keep]
        q = days_q[confidence]
        buckets = defaultdict(list)
        for r, p in zip(pop_used, pts_used):
            k = key_fn(r)
            if k is None:
                continue
            lower, upper = max(0.0, p - q), min(DAYS_HORIZON, p + q)
            truth = float(r["target_days_until_30cm"])
            buckets[k].append(lower <= truth <= upper)
        return {str(k): {"n": len(v), "coverage_pct": round(100 * float(np.mean(v)), 1)}
               for k, v in buckets.items() if len(v) >= min_n}

    results["test_days_coverage_stratified_90pct"] = {
        "by_nivel": stratified_days_coverage(test, nivel_bucket),
        "by_season": stratified_days_coverage(test, season_bucket),
        "by_rocada_proximity": stratified_days_coverage(test, rocada_bucket),
    }
    print(f"  stratified (90%): {json.dumps(results['test_days_coverage_stratified_90pct'], indent=1)}")

    def height_coverage(rows, h, confidence):
        tgt = f"target_height_plus_{h}d_cm"
        rows_h = [r for r in rows if fnum(r[tgt]) is not None]
        preds = height_model.predict_height_batch(rows_h, h)
        q = height_q[(h, confidence)]
        lower = np.clip(preds - q, 0.0, None)
        upper = np.clip(preds + q, None, 150.0)
        truths = np.array([float(r[tgt]) for r in rows_h])
        covered = (truths >= lower) & (truths <= upper)
        widths = upper - lower
        return {"n": len(rows_h), "coverage_pct": round(100 * float(covered.mean()), 1),
               "mean_width_cm": round(float(widths.mean()), 2), "median_width_cm": round(float(np.median(widths)), 2)}

    results["test_height_coverage"] = {
        str(h): {str(c): height_coverage(test, h, c) for c in ALPHAS} for h in HORIZONS
    }
    print(f"  height coverage: {json.dumps(results['test_height_coverage'], indent=1)}")

    # ============================================================ 3. OOD diagnostic (opened once)
    print("\n=== 3. OOD diagnostic (calibration already frozen; not recalibrated) ===")
    ood = load_rows(OOD_FEAT)
    ood_days_cov, ood_days_meta = days_coverage(ood, "ood")
    # composition check, per the phase's own instruction: never present an OOD number without it
    h0_all = [fnum(r.get("height_cm")) for r in ood if fnum(r.get("height_cm")) is not None]
    frac_ge30_ood = sum(1 for v in h0_all if v >= CRITICAL_HEIGHT_CM) / len(h0_all)
    h0_all_test = [fnum(r.get("height_cm")) for r in test if fnum(r.get("height_cm")) is not None]
    frac_ge30_test = sum(1 for v in h0_all_test if v >= CRITICAL_HEIGHT_CM) / len(h0_all_test)
    results["ood_days_coverage"] = {**ood_days_cov, "meta": ood_days_meta}
    results["ood_vs_test_composition_caveat"] = {
        "frac_anchor_height_ge_30cm_test": round(frac_ge30_test, 3),
        "frac_anchor_height_ge_30cm_ood": round(frac_ge30_ood, 3),
        "note": "OOD's forecast-regime population is not the same difficulty mix as TEST's -- see "
               "Phase 8's identical finding for point-accuracy. Any apparent OOD coverage "
               "improvement must be read against this composition shift, not as robustness.",
    }
    print(f"  ood days coverage: {json.dumps(ood_days_cov, indent=1)}")
    print(f"  composition caveat: {results['ood_vs_test_composition_caveat']}")

    # ============================================================ 4. Sanity checks A-F
    print("\n=== 4. Sanity checks ===")
    template = dict(test[0])  # borrow a real row's shape/columns; overwritten per scenario below

    def scenario(overrides):
        r = dict(template)
        r.update(overrides)
        return r

    scenarios = {
        "A_height_35_already_critical": scenario({"height_cm": "35.0"}),
        "B_height_29_strong_growth": scenario({
            "height_cm": "29.0", "height_prev_cm": "20.0", "weekly_growth_cm": "9.0",
            "days_since_rocada": "40", "gdd_week_tb15_c": "60.0", "tmin_week_c": "20.0",
            "rain_30d_mm": "150.0", "water_deficit_30d": "10.0",
        }),
        "C_height_low_slow_growth": scenario({
            "height_cm": "4.0", "height_prev_cm": "3.8", "weekly_growth_cm": "0.2",
            "days_since_rocada": "10", "gdd_week_tb15_c": "5.0", "tmin_week_c": "8.0",
            "rain_30d_mm": "5.0", "water_deficit_30d": "-80.0",
        }),
        "D_missing_anchor_height": scenario({"height_cm": ""}),
    }
    sanity_out = {}
    for name, row in scenarios.items():
        fc = build_days_forecast(row, days_model, days_q, MODEL_NAME, confidence=0.90)
        sanity_out[name] = fc
        print(f"  {name}: {fc}")

    # E/F: rather than hope a hand-built row happens to land on the clamp branches, use the real
    # extremes of the VALIDATION calibration population itself (still never touching TEST/OOD) --
    # the smallest point estimate is the row most likely to need the lower-bound clamp (point < q),
    # the largest finite one is most likely to need the upper-bound horizon clamp (point + q > 120).
    order = np.argsort(day_points_used)
    e_row, e_point = calib_pop_used[order[0]], float(day_points_used[order[0]])
    f_row, f_point = calib_pop_used[order[-1]], float(day_points_used[order[-1]])
    e_fc = build_days_forecast(e_row, days_model, days_q, MODEL_NAME, 0.90)
    f_fc = build_days_forecast(f_row, days_model, days_q, MODEL_NAME, 0.90)
    sanity_out["E_lower_bound_clamped_to_zero"] = {
        "point_estimate": round(e_point, 2), "q90": round(days_q[0.90], 2),
        "raw_lower_before_clamp": round(e_point - days_q[0.90], 2), "forecast": e_fc,
    }
    sanity_out["F_upper_bound_horizon_handling"] = {
        "point_estimate": round(f_point, 2), "q90": round(days_q[0.90], 2),
        "raw_upper_before_clamp": round(f_point + days_q[0.90], 2), "forecast": f_fc,
    }
    print(f"  E: {sanity_out['E_lower_bound_clamped_to_zero']}")
    print(f"  F: {sanity_out['F_upper_bound_horizon_handling']}")

    results["sanity_checks"] = sanity_out

    # ============================================================ write + example object
    example_row = next(r for r in test if fnum(r.get("height_cm")) is not None and fnum(r["height_cm"]) < 30.0)
    example = build_days_forecast(example_row, days_model, days_q, MODEL_NAME, confidence=0.90)
    results["example_forecast_object_90pct"] = example
    OUT_JSON.write_text(json.dumps(results, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
    print(f"\nWrote {OUT_JSON}")
    print(f"Example object: {json.dumps(example, indent=1, ensure_ascii=False)}")
    return results


if __name__ == "__main__":
    main()
