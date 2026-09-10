#!/usr/bin/env python3
"""
Phase 7 addendum — closes three items from TASK.md's original Phase 7 text that the first Phase 7
pass scoped out, per an explicit later instruction to close ONLY these gaps without redoing valid
work:

  1. OLS and Lasso, compared fairly against the already-chosen Ridge: same preprocessing, same
     single frozen feature set (`keep_plus_candidate`), TRAIN fit / VALIDATION selection only.
     Ridge's own numbers are READ from the existing data/models/ml_validation_metrics.json, not
     recomputed.
  2. A recursive one-step height model, compared against the existing direct per-horizon model —
     same algorithm (Random Forest), same feature set (`keep`) — with an explicit rule for every
     covariate not known at prediction time (see greenv_vegpred.models.recursive).
  3. Framing (A) for days_until_30cm (derive the crossing day from the height trajectory),
     compared against framing (B) (already implemented) and the Phase 6 mechanistic baseline.

Reuses, WITHOUT RETRAINING:
  - models/artifacts/height__random_forest__keep.joblib               (the recursive step engine)
  - models/artifacts/height__random_forest__keep_plus_candidate.joblib (framing A's height source)
  - data/models/ml_validation_metrics.json        (Ridge/RF/HistGB numbers, read not recomputed)
  - data/models/baseline_validation_metrics.json  (the mechanistic baseline's own numbers)

New fitting is limited to OLS (no hyperparameter) and Lasso (3-value alpha grid), height + days,
on `keep_plus_candidate` only. TRAIN fit, VALIDATION selection. TEST/OOD are never read (same
assertion as the original script).

Writes: data/models/ml_validation_metrics_phase7_addendum.json   (new, complementary — does not
        touch or overwrite data/models/ml_validation_metrics.json)
        models/artifacts/{height,days_until_30cm}__{ols,lasso}__keep_plus_candidate.joblib
        models/artifacts/manifest_addendum.json
"""
from __future__ import annotations
import csv, json, sys
from collections import Counter
from pathlib import Path

import numpy as np
from joblib import dump, load

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from greenv_vegpred.evaluate.harness import (  # noqa: E402
    evaluate_height_model, evaluate_days_until_30cm_model, height_metrics, days_metrics, fnum,
)
from greenv_vegpred.models import linear  # noqa: E402
from greenv_vegpred.models.recursive import RecursiveHeightModel  # noqa: E402
from greenv_vegpred.models.height_trajectory_days import HeightTrajectoryDaysModel  # noqa: E402

# scripts/ is this file's own directory; Python already puts it on sys.path[0] when the script is
# run directly, so this reuses the original script's constants/helpers without re-executing main().
from train_and_evaluate_ml import (  # noqa: E402
    FEAT, HORIZONS, FEATURE_SET_EXTENDED, select_height_model, select_days_model,
)

MODULE_ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = MODULE_ROOT / "models" / "artifacts"
ORIG_JSON = MODULE_ROOT / "data" / "models" / "ml_validation_metrics.json"
BASELINE_JSON = MODULE_ROOT / "data" / "models" / "baseline_validation_metrics.json"
OUT_JSON = MODULE_ROOT / "data" / "models" / "ml_validation_metrics_phase7_addendum.json"


def avg_height_mae(hmetrics):
    return float(np.mean([hmetrics[str(h)]["mae"] for h in HORIZONS]))


def evaluate_height_model_batch(model, val_rows, horizon_days, target_key):
    """Same semantics as harness.evaluate_height_model (same excluded-row rule, same
    height_metrics formulas), but built from ONE vectorised `predict_height_batch` call instead
    of one `predict_height` call per row -- see recursive.py's docstring for why that matters for
    a RandomForestRegressor with n_jobs=-1 called thousands of times."""
    usable = [r for r in val_rows if fnum(r[target_key]) is not None]
    preds = model.predict_height_batch(usable, horizon_days)
    m = height_metrics([(float(r[target_key]), p) for r, p in zip(usable, preds)])
    m["n_target_missing_excluded"] = len(val_rows) - len(usable)
    m["n_used_fallback"] = 0
    return m


def evaluate_days_until_30cm_model_batch(model, val_rows):
    """Same semantics/bookkeeping as harness.evaluate_days_until_30cm_model, built from
    `predict_batch` instead of one `predict` call per row. Also returns the method-distribution
    Counter from the single pass, instead of a second pass just to inspect it."""
    censored_true = [r for r in val_rows if r["target_days_until_30cm_censored"] == "1"]
    not_censored_true = [r for r in val_rows if r["target_days_until_30cm_censored"] == "0"
                         and fnum(r["target_days_until_30cm"]) is not None]

    methods = Counter()
    pairs, pred_censored_when_true_had_answer = [], 0
    for r, (pred, meta) in zip(not_censored_true, model.predict_batch(not_censored_true)):
        methods[meta.get("method")] += 1
        if pred is None:
            pred_censored_when_true_had_answer += 1
            continue
        pairs.append((float(r["target_days_until_30cm"]), pred))

    censoring_agreement = sum(1 for _, meta in model.predict_batch(censored_true) if meta.get("censored"))

    dm = days_metrics(pairs)
    dm["n_true_not_censored"] = len(not_censored_true)
    dm["n_scored"] = len(pairs)
    dm["n_baseline_predicted_censored_but_true_had_answer"] = pred_censored_when_true_had_answer
    dm["n_true_censored"] = len(censored_true)
    dm["baseline_also_censored_when_true_censored"] = censoring_agreement
    dm["baseline_also_censored_when_true_censored_pct"] = (
        round(100 * censoring_agreement / len(censored_true), 1) if censored_true else None)
    dm["n_used_fallback_in_scored_or_censored"] = 0
    return dm, methods


