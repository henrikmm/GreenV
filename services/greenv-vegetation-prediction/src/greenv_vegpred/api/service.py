"""Phase 10 — the (thin) service layer: everything a route needs to do besides HTTP shaping.
Routes call these functions and nothing else; no artifact loading, no business rule, and no
model call lives directly inside a route function (`routes.py`).
"""
from __future__ import annotations
from typing import Optional

from fastapi import HTTPException

from .loader import get_store, MODEL_NAME
from .trecho_meta import parse_trecho_id, vegetation_level
from ..forecast import build_days_forecast

CRITICAL_HEIGHT_CM = 30.0


def _enrich(fc: dict) -> dict:
    """Adds the trecho-identity and classification fields the Phase 9 forecast object itself
    doesn't carry (`rodovia`/`sentido`/`km_start`/`km_end`/`captured_at`/`vegetation_level`) --
    all deterministically derived, never re-computed by the model and never touching Phase 9's
    own `build_days_forecast`/snapshot files. See `trecho_meta.py` for exactly what each is
    derived from and why. Applied once, at the API boundary, to every forecast dict just before
    it is handed to a response model -- snapshot content in `loader.py` stays unenriched."""
    meta = parse_trecho_id(fc.get("trecho_id")) or {}
    return {
        **fc,
        "rodovia": meta.get("rodovia"),
        "sentido": meta.get("sentido"),
        "km_start": meta.get("km_start"),
        "km_end": meta.get("km_end"),
        "captured_at": fc.get("as_of_date"),  # same value, TASK.md's preferred name for it; this
                                              # pipeline has no timestamp finer than a date (no
                                              # fabricated time-of-day is added)
        "vegetation_level": vegetation_level(fc.get("current_height_cm"), fc.get("operational_status")),
    }


def _snapshot_or_503() -> list:
    store = get_store()
    snap = store.snapshot()
    if snap is None:
        raise HTTPException(status_code=503, detail=(
            f"forecast snapshot unavailable ({store.snapshot_status_message()}). "
            "Regenerate with: python scripts/build_current_forecast_batch.py"
        ))
    return snap


def get_forecast(trecho_id: str) -> dict:
    for fc in _snapshot_or_503():
        if fc["trecho_id"] == trecho_id:
            return _enrich(fc)
    raise HTTPException(status_code=404, detail=f"no forecast for trecho_id={trecho_id!r}")


def get_ranking(limit: Optional[int] = None) -> list:
    snap = _snapshot_or_503()
    if limit is not None:
        snap = snap[:limit]
    return [_enrich(fc) for fc in snap]


def list_forecasts(status: Optional[str] = None, operational_status: Optional[str] = None) -> list:
    out = _snapshot_or_503()
    if status is not None:
        out = [fc for fc in out if fc["status"] == status]
    if operational_status is not None:
        out = [fc for fc in out if fc.get("operational_status") == operational_status]
    return [_enrich(fc) for fc in out]


def get_summary() -> dict:
    snap = _snapshot_or_503()
    counts = {"critical": 0, "forecast": 0, "beyond_horizon": 0, "insufficient_data": 0}
    for fc in snap:
        counts[fc["status"]] = counts.get(fc["status"], 0) + 1
    return {"total_trechos": len(snap), **counts,
           "critical_height_cm": CRITICAL_HEIGHT_CM, "data_provenance": "synthetic"}


def predict_now(payload, confidence: float = 0.90) -> dict:
    """`payload`: schemas.PredictRequest. If `height_cm >= 30`, the frozen model is never even
    consulted (build_days_forecast's own deterministic `critical` branch) -- so this only 503s
    when the model/calibration is genuinely needed and missing, not unconditionally."""
    store = get_store()
    row = payload.to_row()
    if payload.height_cm < CRITICAL_HEIGHT_CM:
        if not store.model_available():
            raise HTTPException(status_code=503, detail=(
                f"inference model unavailable: {store.model_status_message()}"
            ))
        if not store.calibration_available():
            raise HTTPException(status_code=503, detail=(
                "interval calibration unavailable -- data/models/interval_calibration.json "
                "missing or invalid; re-run scripts/calibrate_and_evaluate_intervals.py"
            ))
    fc = build_days_forecast(row, store.days_model(), store.calibration() or {},
                             MODEL_NAME, confidence=confidence)
    return _enrich(fc)
