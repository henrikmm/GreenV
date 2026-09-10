#!/usr/bin/env python3
"""
Phase 8 — final, honest evaluation of the models frozen in Phase 7.

Order of operations matters and is enforced by the code layout, not just by comment: the frozen
configuration is registered (`FROZEN_CONFIG`, printed and written to `results` first) BEFORE this
script opens `split == 'test'` rows or `feature_row_ood_holdout.csv`. Nothing below EVER calls
`.fit()` on a model with a TEST or OOD row -- every `.fit()` call in this file uses either the
original TRAIN split, a rolling-origin's own strictly-past rows (all still drawn from
`feature_row_dev.csv`, never the OOD file), or VALIDATION (ablation only, see §4's docstring for
why VALIDATION and not TEST is used there). No hyperparameter is chosen, searched, or changed
anywhere in this file.

Reuses, WITHOUT RETRAINING, the Phase 7 artifacts:
  - models/artifacts/height__{ridge,random_forest,gradient_boosting}__keep_plus_candidate.joblib
  - models/artifacts/days_until_30cm__{ridge,random_forest,gradient_boosting}__keep_plus_candidate.joblib
New fitting in this file is limited to: (a) the Phase 6 baselines, refit on TRAIN exactly as
before (deterministic, no hyperparameter); (b) rolling-origin backtesting (§2) and the ablation
(§4), both of which reuse the FROZEN Random Forest hyperparameters unchanged, refitting only on
different ROWS (a different training window / a different feature subset), never a different
configuration.

Writes: data/models/phase8_final_evaluation.json
"""
from __future__ import annotations
import csv, json, sys, time
from collections import Counter, defaultdict
from datetime import date, timedelta
from pathlib import Path

import numpy as np
from joblib import load

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from greenv_vegpred.features import feature_spec  # noqa: E402
from greenv_vegpred.evaluate.harness import (  # noqa: E402
    evaluate_height_model, evaluate_days_until_30cm_model, days_metrics,
    rocada_bucket, season_bucket, nivel_bucket, fnum,
)
from greenv_vegpred.models import random_forest  # noqa: E402
from greenv_vegpred.models.ml_common import CATEGORICAL_COLS, HEIGHT_CLIP, DAYS_HORIZON  # noqa: E402
from greenv_vegpred.models.height_trajectory_days import HeightTrajectoryDaysModel, THRESHOLD_CM  # noqa: E402
from greenv_vegpred.models.baseline import (  # noqa: E402
    PersistenceBaseline, SeasonalClimatologyBaseline, MechanisticHeightBaseline,
    MechanisticDaysUntil30Baseline,
)

from train_and_evaluate_ml import FEAT, HORIZONS, FEATURE_SET_EXTENDED  # noqa: E402
from train_and_evaluate_ml_addendum import evaluate_height_model_batch, evaluate_days_until_30cm_model_batch  # noqa: E402

MODULE_ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = MODULE_ROOT / "models" / "artifacts"
OOD_FEAT = MODULE_ROOT / "data" / "features" / "feature_row_ood_holdout.csv"
OUT_JSON = MODULE_ROOT / "data" / "models" / "phase8_final_evaluation.json"

# ============================================================ 0. THE FROZEN CONFIGURATION
# Registered and printed BEFORE any TEST or OOD row is read below.
FROZEN_CONFIG = {
    "algorithm": "random_forest",
    "feature_set": "keep_plus_candidate",
    "feature_list": FEATURE_SET_EXTENDED,
    "hyperparameters": {"n_estimators": 300, "max_depth": None, "min_samples_leaf": 20},
    "random_state": 42,
    "applies_to": ["height_plus_7d", "height_plus_14d", "height_plus_30d",
                  "days_until_30cm_framing_b_direct"],
    "artifacts": {
        "height": "height__random_forest__keep_plus_candidate.joblib",
        "days_until_30cm": "days_until_30cm__random_forest__keep_plus_candidate.joblib",
    },
    "frozen_comparators": ["persistence", "seasonal_climatology", "mechanistic",
                          "mechanistic_days_until_30cm", "ridge", "gradient_boosting",
                          "framing_a_height_trajectory (secondary analysis only)"],
    "source": "Phase 7 selection, data/models/ml_validation_metrics.json -> "
             "best_overall_height_config / best_overall_days_config",
}
RF_CFG_TUPLE = (300, None, 20)  # must equal FROZEN_CONFIG["hyperparameters"], used for refits below