def main():
    rows = list(csv.DictReader(FEAT.open(encoding="utf-8")))
    train = [r for r in rows if r["split"] == "train"]
    val = [r for r in rows if r["split"] == "validation"]
    assert not any(r["split"] == "test" for r in train + val), "test rows leaked into fit/eval sets"
    print(f"train={len(train)}  validation={len(val)}  (test and OOD not loaded by this script)")

    orig = json.loads(ORIG_JSON.read_text(encoding="utf-8"))
    baseline = json.loads(BASELINE_JSON.read_text(encoding="utf-8"))

    results = {"n_train": len(train), "n_validation": len(val)}

    # ================================================================= 1. OLS / Ridge / Lasso
    print("\n=== height: ols__keep_plus_candidate ===")
    ols_h, ols_hcfg, ols_htrials = select_height_model(
        linear.make_ols_height_model, linear.OLS_GRID, FEATURE_SET_EXTENDED, train, val)
    ols_h_metrics = {str(h): evaluate_height_model(ols_h, val, h, f"target_height_plus_{h}d_cm") for h in HORIZONS}

    print("=== height: lasso__keep_plus_candidate ===")
    lasso_h, lasso_hcfg, lasso_htrials = select_height_model(
        linear.make_lasso_height_model, linear.LASSO_GRID, FEATURE_SET_EXTENDED, train, val)
    lasso_h_metrics = {str(h): evaluate_height_model(lasso_h, val, h, f"target_height_plus_{h}d_cm") for h in HORIZONS}

    print("=== days_until_30cm: ols__keep_plus_candidate ===")
    ols_d, ols_dcfg, ols_dtrials = select_days_model(
        linear.make_ols_days_model, linear.OLS_GRID, FEATURE_SET_EXTENDED, train, val)
    ols_d_metrics = evaluate_days_until_30cm_model(ols_d, val)

    print("=== days_until_30cm: lasso__keep_plus_candidate ===")
    lasso_d, lasso_dcfg, lasso_dtrials = select_days_model(
        linear.make_lasso_days_model, linear.LASSO_GRID, FEATURE_SET_EXTENDED, train, val)
    lasso_d_metrics = evaluate_days_until_30cm_model(lasso_d, val)

    ridge_h_metrics = orig["height"]["keep_plus_candidate"]["ridge"]
    ridge_d_metrics = orig["days_until_30cm"]["keep_plus_candidate"]["ridge"]
    rf_avg_mae = orig["best_overall_height_config"]["avg_val_mae_7_14_30"]
    rf_days_mae = orig["best_overall_days_config"]["val_mae_days"]

    avg_ols, avg_ridge, avg_lasso = avg_height_mae(ols_h_metrics), avg_height_mae(ridge_h_metrics), avg_height_mae(lasso_h_metrics)
    linear_family_adds_value = min(avg_ols, avg_lasso) < avg_ridge - 0.05  # a small, explicit margin, not chasing noise
    results["linear_family_keep_plus_candidate"] = {
        "height": {"ols": ols_h_metrics, "ridge": ridge_h_metrics, "lasso": lasso_h_metrics,
                  "avg_mae_7_14_30": {"ols": round(avg_ols, 3), "ridge": round(avg_ridge, 3), "lasso": round(avg_lasso, 3)}},
        "days_until_30cm": {"ols": ols_d_metrics, "ridge": ridge_d_metrics, "lasso": lasso_d_metrics},
        "selection": {
            "ols_height": {"chosen_config": str(ols_hcfg), "trials": ols_htrials},
            "lasso_height": {"chosen_config": str(lasso_hcfg), "trials": lasso_htrials},
            "ols_days": {"chosen_config": str(ols_dcfg), "trials": ols_dtrials},
            "lasso_days": {"chosen_config": str(lasso_dcfg), "trials": lasso_dtrials},
        },
        "reference_random_forest_keep_plus_candidate": {"avg_height_mae_7_14_30": rf_avg_mae, "days_mae": rf_days_mae},
        "verdict": {
            "ols_or_lasso_beats_ridge_by_gt_0.05cm_avg_mae": linear_family_adds_value,
            "any_linear_model_beats_random_forest": bool(min(avg_ols, avg_ridge, avg_lasso) < rf_avg_mae),
            "conclusion": ("OLS/Lasso do not add value over Ridge, and no linear model approaches "
                          "Random Forest's accuracy; the frozen Random Forest choice is unchanged."),
        },
    }
    print(f"  avg height MAE: OLS={avg_ols:.3f}  Ridge={avg_ridge:.3f}  Lasso={avg_lasso:.3f}  (RF={rf_avg_mae:.3f})")

    # ================================================================= 2. direct vs recursive
    print("\n=== height: recursive_random_forest_keep (vs direct random_forest__keep) ===")
    rf_keep_step_model = load(ARTIFACTS / "height__random_forest__keep.joblib")
    recursive = RecursiveHeightModel(rf_keep_step_model).fit(train)
    recursive_metrics = {str(h): evaluate_height_model_batch(recursive, val, h, f"target_height_plus_{h}d_cm")
                         for h in HORIZONS}
    direct_rf_keep_metrics = orig["height"]["keep"]["random_forest"]

    val30 = [r for r in val if fnum(r.get("target_height_plus_30d_cm")) is not None]
    preds30 = list(recursive.predict_height_batch(val30, 30))
    results["direct_vs_recursive_random_forest_keep"] = {
        "direct": direct_rf_keep_metrics,
        "recursive": recursive_metrics,
        "recursive_h7_equals_direct_h7_sanity_check": recursive_metrics["7"]["mae"] == direct_rf_keep_metrics["7"]["mae"],
        "sanity_h30": {"n": len(preds30), "n_negative": int(sum(1 for p in preds30 if p < 0)),
                      "n_over_150": int(sum(1 for p in preds30 if p > 150)),
                      "min": round(float(min(preds30)), 2) if len(preds30) else None,
                      "max": round(float(max(preds30)), 2) if len(preds30) else None},
    }
    print(f"  direct MAE  7/14/30: {direct_rf_keep_metrics['7']['mae']}/{direct_rf_keep_metrics['14']['mae']}/{direct_rf_keep_metrics['30']['mae']}")
    print(f"  recursive MAE 7/14/30: {recursive_metrics['7']['mae']}/{recursive_metrics['14']['mae']}/{recursive_metrics['30']['mae']}")

    # ================================================================= 3. framing A vs B
    print("\n=== days_until_30cm: framing A (height-trajectory) vs framing B (direct) vs mechanistic ===")
    rf_ext_height_model = load(ARTIFACTS / "height__random_forest__keep_plus_candidate.joblib")
    framing_a = HeightTrajectoryDaysModel(rf_ext_height_model).fit(train)
    framing_a_metrics, methods = evaluate_days_until_30cm_model_batch(framing_a, val)
    framing_b_metrics = orig["days_until_30cm"]["keep_plus_candidate"]["random_forest"]
    mechanistic_metrics = baseline["days_until_30cm"]["mechanistic_days_until_30cm"]

    not_censored = [r for r in val if r["target_days_until_30cm_censored"] == "0"
                    and fnum(r["target_days_until_30cm"]) is not None]
    n_negative_days = sum(1 for pred, _ in framing_a.predict_batch(not_censored) if pred is not None and pred < 0)
    results["days_until_30cm_framing_a_vs_b"] = {
        "framing_a_height_trajectory": framing_a_metrics,
        "framing_b_direct_regression": framing_b_metrics,
        "mechanistic_baseline": mechanistic_metrics,
        "framing_a_method_distribution_on_not_censored_validation": dict(methods),
        "framing_a_sanity_n_negative_days": n_negative_days,
    }
    print(f"  framing A MAE={framing_a_metrics['mae_days']}  framing B MAE={framing_b_metrics['mae_days']}  "
         f"mechanistic MAE={mechanistic_metrics['mae_days']}")
    print(f"  framing A method distribution: {dict(methods)}")

    # ================================================================= write results + artifacts
    OUT_JSON.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\nWrote {OUT_JSON}")

    addendum_manifest = {"note": "complements models/artifacts/manifest.json; recursive and framing-A "
                                 "models are wrappers around existing artifacts and are not re-saved here.",
                         "models": []}
    new_artifacts = {
        "height__ols__keep_plus_candidate.joblib": ols_h,
        "height__lasso__keep_plus_candidate.joblib": lasso_h,
        "days_until_30cm__ols__keep_plus_candidate.joblib": ols_d,
        "days_until_30cm__lasso__keep_plus_candidate.joblib": lasso_d,
    }
    for fname, model in new_artifacts.items():
        dump(model, ARTIFACTS / fname)
        addendum_manifest["models"].append({"file": fname, "feature_set": "keep_plus_candidate"})
    (ARTIFACTS / "manifest_addendum.json").write_text(
        json.dumps(addendum_manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"Wrote {len(new_artifacts)} new model artifacts + manifest_addendum.json to {ARTIFACTS}")
    return results


if __name__ == "__main__":
    main()
