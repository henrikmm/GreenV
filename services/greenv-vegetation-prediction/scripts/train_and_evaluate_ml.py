#!/usr/bin/env python3
"""
Phase 7 — train, hyperparameter-select and evaluate the ML candidates.

Reads:  data/features/feature_row_dev.csv  (split in {train, validation, test, excluded})
Writes: reports/hyperparameters.md
        data/models/ml_validation_metrics.json
        models/artifacts/*.joblib + models/artifacts/manifest.json

TRAIN only for fitting and preprocessing. VALIDATION only for hyperparameter selection and model
comparison. TEST and OOD are never read by this script (grep-checked: no reference to
feature_row_ood_holdout.csv or split=='test' anywhere below).
"""
from __future__ import annotations
import csv, json, sys, time
from collections import Counter
from pathlib import Path

import numpy as np
from joblib import dump
from sklearn.inspection import permutation_importance

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from greenv_vegpred.features import feature_spec  # noqa: E402
from greenv_vegpred.evaluate.harness import (  # noqa: E402
    evaluate_height_model, evaluate_days_until_30cm_model,
    stratify_height, stratify_days, rocada_bucket, season_bucket, nivel_bucket, fnum,
)
from greenv_vegpred.models import linear, random_forest, gradient_boosting  # noqa: E402
from greenv_vegpred.models.ml_common import CATEGORICAL_COLS  # noqa: E402
from greenv_vegpred.models.baseline import (  # noqa: E402
    PersistenceBaseline, SeasonalClimatologyBaseline, MechanisticHeightBaseline,
    MechanisticDaysUntil30Baseline,
)

MODULE_ROOT = Path(__file__).resolve().parents[1]
FEAT = MODULE_ROOT / "data" / "features" / "feature_row_dev.csv"
ARTIFACTS = MODULE_ROOT / "models" / "artifacts"
OUT_MD = MODULE_ROOT / "reports" / "hyperparameters.md"
OUT_JSON = MODULE_ROOT / "data" / "models" / "ml_validation_metrics.json"
HORIZONS = (7, 14, 30)
FEATURE_SPEC_VERSION = "weather-v1+features-v1"

# ----------------------------------------------------------------- feature sets (frozen, Phase 5)
FEATURE_SET_KEEP = list(feature_spec.KEEP)
# A small, individually-justified CANDIDATE extension (reports/hyperparameters.md documents each):
#   height_lag2_cm        -- second-order history, natural complement to height_prev_cm
#   rocada_in_new_cycle    -- explains WHY height_prev_cm/weekly_growth_cm are imputed on this row
#   dry_season_flag        -- coarse season indicator, not collinear with GDD in the same way
#                            tmean/tmax/tb10/tb17 would be with tmin+gdd already in KEEP
#   water_deficit_90d       -- the seasonal-window complement to water_deficit_30d (already KEEP)
#   vegetation_type         -- the one hypothesis-flagged categorical the instructions asked to check
CANDIDATE_EXTENSION = ["height_lag2_cm", "rocada_in_new_cycle", "dry_season_flag",
                       "water_deficit_90d", "vegetation_type"]
FEATURE_SET_EXTENDED = FEATURE_SET_KEEP + CANDIDATE_EXTENSION
feature_spec.assert_no_leakage(FEATURE_SET_KEEP)
feature_spec.assert_no_leakage(FEATURE_SET_EXTENDED)

FEATURE_SETS = {"keep": FEATURE_SET_KEEP, "keep_plus_candidate": FEATURE_SET_EXTENDED}


def mae_of(y, p):
    return float(np.mean(np.abs(np.asarray(y, float) - np.asarray(p, float))))


def rmse_of(y, p):
    return float(np.sqrt(np.mean((np.asarray(y, float) - np.asarray(p, float)) ** 2)))