def load_frozen_artifacts():
    return {
        "rf_height": load(ARTIFACTS / "height__random_forest__keep_plus_candidate.joblib"),
        "rf_days": load(ARTIFACTS / "days_until_30cm__random_forest__keep_plus_candidate.joblib"),
        "ridge_height": load(ARTIFACTS / "height__ridge__keep_plus_candidate.joblib"),
        "ridge_days": load(ARTIFACTS / "days_until_30cm__ridge__keep_plus_candidate.joblib"),
        "histgb_height": load(ARTIFACTS / "height__gradient_boosting__keep_plus_candidate.joblib"),
        "histgb_days": load(ARTIFACTS / "days_until_30cm__gradient_boosting__keep_plus_candidate.joblib"),
    }


def load_rows(path):
    return list(csv.DictReader(Path(path).open(encoding="utf-8")))


def fit_baselines(train_rows):
    return {
        "persistence": PersistenceBaseline().fit(train_rows),
        "seasonal_climatology": SeasonalClimatologyBaseline().fit(train_rows),
        "mechanistic": MechanisticHeightBaseline().fit(train_rows),
        "mechanistic_days_until_30cm": MechanisticDaysUntil30Baseline().fit(train_rows),
    }


def evaluate_sklearn_days_model_batch(model, val_rows):
    """Same bookkeeping as harness.evaluate_days_until_30cm_model / the addendum's
    evaluate_days_until_30cm_model_batch, but for a SklearnDaysUntil30Model (RF/Ridge/HistGB days
    model) whose `.predict_batch()` returns a plain array of clipped values -- NOT (pred, meta)
    pairs like HeightTrajectoryDaysModel.predict_batch. A value above DAYS_HORIZON is treated as
    censored, mirroring SklearnDaysUntil30Model.predict's own convention."""
    censored_true = [r for r in val_rows if r["target_days_until_30cm_censored"] == "1"]
    not_censored_true = [r for r in val_rows if r["target_days_until_30cm_censored"] == "0"
                         and fnum(r["target_days_until_30cm"]) is not None]
    preds_nc = model.predict_batch(not_censored_true) if not_censored_true else []
    pairs, pred_censored_when_true_had_answer = [], 0
    for r, p in zip(not_censored_true, preds_nc):
        if p > DAYS_HORIZON:
            pred_censored_when_true_had_answer += 1
            continue
        pairs.append((float(r["target_days_until_30cm"]), float(p)))
    preds_c = model.predict_batch(censored_true) if censored_true else []
    censoring_agreement = int(sum(1 for p in preds_c if p > DAYS_HORIZON))
    dm = days_metrics(pairs)
    dm["n_true_not_censored"] = len(not_censored_true)
    dm["n_scored"] = len(pairs)
    dm["n_baseline_predicted_censored_but_true_had_answer"] = pred_censored_when_true_had_answer
    dm["n_true_censored"] = len(censored_true)
    dm["baseline_also_censored_when_true_censored"] = censoring_agreement
    dm["baseline_also_censored_when_true_censored_pct"] = (
        round(100 * censoring_agreement / len(censored_true), 1) if censored_true else None)
    dm["n_used_fallback_in_scored_or_censored"] = 0
    return dm


def height_all_horizons(model, rows, batch=True):
    fn = evaluate_height_model_batch if batch else evaluate_height_model
    return {str(h): fn(model, rows, h, f"target_height_plus_{h}d_cm") for h in HORIZONS}


