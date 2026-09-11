#!/usr/bin/env python3
"""
Phase 6 — fit baselines on train, evaluate on validation. Never reads test or OOD.

Reads:  data/features/feature_row_dev.csv  (split in {train, validation, test, excluded})
Writes: reports/model-comparison.md (hand-authored; not overwritten by this script)
        data/models/baseline_validation_metrics.json

Height baselines: persistence, last_growth, seasonal_climatology, mechanistic.
days_until_30cm baselines: naive_days_until_30cm, mechanistic_days_until_30cm.

This is the SAME evaluation as before the Phase 6 harness refactor, now calling into
greenv_vegpred.evaluate.harness instead of computing metrics inline -- the numbers for
persistence / last_growth / seasonal_climatology / naive_days_until_30cm are unchanged (verified
in reports/model-comparison.md's changelog note); mechanistic / mechanistic_days_until_30cm are
new.
"""
from __future__ import annotations
import json, sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from greenv_vegpred.models.baseline import (  # noqa: E402
    PersistenceBaseline, LastGrowthBaseline, SeasonalClimatologyBaseline, MechanisticHeightBaseline,
    NaiveDaysUntil30Baseline, MechanisticDaysUntil30Baseline, fnum,
)
from greenv_vegpred.evaluate.harness import (  # noqa: E402
    evaluate_height_model, evaluate_days_until_30cm_model,
    stratify_height, stratify_days, rocada_bucket, season_bucket, nivel_bucket,
)

MODULE_ROOT = Path(__file__).resolve().parents[1]
FEAT = MODULE_ROOT / "data" / "features" / "feature_row_dev.csv"
OUT_JSON = MODULE_ROOT / "data" / "models" / "baseline_validation_metrics.json"
HORIZONS = (7, 14, 30)

HEIGHT_MODEL_CLASSES = [PersistenceBaseline, LastGrowthBaseline, SeasonalClimatologyBaseline, MechanisticHeightBaseline]
DAYS_MODEL_CLASSES = [NaiveDaysUntil30Baseline, MechanisticDaysUntil30Baseline]


def main():
    import csv
    rows = list(csv.DictReader(FEAT.open(encoding="utf-8")))
    train = [r for r in rows if r["split"] == "train"]
    val = [r for r in rows if r["split"] == "validation"]
    assert not any(r["split"] == "test" for r in train + val), "test rows leaked into fit/eval sets"
    print(f"train={len(train)}  validation={len(val)}  (test and OOD not loaded by this script)")

    height_models = {}
    for cls in HEIGHT_MODEL_CLASSES:
        m = cls().fit(train)
        height_models[m.name] = m
    days_models = {}
    for cls in DAYS_MODEL_CLASSES:
        m = cls().fit(train)
        days_models[m.name] = m

    results = {"n_train": len(train), "n_validation": len(val), "height": {}, "days_until_30cm": {}}

    for h in HORIZONS:
        results["height"][h] = {}
        tgt_key = f"target_height_plus_{h}d_cm"
        for name, model in height_models.items():
            results["height"][h][name] = evaluate_height_model(model, val, h, tgt_key)

    for name, model in days_models.items():
        results["days_until_30cm"][name] = evaluate_days_until_30cm_model(model, val)
        if name == "mechanistic_days_until_30cm":
            results["days_until_30cm"][name]["fit_stats"] = model._height_model.fit_stats
            results["days_until_30cm"][name]["train_avg_daily_gdd_tb15"] = round(model._height_model.train_avg_daily_gdd, 3)
    results["days_until_30cm"]["naive_days_until_30cm"]["fallback_rate_cm_per_week"] = round(
        days_models["naive_days_until_30cm"].fallback_rate_cm_per_week, 3)
    results["days_until_30cm"]["naive_days_until_30cm"]["fallback_rate_fit_n"] = days_models["naive_days_until_30cm"].fallback_rate_n
    results["days_until_30cm"]["mechanistic_days_until_30cm"]["mechanistic_fit_stats"] = height_models["mechanistic"].fit_stats

    # hard cases (unchanged definitions)
    neg_growth_noise = sum(1 for r in val if fnum(r["weekly_growth_cm"]) is not None and fnum(r["weekly_growth_cm"]) < 0)
    not_ready_anchor = sum(1 for r in val if r["operational_status"] == "not-ready")
    new_cycle = sum(1 for r in val if r["rocada_in_new_cycle"] == "1")
    results["hard_cases"] = {
        "n_validation_rows": len(val),
        "negative_observed_weekly_growth_clipped_to_zero": neg_growth_noise,
        "not_ready_anchor_rows": not_ready_anchor,
        "new_cycle_rows_no_local_rate": new_cycle,
        "target_missing_pct": {
            f"plus_{h}d": round(100 * results["height"][h]["persistence"]["n_target_missing_excluded"] / len(val), 2)
            for h in HORIZONS
        },
        "days_until_30cm_censored_any_pct": round(
            100 * results["days_until_30cm"]["naive_days_until_30cm"]["n_censored_any"] / len(val), 2),
    }

    # stratifications: +7d height across ALL height models; days_until_30cm for both days models
    results["stratified"] = {
        "height_plus_7d": {
            "by_current_nivel": stratify_height(height_models, val, nivel_bucket, 7, "target_height_plus_7d_cm"),
            "by_season": stratify_height(height_models, val, season_bucket, 7, "target_height_plus_7d_cm"),
            "by_rocada_proximity": stratify_height(height_models, val, rocada_bucket, 7, "target_height_plus_7d_cm"),
        },
        "days_until_30cm": {
            name: {
                "by_current_nivel": stratify_days(model, val, nivel_bucket),
                "by_season": stratify_days(model, val, season_bucket),
                "by_rocada_proximity": stratify_days(model, val, rocada_bucket),
            }
            for name, model in days_models.items()
        },
    }

    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")

    print(json.dumps({"height": results["height"], "days_until_30cm": {
        k: {kk: vv for kk, vv in v.items() if kk not in ("fit_stats", "mechanistic_fit_stats")}
        for k, v in results["days_until_30cm"].items()
    }, "hard_cases": results["hard_cases"]}, indent=1))
    return results


if __name__ == "__main__":
    main()
