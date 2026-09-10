#!/usr/bin/env python3
"""
Phase 9 (closing the remaining gaps) — a reproducible, demonstrable BATCH forecast + ranking for
the current SP-021 trechos, built entirely from the artifacts already frozen in Phase 7
(model) and Phase 9's first pass (`data/models/interval_calibration.json`). Nothing here refits
the model, re-derives the calibration, or reads TEST/OOD.

"Current" snapshot: for each of the 118 trechos, the row with the LATEST `as_of_date` in
`dataset == 'main'` (seed=42) only -- `main` and `seed2` share the same 118 `trecho_id` values
(two alternate-reality simulated trajectories for the same physical trechos), so picking a single
canonical dataset avoids mixing two different simulated histories under one "current state" label.

Writes two artifacts, both schema-shaped rather than free-form:
  data/forecast/ranking_current.json / .csv    -- the urgency ranking (Phase 9 task item 1)
  data/forecast/prediction_batch.csv           -- V1-equivalent of the `prediction` table already
                                                   defined in schema.sql (Phase 2); see
                                                   reports/forecast-method.md for the column
                                                   mapping and why this is a CSV, not a live INSERT
                                                   (nothing in this project writes to a running DB
                                                   anywhere else either -- Phases 1-9 are all
                                                   CSV/JSON, consistent with that convention).
"""
from __future__ import annotations
import csv, json, sys, uuid
from datetime import datetime, timezone
from pathlib import Path

from joblib import load

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from greenv_vegpred.forecast import build_days_forecast, rank_forecasts  # noqa: E402

MODULE_ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = MODULE_ROOT / "models" / "artifacts"
FEAT = MODULE_ROOT / "data" / "features" / "feature_row_dev.csv"
CALIB_JSON = MODULE_ROOT / "data" / "models" / "interval_calibration.json"
OUT_DIR = MODULE_ROOT / "data" / "forecast"
MODEL_NAME = "random_forest__keep_plus_candidate__framing_b"
PRIMARY_CONFIDENCE = 0.90


def latest_row_per_trecho(rows, dataset="main"):
    latest = {}
    for r in rows:
        if r["dataset"] != dataset:
            continue
        tid = r["trecho_id"]
        if tid not in latest or r["as_of_date"] > latest[tid]["as_of_date"]:
            latest[tid] = r
    return list(latest.values())