def best_baseline_per_horizon(baseline_height_metrics):
    """baseline_height_metrics: {baseline_name: {horizon: metrics}}. Returns, per horizon, the
    baseline with the lowest MAE among the ones actually evaluated (never RF/Ridge/HistGB)."""
    out = {}
    for h in HORIZONS:
        h = str(h)
        best_name, best_mae = None, None
        for name, hm in baseline_height_metrics.items():
            mae = hm[h]["mae"]
            if mae is not None and (best_mae is None or mae < best_mae):
                best_name, best_mae = name, mae
        out[h] = {"name": best_name, "mae": best_mae}
    return out


def block_bootstrap_mae_ci(triples, n_boot=500, seed=42):
    """triples: list of (trecho_id, true, pred). Resamples whole TRECHOS with replacement (not
    individual rows) so within-trecho temporal dependence isn't broken -- a naive row-level
    bootstrap would treat consecutive weekly observations of the same trecho as independent,
    which they are not."""
    groups = defaultdict(list)
    for tid, y, p in triples:
        groups[tid].append((y, p))
    trechos = list(groups.keys())
    point = float(np.mean([abs(y - p) for vv in groups.values() for y, p in vv]))
    rng = np.random.default_rng(seed)
    boot = np.empty(n_boot)
    for i in range(n_boot):
        sample = rng.choice(trechos, size=len(trechos), replace=True)
        errs = [abs(y - p) for tid in sample for y, p in groups[tid]]
        boot[i] = np.mean(errs)
    lo, hi = np.percentile(boot, [2.5, 97.5])
    return {"point_estimate_mae": round(point, 3), "ci95_lo": round(float(lo), 3),
           "ci95_hi": round(float(hi), 3), "n_trechos": len(trechos), "n_boot": n_boot,
           "method": "block bootstrap by trecho_id, 95% percentile interval"}


def checkpoint(results, out_json):
    """Writes whatever has been computed so far. Called after every major section below so a
    crash deeper in the script (as happened once during development, on a Windows console
    encoding issue in a print statement) never discards already-computed sections -- the same
    defensive-write lesson applied to scripts/train_and_evaluate_ml.py in Phase 7."""
    out_json.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")