def r2_of(y, p):
    y, p = np.asarray(y, float), np.asarray(p, float)
    ss_res = np.sum((y - p) ** 2)
    ss_tot = np.sum((y - y.mean()) ** 2)
    return float(1 - ss_res / ss_tot) if ss_tot > 0 else float("nan")


# ----------------------------------------------------------------- hyperparameter selection (height)
def select_height_model(make_fn, configs, feature_list, train_rows, val_rows):
    """Small grid; picks the config with the lowest MEAN validation MAE across the three
    horizons (one config used for all three horizon sub-models of that algorithm+feature-set --
    not re-searched per horizon, to keep the search genuinely small and not over-fit the search
    itself to validation)."""
    trials = []
    best = None
    for cfg in configs:
        t0 = time.time()
        model = make_fn(cfg, feature_list, CATEGORICAL_COLS).fit(train_rows)
        maes = {}
        for h in HORIZONS:
            tgt = f"target_height_plus_{h}d_cm"
            rows_h = [r for r in val_rows if fnum(r[tgt]) is not None]
            y = [float(r[tgt]) for r in rows_h]
            p = model.predict_height_batch(rows_h, h)
            maes[h] = round(mae_of(y, p), 3)
        avg = float(np.mean(list(maes.values())))
        trials.append({"config": str(cfg), "val_mae_by_horizon": maes, "avg_val_mae": round(avg, 3),
                       "fit_seconds": round(time.time() - t0, 1)})
        if best is None or avg < best[0]:
            best = (avg, cfg, model)
    return best[2], best[1], trials


def select_days_model(make_fn, configs, feature_list, train_rows, val_rows):
    not_censored = [r for r in val_rows if r["target_days_until_30cm_censored"] == "0"]
    y_true = [float(r["target_days_until_30cm"]) for r in not_censored]
    trials = []
    best = None
    for cfg in configs:
        t0 = time.time()
        model = make_fn(cfg, feature_list, CATEGORICAL_COLS).fit(train_rows)
        p = model.predict_batch(not_censored)
        # score only the not-censored truths, same convention as Phase 6 (predictions >120 still
        # contribute their capped/clipped numeric error here, for a comparable search signal;
        # final reported metrics use the harness's stricter censored-vs-not accounting)
        mae = mae_of(y_true, p)
        trials.append({"config": str(cfg), "val_mae_days": round(mae, 3),
                       "fit_seconds": round(time.time() - t0, 1)})
        if best is None or mae < best[0]:
            best = (mae, cfg, model)
    return best[2], best[1], trials


def train_val_gap(model, train_rows, val_rows, horizon=7, tgt="target_height_plus_7d_cm"):
    tr = [r for r in train_rows if fnum(r[tgt]) is not None]
    va = [r for r in val_rows if fnum(r[tgt]) is not None]
    ytr, ptr = [float(r[tgt]) for r in tr], model.predict_height_batch(tr, horizon)
    yva, pva = [float(r[tgt]) for r in va], model.predict_height_batch(va, horizon)
    return {"train_mae": round(mae_of(ytr, ptr), 3), "val_mae": round(mae_of(yva, pva), 3),
            "gap": round(mae_of(yva, pva) - mae_of(ytr, ptr), 3)}


def sanity_checks(model, val_rows):
    preds7 = model.predict_height_batch(val_rows, 7)
    out = {
        "n": len(preds7),
        "n_negative": int((preds7 < 0).sum()),
        "n_over_150": int((preds7 > 150).sum()),
        "min": round(float(preds7.min()), 2), "max": round(float(preds7.max()), 2),
    }
    return out


