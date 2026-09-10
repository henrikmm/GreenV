"""Phase 10 — HTTP endpoints. Every route is a thin wrapper: validate via the Pydantic
`response_model`/request schema, call one `service.py` function, return its result. No business
rule, no artifact loading, and no model call happens directly in a route body.

Path ordering matters: FastAPI matches routes in declaration order, and `{trecho_id}` is a
catch-all single-segment parameter, so the literal paths (`/ranking`, `/predict`) are declared
BEFORE it below -- otherwise a request for `/api/v1/forecasts/ranking` would be swallowed by
`forecast_by_trecho(trecho_id="ranking")` instead.
"""
from __future__ import annotations
from typing import List, Literal, Optional

from fastapi import APIRouter, Query

from . import service
from .loader import get_store
from .schemas import Forecast, Health, PredictRequest, RankedForecast, Summary

router = APIRouter()

API_VERSION = "0.1.0"


@router.get("/health", response_model=Health, tags=["health"])
def health() -> Health:
    """Never runs a prediction or touches the snapshot's content -- only checks whether each
    artifact is present and loadable, which is already cached after the first check."""
    store = get_store()
    model_ok = store.model_available()
    calib_ok = store.calibration_available()
    snap_ok = store.snapshot_available()
    return Health(
        status="ok" if snap_ok else "degraded",
        version=API_VERSION,
        model_available=model_ok,
        calibration_available=calib_ok,
        snapshot_available=snap_ok,
    )


@router.get("/api/v1/summary", response_model=Summary, tags=["forecasts"])
def summary() -> dict:
    """Computed from the currently loaded snapshot, never hard-coded -- see service.get_summary."""
    return service.get_summary()


@router.get("/api/v1/forecasts/ranking", response_model=List[RankedForecast], tags=["forecasts"])
def ranking(limit: Optional[int] = Query(default=None, ge=1, le=500)) -> list:
    """Exactly the Phase 9 frozen ranking rule (critical -> forecast by soonest -> beyond_horizon
    -> insufficient_data, ties by trecho_id) -- re-derived at load time from the snapshot's own
    forecast objects via `greenv_vegpred.forecast.rank_forecasts`, not a second, API-local score."""
    return service.get_ranking(limit=limit)


@router.post("/api/v1/forecasts/predict", response_model=Forecast, tags=["forecasts"])
def predict(payload: PredictRequest, confidence: Literal[0.80, 0.90] = 0.90) -> dict:
    """Dynamic, on-demand inference using the frozen Random Forest -- 503s if the model or the
    calibration is unavailable AND actually needed for this specific input (see
    service.predict_now: a `height_cm >= 30` request never touches the model at all)."""
    return service.predict_now(payload, confidence=confidence)


@router.get("/api/v1/forecasts", response_model=List[RankedForecast], tags=["forecasts"])
def list_forecasts(status: Optional[str] = None, operational_status: Optional[str] = None) -> list:
    """Simple, transparent filters only -- no query language. `status`/`operational_status` are
    matched by exact string equality against the snapshot's own fields."""
    return service.list_forecasts(status=status, operational_status=operational_status)


@router.get("/api/v1/forecasts/{trecho_id}", response_model=RankedForecast, tags=["forecasts"])
def forecast_by_trecho(trecho_id: str) -> dict:
    """404 for a trecho_id not present in the current snapshot -- see service.get_forecast."""
    return service.get_forecast(trecho_id)