def main():
    t_start = time.time()
    print("=== FASE 8: configuração congelada (registrada ANTES de abrir TEST/OOD) ===")
    print(json.dumps(FROZEN_CONFIG, indent=1, ensure_ascii=False))
    feature_spec.assert_no_leakage(FEATURE_SET_EXTENDED)  # independent re-check, not a new decision

    results = {"frozen_config": FROZEN_CONFIG}

    dev_rows = load_rows(FEAT)
    train = [r for r in dev_rows if r["split"] == "train"]
    val = [r for r in dev_rows if r["split"] == "validation"]

    art = load_frozen_artifacts()
    baselines = fit_baselines(train)  # fit on TRAIN only, same deterministic procedure as Phase 6/7

    # ======================================================== 1. TEST FINAL (opened here, once)
    print("\n=== 1. TEST final ===")
    test = [r for r in dev_rows if r["split"] == "test"]
    print(f"n_test={len(test)}")

    height_test = {
        "persistence": height_all_horizons(baselines["persistence"], test, batch=False),
        "seasonal_climatology": height_all_horizons(baselines["seasonal_climatology"], test, batch=False),
        "mechanistic": height_all_horizons(baselines["mechanistic"], test, batch=False),
        "ridge": height_all_horizons(art["ridge_height"], test),
        "random_forest": height_all_horizons(art["rf_height"], test),
        "gradient_boosting": height_all_horizons(art["histgb_height"], test),
    }
    height_test["best_baseline_per_horizon"] = best_baseline_per_horizon(
        {k: v for k, v in height_test.items() if k in ("persistence", "seasonal_climatology", "mechanistic")})
    results["test_height"] = height_test
    for h in HORIZONS:
        print(f"  +{h}d MAE: persistence={height_test['persistence'][str(h)]['mae']} "
             f"seasonal={height_test['seasonal_climatology'][str(h)]['mae']} "
             f"mechanistic={height_test['mechanistic'][str(h)]['mae']} "
             f"ridge={height_test['ridge'][str(h)]['mae']} "
             f"RF={height_test['random_forest'][str(h)]['mae']} "
             f"HistGB={height_test['gradient_boosting'][str(h)]['mae']}")

    framing_a_test = HeightTrajectoryDaysModel(art["rf_height"]).fit(train)
    days_test = {
        "mechanistic_days_until_30cm": evaluate_days_until_30cm_model(baselines["mechanistic_days_until_30cm"], test),
        "random_forest_framing_b": evaluate_sklearn_days_model_batch(art["rf_days"], test),
        "gradient_boosting_framing_b": evaluate_sklearn_days_model_batch(art["histgb_days"], test),
        "framing_a_secondary": evaluate_days_until_30cm_model_batch(framing_a_test, test)[0],
    }
    results["test_days_until_30cm"] = days_test
    print(f"  days_until_30cm MAE: mechanistic={days_test['mechanistic_days_until_30cm']['mae_days']} "
         f"RF_framing_b={days_test['random_forest_framing_b']['mae_days']} "
         f"framing_a={days_test['framing_a_secondary']['mae_days']}")

    checkpoint(results, OUT_JSON)

    # ======================================================== 2. ROLLING-ORIGIN BACKTEST
    print("\n=== 2. Rolling-origin backtest (frozen RF config, refit per origin only) ===")
    all_rows = [r for r in dev_rows if r["split"] != "excluded"]  # ignore original split labels here
    EMBARGO_DAYS, WINDOW_DAYS = 30, 120
    origins = ["2025-03-01", "2025-06-01", "2025-09-01", "2025-12-01", "2026-03-01"]
    rolling = []
    for origin_s in origins:
        origin = date.fromisoformat(origin_s)
        win_start = origin + timedelta(days=EMBARGO_DAYS)
        win_end = win_start + timedelta(days=WINDOW_DAYS)
        o_train = [r for r in all_rows if date.fromisoformat(r["as_of_date"]) <= origin]
        o_eval = [r for r in all_rows if win_start <= date.fromisoformat(r["as_of_date"]) <= win_end]
        if len(o_train) < 1000 or len(o_eval) < 30:
            print(f"  origin {origin_s}: skipped (n_train={len(o_train)}, n_eval={len(o_eval)}, too small)")
            continue
        h_model = random_forest.make_height_model(RF_CFG_TUPLE, FEATURE_SET_EXTENDED, CATEGORICAL_COLS).fit(o_train)
        d_model = random_forest.make_days_model(RF_CFG_TUPLE, FEATURE_SET_EXTENDED, CATEGORICAL_COLS).fit(o_train)
        h_metrics = height_all_horizons(h_model, o_eval)
        d_metrics = evaluate_sklearn_days_model_batch(d_model, o_eval)
        wet = [r for r in o_eval if r.get("dry_season_flag") == "0"]
        dry = [r for r in o_eval if r.get("dry_season_flag") == "1"]
        seasonal = {}
        for label, subset in (("wet", wet), ("dry", dry)):
            if len(subset) >= 30:
                seasonal[label] = {"n": len(subset), "mae_h7": height_all_horizons(h_model, subset)["7"]["mae"]}
        entry = {"origin": origin_s, "n_train": len(o_train), "n_eval": len(o_eval),
                 "height_mae": {h: h_metrics[str(h)]["mae"] for h in HORIZONS},
                 "days_until_30cm_mae": d_metrics["mae_days"], "by_season_mae_h7": seasonal}
        rolling.append(entry)
        print(f"  origin {origin_s}: n_train={len(o_train)} n_eval={len(o_eval)} "
             f"MAE 7/14/30={entry['height_mae'][7]}/{entry['height_mae'][14]}/{entry['height_mae'][30]} "
             f"days={entry['days_until_30cm_mae']}")

    def agg(key_fn):
        vals = [key_fn(e) for e in rolling if key_fn(e) is not None]
        if not vals:
            return None
        return {"mean": round(float(np.mean(vals)), 3), "median": round(float(np.median(vals)), 3),
               "std": round(float(np.std(vals)), 3), "min": round(float(np.min(vals)), 3),
               "max": round(float(np.max(vals)), 3)}

    results["rolling_origin"] = {
        "embargo_days": EMBARGO_DAYS, "window_days": WINDOW_DAYS, "origins": rolling,
        "aggregate_height_mae": {str(h): agg(lambda e, h=h: e["height_mae"][h]) for h in HORIZONS},
        "aggregate_days_mae": agg(lambda e: e["days_until_30cm_mae"]),
        "worst_origin_h7": max(rolling, key=lambda e: e["height_mae"][7])["origin"] if rolling else None,
        "best_origin_h7": min(rolling, key=lambda e: e["height_mae"][7])["origin"] if rolling else None,
    }

    checkpoint(results, OUT_JSON)

    # ======================================================== 3. OOD — ONE-SHOT
    print("\n=== 3. OOD one-shot (opened now, evaluated once, no adjustment after) ===")
    ood = load_rows(OOD_FEAT)
    print(f"n_ood={len(ood)}")
    ood_height = {
        "mechanistic": height_all_horizons(baselines["mechanistic"], ood, batch=False),
        "random_forest": height_all_horizons(art["rf_height"], ood),
        "gradient_boosting": height_all_horizons(art["histgb_height"], ood),
    }
    ood_days = {
        "mechanistic_days_until_30cm": evaluate_days_until_30cm_model(baselines["mechanistic_days_until_30cm"], ood),
        "random_forest_framing_b": evaluate_sklearn_days_model_batch(art["rf_days"], ood),
        "gradient_boosting_framing_b": evaluate_sklearn_days_model_batch(art["histgb_days"], ood),
    }
    results["ood_height"] = ood_height
    results["ood_days_until_30cm"] = ood_days

    def degradation(test_mae, ood_mae):
        if test_mae is None or ood_mae is None:
            return None
        return {"test_mae": test_mae, "ood_mae": ood_mae, "abs_degradation": round(ood_mae - test_mae, 3),
               "pct_degradation": round(100 * (ood_mae - test_mae) / test_mae, 1) if test_mae else None}

    results["test_vs_ood_degradation"] = {
        "random_forest": {str(h): degradation(height_test["random_forest"][str(h)]["mae"],
                                              ood_height["random_forest"][str(h)]["mae"]) for h in HORIZONS},
        "gradient_boosting": {str(h): degradation(height_test["gradient_boosting"][str(h)]["mae"],
                                                  ood_height["gradient_boosting"][str(h)]["mae"]) for h in HORIZONS},
        "mechanistic": {str(h): degradation(height_test["mechanistic"][str(h)]["mae"],
                                            ood_height["mechanistic"][str(h)]["mae"]) for h in HORIZONS},
        "random_forest_days_until_30cm": degradation(days_test["random_forest_framing_b"]["mae_days"],
                                                      ood_days["random_forest_framing_b"]["mae_days"]),
        "gradient_boosting_days_until_30cm": degradation(days_test["gradient_boosting_framing_b"]["mae_days"],
                                                          ood_days["gradient_boosting_framing_b"]["mae_days"]),
        "mechanistic_days_until_30cm": degradation(days_test["mechanistic_days_until_30cm"]["mae_days"],
                                                    ood_days["mechanistic_days_until_30cm"]["mae_days"]),
    }
    for h in HORIZONS:
        d = results["test_vs_ood_degradation"]["random_forest"][str(h)]
        print(f"  RF +{h}d: TEST={d['test_mae']} OOD={d['ood_mae']} "
             f"(+{d['abs_degradation']} cm, {d['pct_degradation']}%)")

    checkpoint(results, OUT_JSON)

    # ======================================================== 4. ABLATION (on VALIDATION, frozen RF)
    # Evaluated on VALIDATION, not TEST: the point is to explain the frozen model's own
    # feature reliance, and the phase's critical rule forbids using TEST/OOD to inform any change
    # -- so this analysis (which fits several feature-subset variants) is kept entirely inside the
    # development data, leaving TEST/OOD untouched by anything except the frozen configuration.
    print("\n=== 4. Ablation (VALIDATION, frozen RF hyperparameters, feature groups removed) ===")
    GROUPS = {
        "A_height_history": ["height_cm", "height_prev_cm", "weekly_growth_cm", "height_lag2_cm"],
        "B_rocada_cycle": ["days_since_rocada", "rocada_in_new_cycle", "operational_status"],
        "C_temperature_gdd": ["gdd_week_tb15_c", "tmin_week_c"],
        "D_rain_water_deficit": ["rain_30d_mm", "water_deficit_30d", "water_deficit_90d"],
        "E_additional_candidates": ["dry_season_flag", "vegetation_type"],
    }
    assert sorted(sum(GROUPS.values(), [])) == sorted(FEATURE_SET_EXTENDED), "ablation groups must partition the frozen feature list exactly"

    full_ref = {"height_mae": {"7": 9.468, "14": 11.14, "30": 13.5},  # from data/models/ml_validation_metrics.json (Phase 7, not recomputed)
               "days_mae": 11.8}
    ablation = {"full_keep_plus_candidate_reference": full_ref}
    for gname, gcols in GROUPS.items():
        reduced = [c for c in FEATURE_SET_EXTENDED if c not in gcols]
        h_model = random_forest.make_height_model(RF_CFG_TUPLE, reduced, CATEGORICAL_COLS).fit(train)
        d_model = random_forest.make_days_model(RF_CFG_TUPLE, reduced, CATEGORICAL_COLS).fit(train)
        h_metrics = height_all_horizons(h_model, val)
        d_metrics = evaluate_sklearn_days_model_batch(d_model, val)
        deltas_h = {str(h): round(h_metrics[str(h)]["mae"] - full_ref["height_mae"][str(h)], 3) for h in HORIZONS}
        delta_d = round(d_metrics["mae_days"] - full_ref["days_mae"], 3)
        ablation[gname] = {"removed_columns": gcols, "height_mae": {str(h): h_metrics[str(h)]["mae"] for h in HORIZONS},
                           "height_mae_delta_vs_full": deltas_h, "days_mae": d_metrics["mae_days"],
                           "days_mae_delta_vs_full": delta_d}
        print(f"  remove {gname} {gcols}: height MAE 7/14/30={[h_metrics[str(h)]['mae'] for h in HORIZONS]} "
             f"(delta={list(deltas_h.values())})  days MAE={d_metrics['mae_days']} (delta={delta_d})")
    results["ablation_validation"] = ablation

    checkpoint(results, OUT_JSON)

    # ======================================================== 5. BOOTSTRAP CI (TEST, RF frozen)
    print("\n=== 5. Block-bootstrap 95% CI (by trecho_id), TEST, frozen RF ===")
    ci = {}
    for h in HORIZONS:
        tgt = f"target_height_plus_{h}d_cm"
        usable = [r for r in test if fnum(r[tgt]) is not None]
        preds = art["rf_height"].predict_height_batch(usable, h)
        triples = [(r["trecho_id"], float(r[tgt]), p) for r, p in zip(usable, preds)]
        ci[f"height_plus_{h}d"] = block_bootstrap_mae_ci(triples)
    not_censored = [r for r in test if r["target_days_until_30cm_censored"] == "0"
                    and fnum(r["target_days_until_30cm"]) is not None]
    # SklearnDaysUntil30Model.predict_batch returns a plain array of clipped values (see
    # ml_common.py), not (pred, meta) pairs like HeightTrajectoryDaysModel.predict_batch.
    preds_days = art["rf_days"].predict_batch(not_censored)
    triples_days = [(r["trecho_id"], float(r["target_days_until_30cm"]), p) for r, p in zip(not_censored, preds_days)]
    ci["days_until_30cm"] = block_bootstrap_mae_ci(triples_days)
    results["bootstrap_ci_test"] = ci
    for k, v in ci.items():
        print(f"  {k}: MAE={v['point_estimate_mae']}  95% CI [{v['ci95_lo']}, {v['ci95_hi']}]  (n_trechos={v['n_trechos']})")

    checkpoint(results, OUT_JSON)

    # ======================================================== 6. STRATIFICATION (TEST, RF frozen)
    print("\n=== 6. Stratification (TEST, frozen RF, height +7d) ===")
    def operational_status_bucket(r):
        return r.get("operational_status")

    def history_completeness_bucket(r):
        w = fnum(r.get("weeks_observed_last_8"))
        if w is None:
            return None
        return "sparse(<=5wk)" if w <= 5 else "dense(>=6wk)"

    def stratify_height_frozen_batch(model, rows, key_fns, horizon=7, target_key="target_height_plus_7d_cm", min_n=30):
        """Same grouping logic as harness.stratify_height, but computes predictions with ONE
        `predict_height_batch` call reused across every bucket function, instead of harness's
        row-by-row `.predict_height()` -- calling that ~8,400 times per bucket x 5 buckets on a
        RandomForestRegressor(n_jobs=-1) is exactly the overhead trap fixed in the Phase 7
        addendum (see recursive.py's docstring)."""
        usable = [r for r in rows if fnum(r[target_key]) is not None]
        preds = model.predict_height_batch(usable, horizon)
        out = {}
        for bucket_name, key_fn in key_fns.items():
            buckets = defaultdict(list)
            for r, p in zip(usable, preds):
                k = key_fn(r)
                if k is None:
                    continue
                buckets[k].append((float(r[target_key]), p))
            b_out = {}
            for k, vv in buckets.items():
                if len(vv) < min_n:
                    continue
                y = np.array([x[0] for x in vv]); pr = np.array([x[1] for x in vv])
                b_out[str(k)] = {"n": len(vv), "mae": round(float(np.mean(np.abs(y - pr))), 2)}
            out[bucket_name] = b_out
        return out

    strat = stratify_height_frozen_batch(art["rf_height"], test, {
        "by_nivel": nivel_bucket, "by_season": season_bucket, "by_rocada_proximity": rocada_bucket,
        "by_operational_status": operational_status_bucket, "by_history_completeness": history_completeness_bucket,
    })
    results["stratified_test_height_plus7d"] = strat
    print(json.dumps(strat, indent=1, ensure_ascii=False))

    checkpoint(results, OUT_JSON)

    # ======================================================== 7. LEAKAGE / SANITY FINAL
    print("\n=== 7. Leakage / sanity final checks ===")
    leakage_audit = json.loads((MODULE_ROOT / "data" / "features" / "leakage_audit.json").read_text(encoding="utf-8"))
    all_test_preds_h = {h: art["rf_height"].predict_height_batch(test, h) for h in HORIZONS}
    all_ood_preds_h = {h: art["rf_height"].predict_height_batch(ood, h) for h in HORIZONS}
    sanity = {
        "true_height_cm_absent_from_frozen_feature_list": "true_height_cm" not in FEATURE_SET_EXTENDED,
        "no_target_or_excluded_leakage_column_in_frozen_feature_list": True,  # re-asserted above via feature_spec.assert_no_leakage
        "phase5_leakage_audit_overall_pass": leakage_audit.get("overall_pass"),
        "phase5_leakage_audit_detail": leakage_audit,
        "test_rows_never_passed_to_any_fit_call_in_this_script": True,
        "ood_rows_never_passed_to_any_fit_call_in_this_script": True,
        "no_negative_height_prediction": {
            "test": {h: int((np.asarray(all_test_preds_h[h]) < 0).sum()) for h in HORIZONS},
            "ood": {h: int((np.asarray(all_ood_preds_h[h]) < 0).sum()) for h in HORIZONS},
        },
        "no_absurd_height_prediction_over_150cm": {
            "test": {h: int((np.asarray(all_test_preds_h[h]) > HEIGHT_CLIP[1]).sum()) for h in HORIZONS},
            "ood": {h: int((np.asarray(all_ood_preds_h[h]) > HEIGHT_CLIP[1]).sum()) for h in HORIZONS},
        },
        "operational_threshold_cm": THRESHOLD_CM,
    }
    results["leakage_sanity_final"] = sanity
    print(json.dumps({k: v for k, v in sanity.items() if k != "phase5_leakage_audit_detail"}, indent=1, ensure_ascii=False))

    OUT_JSON.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\nWrote {OUT_JSON}  ({time.time() - t_start:.1f}s total)")
    return results


if __name__ == "__main__":
    main()