def main():
    rows = list(csv.DictReader(FEAT.open(encoding="utf-8")))
    train = [r for r in rows if r["split"] == "train"]
    val = [r for r in rows if r["split"] == "validation"]
    assert not any(r["split"] == "test" for r in train + val), "test rows leaked into fit/eval sets"
    print(f"train={len(train)}  validation={len(val)}  (test and OOD not loaded by this script)")

    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)

    algos = {
        "ridge": (linear.make_height_model, linear.make_days_model, linear.ALPHA_GRID),
        "random_forest": (random_forest.make_height_model, random_forest.make_days_model, random_forest.CONFIG_GRID),
        "gradient_boosting": (gradient_boosting.make_height_model, gradient_boosting.make_days_model,
                              gradient_boosting.CONFIG_GRID),
    }

    results = {"n_train": len(train), "n_validation": len(val), "feature_sets": FEATURE_SETS,
              "height": {}, "days_until_30cm": {}, "selection": {}, "sanity": {}, "train_val_gap": {},
              "importances": {}}

    fitted_height = {}   # (algo, fset) -> model
    fitted_days = {}

    for fset_name, feats in FEATURE_SETS.items():
        for algo_name, (make_h, make_d, grid) in algos.items():
            key = f"{algo_name}__{fset_name}"
            print(f"\n=== height: {key} ===")
            model, cfg, trials = select_height_model(make_h, grid, feats, train, val)
            fitted_height[key] = model
            results["selection"][f"height__{key}"] = {"chosen_config": str(cfg), "trials": trials}
            print(f"  chosen: {cfg}  (trials={len(trials)})")

            hmetrics = {}
            for h in HORIZONS:
                hmetrics[h] = evaluate_height_model(model, val, h, f"target_height_plus_{h}d_cm")
            results["height"].setdefault(fset_name, {})[algo_name] = hmetrics
            results["train_val_gap"][key] = train_val_gap(model, train, val)
            results["sanity"][f"height__{key}"] = sanity_checks(model, val)

            print(f"\n=== days_until_30cm: {key} ===")
            dmodel, dcfg, dtrials = select_days_model(make_d, grid, feats, train, val)
            fitted_days[key] = dmodel
            results["selection"][f"days__{key}"] = {"chosen_config": str(dcfg), "trials": dtrials}
            dmetrics = evaluate_days_until_30cm_model(dmodel, val)
            results["days_until_30cm"].setdefault(fset_name, {})[algo_name] = dmetrics
            print(f"  chosen: {dcfg}  MAE={dmetrics['mae_days']}")

    # ---------------- native importances ----------------
    ridge_ext = fitted_height["ridge__keep_plus_candidate"]
    pipe7, est7, _ = ridge_ext.sub[7]
    coef_names = pipe7.transform([train[0]])[1]
    results["importances"]["ridge_coefficients_plus7d_extended"] = {
        n: round(float(c), 3) for n, c in sorted(zip(coef_names, est7.coef_), key=lambda x: -abs(x[1]))
    }

    rf_ext = fitted_height["random_forest__keep_plus_candidate"]
    pipeR, estR, _ = rf_ext.sub[7]
    namesR = pipeR.transform([train[0]])[1]
    results["importances"]["random_forest_impurity_plus7d_extended"] = {
        n: round(float(c), 4) for n, c in sorted(zip(namesR, estR.feature_importances_), key=lambda x: -x[1])
    }

    # permutation importance (model-agnostic, comparable across all three), on VALIDATION, +7d,
    # extended feature set, for each algorithm's chosen model -- kept to ONE horizon x ONE
    # feature set x each algo, to bound runtime (not a sweep across every model/horizon).
    for algo_name in algos:
        key = f"{algo_name}__keep_plus_candidate"
        model = fitted_height[key]
        pipe, est, _ = model.sub[7]
        rows7 = [r for r in val if fnum(r["target_height_plus_7d_cm"]) is not None]
        X, names = pipe.transform(rows7)
        y = np.array([float(r["target_height_plus_7d_cm"]) for r in rows7])
        pi = permutation_importance(est, X, y, n_repeats=5, random_state=42, scoring="neg_mean_absolute_error")
        results["importances"][f"permutation_{algo_name}_plus7d_extended"] = {
            n: round(float(m), 3) for n, m in sorted(zip(names, pi.importances_mean), key=lambda x: -x[1])
        }

    # ---------------- stratification for the single best overall height config ----------------
    best_key, best_mae = None, None
    for fset_name, algos_d in results["height"].items():
        for algo_name, hmetrics in algos_d.items():
            avg = np.mean([hmetrics[h]["mae"] for h in HORIZONS])
            if best_mae is None or avg < best_mae:
                best_mae, best_key = avg, f"{algo_name}__{fset_name}"
    results["best_overall_height_config"] = {"key": best_key, "avg_val_mae_7_14_30": round(float(best_mae), 3)}
    print(f"\nBest overall height config: {best_key} (avg MAE {best_mae:.3f})")

    baseline_models = {
        "persistence": PersistenceBaseline().fit(train),
        "seasonal_climatology": SeasonalClimatologyBaseline().fit(train),
        "mechanistic": MechanisticHeightBaseline().fit(train),
        best_key: fitted_height[best_key],
    }
    results["stratified_height_plus7d_vs_baselines"] = {
        "by_current_nivel": stratify_height(baseline_models, val, nivel_bucket, 7, "target_height_plus_7d_cm"),
        "by_season": stratify_height(baseline_models, val, season_bucket, 7, "target_height_plus_7d_cm"),
        "by_rocada_proximity": stratify_height(baseline_models, val, rocada_bucket, 7, "target_height_plus_7d_cm"),
    }

    best_days_key, best_days_mae = None, None
    for fset_name, algos_d in results["days_until_30cm"].items():
        for algo_name, dm in algos_d.items():
            if dm["mae_days"] is None:
                continue
            if best_days_mae is None or dm["mae_days"] < best_days_mae:
                best_days_mae, best_days_key = dm["mae_days"], f"{algo_name}__{fset_name}"
    results["best_overall_days_config"] = {"key": best_days_key, "val_mae_days": best_days_mae}
    print(f"Best overall days_until_30cm config: {best_days_key} (MAE {best_days_mae})")

    results["stratified_days_until_30cm_vs_baseline"] = {
        "mechanistic_days_until_30cm": {
            "by_current_nivel": stratify_days(MechanisticDaysUntil30Baseline().fit(train), val, nivel_bucket),
            "by_season": stratify_days(MechanisticDaysUntil30Baseline().fit(train), val, season_bucket),
        },
        best_days_key: {
            "by_current_nivel": stratify_days(fitted_days[best_days_key], val, nivel_bucket),
            "by_season": stratify_days(fitted_days[best_days_key], val, season_bucket),
        },
    }

    # ---------------- write the metrics JSON FIRST ----------------
    # (so a failure saving the (larger, less critical) model artifacts below can never cost the
    # already-computed metrics/selection/importances/stratification results)
    OUT_JSON.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\nWrote {OUT_JSON}")

    # ---------------- save artifacts ----------------
    manifest = {"feature_spec_version": FEATURE_SPEC_VERSION, "n_train": len(train), "n_validation": len(val),
               "feature_sets": FEATURE_SETS, "models": []}
    for key, model in {**fitted_height}.items():
        fname = f"height__{key}.joblib"
        dump(model, ARTIFACTS / fname)
        manifest["models"].append({"kind": "height", "key": key, "file": fname,
                                   "feature_set": key.split("__")[1], "algorithm": key.split("__")[0]})
    for key, model in {**fitted_days}.items():
        fname = f"days_until_30cm__{key}.joblib"
        dump(model, ARTIFACTS / fname)
        manifest["models"].append({"kind": "days_until_30cm", "key": key, "file": fname,
                                   "feature_set": key.split("__")[1], "algorithm": key.split("__")[0]})
    manifest["best_overall_height_config"] = best_key
    manifest["best_overall_days_config"] = best_days_key
    (ARTIFACTS / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")

    print(f"Wrote {len(manifest['models'])} model artifacts to {ARTIFACTS}")
    return results


if __name__ == "__main__":
    main()