def to_prediction_row(fc: dict) -> dict:
    """Maps one forecast object onto schema.sql's `prediction` table columns (Phase 2). Columns
    this V1 pipeline has no source for (source_observation_id -- there is no `height_observation`
    table behind this CSV-based prototype) are left NULL/empty rather than fabricated."""
    interval = fc.get("interval") or {}
    censored = 1 if fc["status"] == "beyond_horizon" else 0
    return {
        "prediction_id": str(uuid.uuid4()),  # UUIDv4, not v7 -- no UUIDv7 stdlib generator; a
                                             # random unique id still satisfies the column's role
                                             # (a unique identifier), documented as a minor,
                                             # harmless deviation from the schema comment.
        "trecho_id": fc["trecho_id"],
        "as_of_date": fc["as_of_date"],
        "model_kind": "random_forest",
        "model_version": "keep_plus_candidate",
        "h_plus_7_cm": "", "h_plus_14_cm": "", "h_plus_30_cm": "",
        "h_plus_7_low_cm": "", "h_plus_7_high_cm": "",
        "h_plus_14_low_cm": "", "h_plus_14_high_cm": "",
        "h_plus_30_low_cm": "", "h_plus_30_high_cm": "",
        "days_to_30cm": fc["days_until_critical"] if fc["days_until_critical"] is not None else "",
        "days_to_30cm_low": interval.get("lower_days", ""),
        "days_to_30cm_high": interval.get("upper_days", ""),
        "days_to_30cm_censored": censored,
        "days_to_30cm_horizon_days": 120,
        "interval_method": "conformal" if interval else "",
        "interval_nominal_coverage": interval.get("confidence", ""),
        "confidence": fc["operational_confidence"],
        "operational_status": fc.get("operational_status") or "",
        "blockers": json.dumps(fc.get("blockers")) if fc.get("blockers") else "[]",
        "source_observation_id": "",
        "input_data_source": "synthetic",
        "dataset_is_synthetic": 1,
        "created_at": datetime.now(timezone.utc).isoformat(),
    }


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    rows = list(csv.DictReader(FEAT.open(encoding="utf-8")))
    current_rows = latest_row_per_trecho(rows, dataset="main")
    print(f"n_trechos={len(current_rows)} (dataset=main, latest as_of_date per trecho_id)")

    days_model = load(ARTIFACTS / "days_until_30cm__random_forest__keep_plus_candidate.joblib")
    calib = json.loads(CALIB_JSON.read_text(encoding="utf-8"))
    q_by_confidence = {float(k): v for k, v in calib["days_until_30cm"]["q_by_confidence"].items()}

    forecasts = [build_days_forecast(r, days_model, q_by_confidence, MODEL_NAME, confidence=PRIMARY_CONFIDENCE)
                for r in current_rows]
    ranked = rank_forecasts(forecasts)

    status_counts = {}
    for fc in ranked:
        status_counts[fc["status"]] = status_counts.get(fc["status"], 0) + 1
    print(f"status distribution: {status_counts}")

    # ---------------- ranking export (Phase 9 task item 1) ----------------
    ranking_json = OUT_DIR / "ranking_current.json"
    ranking_csv = OUT_DIR / "ranking_current.csv"
    ranking_json.write_text(json.dumps(ranked, ensure_ascii=False, indent=1), encoding="utf-8")

    ranking_cols = ["rank", "trecho_id", "as_of_date", "current_height_cm", "operational_status",
                   "days_until_critical", "lower_days", "upper_days", "confidence_level",
                   "operational_confidence", "status", "data_provenance"]
    with ranking_csv.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=ranking_cols)
        w.writeheader()
        for fc in ranked:
            interval = fc.get("interval") or {}
            w.writerow({
                "rank": fc["rank"], "trecho_id": fc["trecho_id"], "as_of_date": fc["as_of_date"],
                "current_height_cm": fc["current_height_cm"], "operational_status": fc.get("operational_status"),
                "days_until_critical": fc["days_until_critical"],
                "lower_days": interval.get("lower_days"), "upper_days": interval.get("upper_days"),
                "confidence_level": interval.get("confidence"),       # statistical: 0.80/0.90
                "operational_confidence": fc["operational_confidence"],  # operational: low/medium/high
                "status": fc["status"], "data_provenance": fc["data_provenance"],
            })
    print(f"Wrote {ranking_json} and {ranking_csv} ({len(ranked)} rows)")

    # ---------------- schema.sql-compatible prediction batch (Phase 9 task item 4) ----------------
    pred_csv = OUT_DIR / "prediction_batch.csv"
    pred_cols = ["prediction_id", "trecho_id", "as_of_date", "model_kind", "model_version",
                "h_plus_7_cm", "h_plus_14_cm", "h_plus_30_cm", "h_plus_7_low_cm", "h_plus_7_high_cm",
                "h_plus_14_low_cm", "h_plus_14_high_cm", "h_plus_30_low_cm", "h_plus_30_high_cm",
                "days_to_30cm", "days_to_30cm_low", "days_to_30cm_high", "days_to_30cm_censored",
                "days_to_30cm_horizon_days", "interval_method", "interval_nominal_coverage",
                "confidence", "operational_status", "blockers", "source_observation_id",
                "input_data_source", "dataset_is_synthetic", "created_at"]
    pred_rows = [to_prediction_row(fc) for fc in forecasts if fc["status"] != "insufficient_data"]
    with pred_csv.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=pred_cols)
        w.writeheader()
        w.writerows(pred_rows)
    print(f"Wrote {pred_csv} ({len(pred_rows)} rows; "
         f"{len(forecasts) - len(pred_rows)} insufficient_data rows excluded -- no source data to predict from)")

    # ---------------- show a few examples for the report ----------------
    print("\nTop 5 by urgency:")
    for fc in ranked[:5]:
        print(f"  #{fc['rank']} {fc['trecho_id']} status={fc['status']} days={fc['days_until_critical']} "
             f"opstatus={fc['operational_status']} opconf={fc['operational_confidence']}")

    not_ready_examples = [fc for fc in ranked if fc.get("operational_status") == "not-ready"][:2]
    print("\nnot-ready examples (from the current batch):")
    for fc in not_ready_examples:
        print(f"  {json.dumps(fc, ensure_ascii=False)}")
    if not not_ready_examples:
        print("  (none in the current 118-trecho snapshot -- see a real not-ready row pulled from "
             "VALIDATION for the report instead, since none of the CURRENT rows happen to be not-ready today)")
        val_not_ready = next(r for r in rows if r["split"] == "validation" and r["operational_status"] == "not-ready")
        example_nr = build_days_forecast(val_not_ready, days_model, q_by_confidence, MODEL_NAME, PRIMARY_CONFIDENCE)
        print(f"  {json.dumps(example_nr, ensure_ascii=False)}")

    return ranked


if __name__ == "__main__":
    main()
