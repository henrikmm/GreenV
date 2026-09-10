"""Phase 10 — HTTP contracts (Pydantic). Field names mirror the Phase 9 forecast object
(`greenv_vegpred.forecast.interval.build_days_forecast`) exactly, so this layer adds validation
and OpenAPI documentation without inventing a second vocabulary for the same thing.

TWO DIFFERENT "CONFIDENCE" FIELDS — kept apart here exactly as they are in `interval.py`:
  `Interval.confidence`     the STATISTICAL nominal coverage level (0.80 or 0.90) of the
                            conformal band -- a property of the method, same for every row at
                            that level.
  `Forecast.operational_confidence`  a per-row judgement of source-data trust
                            ("low"/"medium"/"high"), independent of the interval's own level.
Never merged into one field; see reports/forecast-method.md §3 for the full rationale.
"""
from __future__ import annotations
from typing import List, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field

Status = Literal["critical", "forecast", "beyond_horizon", "insufficient_data"]
OperationalConfidence = Literal["low", "medium", "high"]
OperationalStatus = Literal["ready", "not-ready"]


class Interval(BaseModel):
    lower_days: Optional[float] = None
    upper_days: Optional[float] = None
    confidence: float = Field(description="Statistical nominal coverage level of the conformal "
                              "band (0.80 or 0.90) -- NOT a data-quality judgement.")
    upper_is_horizon_bound: Optional[bool] = Field(
        default=None, description="True if the raw upper bound before clamping exceeded the "
                                  "120-day horizon -- the true upper edge is then unknown, not "
                                  "exactly 120.")


class Forecast(BaseModel):
    """One trecho's days-until-30cm forecast -- the Phase 9 object, unchanged, over HTTP."""
    model_config = ConfigDict(json_schema_extra={
        "example": {
            "trecho_id": "SP-021:norte:000000", "as_of_date": "2026-08-30",
            "captured_at": "2026-08-30", "rodovia": "SP-021", "sentido": "norte",
            "km_start": 0.0, "km_end": 0.5,
            "current_height_cm": 15.96, "critical_height_cm": 30.0, "vegetation_level": 2,
            "operational_status": "ready", "blockers": None,
            "days_until_critical": 27.0,
            "interval": {"lower_days": 0.0, "upper_days": 61.6, "confidence": 0.9,
                        "upper_is_horizon_bound": False},
            "status": "forecast", "reason": None, "operational_confidence": "medium",
            "model": "random_forest__keep_plus_candidate__framing_b", "data_provenance": "synthetic",
        }
    })

    trecho_id: str
    as_of_date: Optional[str] = None
    captured_at: Optional[str] = Field(
        default=None, description="Same value as `as_of_date` -- this pipeline has no timestamp "
                                  "finer than a date (no fabricated time-of-day). Named "
                                  "`captured_at` to match TASK.md's `capturado_em`.")
    rodovia: Optional[str] = Field(default=None, description="Parsed from trecho_id; null only "
                                   "if trecho_id doesn't match the documented format.")
    sentido: Optional[str] = None
    km_start: Optional[float] = Field(default=None, description="km, parsed from trecho_id's own "
                                      "documented suffix (schema.sql).")
    km_end: Optional[float] = Field(default=None, description="km_start + 0.5, clamped to the "
                                    "corridor's documented end (29.3 km) for the two trechos "
                                    "that are actually shorter -- verified against every trecho "
                                    "in the dataset, not assumed uniform.")
    current_height_cm: Optional[float] = None
    critical_height_cm: float = 30.0
    vegetation_level: int = Field(
        description="0-3, schema.sql's height_observation.nivel formula verbatim (not-ready or "
                    "missing height -> 0; <10cm -> 1; 10-30cm -> 2; >30cm -> 3) -- confirmed "
                    "compatible with apps/web/src/utils/classification.js's own LEVELS/"
                    "vegetationLevel (read-only check; apps/web is not modified).")
    operational_status: Optional[OperationalStatus] = None
    blockers: Optional[List[str]] = None
    days_until_critical: Optional[float] = None
    interval: Optional[Interval] = None
    status: Status
    reason: Optional[str] = None
    operational_confidence: OperationalConfidence
    model: str
    data_provenance: Literal["synthetic"] = "synthetic"


class RankedForecast(Forecast):
    rank: int = Field(description="1-indexed position from the frozen ranking rule -- see "
                                  "greenv_vegpred.forecast.ranking.")


class Summary(BaseModel):
    total_trechos: int
    critical: int
    forecast: int
    beyond_horizon: int
    insufficient_data: int
    critical_height_cm: float = 30.0
    data_provenance: Literal["synthetic"] = "synthetic"


class Health(BaseModel):
    status: Literal["ok", "degraded"]
    service: str = "greenv-vegetation-prediction"
    version: str
    model_available: bool = Field(description="Whether the frozen Random Forest days_until_30cm "
                                  ".joblib (not committed to git; >5MB) was found on disk.")
    calibration_available: bool
    snapshot_available: bool
    data_provenance: Literal["synthetic"] = "synthetic"


class PredictRequest(BaseModel):
    """Exactly the 14 columns the frozen Random Forest (`keep_plus_candidate`) was trained on,
    plus identity fields. `extra="forbid"` rejects anything else outright (422) -- there is no
    field named `true_height_cm`, `generator_seed`, `provenance`, or `dataset` here at all, so a
    client cannot submit one, let alone use it to impersonate real data; `data_provenance` in the
    response is always hardcoded to `"synthetic"` server-side, never taken from the request."""
    # `allow_inf_nan=False` (pre-push hardening) rejects NaN/Infinity/-Infinity on every float
    # field of this model with a 422, before any value reaches the frozen Random Forest -- without
    # it, Pydantic v2's `ge`/`le` constraints do NOT implicitly reject non-finite floats, so a
    # value like `height_cm: Infinity` previously passed validation and reached
    # `RandomForestRegressor.predict()`, which raises an unhandled `ValueError` (caught only by
    # the outermost 500 handler) -- and a `NaN` in an unbounded field (e.g. `tmin_week_c`) was
    # silently accepted and produced a seemingly-normal 200 response. This does not change any
    # valid range already frozen for these fields (`ge`/`le` bounds are untouched).
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False, json_schema_extra={
        "example": {
            "trecho_id": "SP-021:norte:000000", "as_of_date": "2026-09-10",
            "height_cm": 15.96, "height_prev_cm": 13.2, "weekly_growth_cm": 2.76,
            "days_since_rocada": 42, "gdd_week_tb15_c": 38.5, "tmin_week_c": 17.2,
            "rain_30d_mm": 64.0, "water_deficit_30d": -12.5, "operational_status": "ready",
        }
    })

    trecho_id: str
    as_of_date: str

    # The one required feature: the anchor observation itself (Phase 9's `insufficient_data`
    # status exists for when this is missing -- but at the API boundary, a client with no current
    # height has nothing to ask this endpoint; every other feature below has a documented,
    # TRAIN-only imputation fallback already built into the frozen model's own pipeline (Phase 7
    # `ml_common.FeaturePipeline`), so omitting them is a legitimate, already-defended choice.
    height_cm: float = Field(ge=0, le=150)

    height_prev_cm: Optional[float] = Field(default=None, ge=0, le=150)
    weekly_growth_cm: Optional[float] = Field(default=None, ge=-35, le=35)
    days_since_rocada: Optional[float] = Field(default=None, ge=0)
    gdd_week_tb15_c: Optional[float] = Field(default=None, ge=0)
    tmin_week_c: Optional[float] = None
    rain_30d_mm: Optional[float] = Field(default=None, ge=0)
    water_deficit_30d: Optional[float] = None
    operational_status: Optional[OperationalStatus] = None
    height_lag2_cm: Optional[float] = Field(default=None, ge=0, le=150)
    rocada_in_new_cycle: Optional[float] = Field(default=None, ge=0, le=1)
    dry_season_flag: Optional[float] = Field(default=None, ge=0, le=1)
    water_deficit_90d: Optional[float] = None
    vegetation_type: Optional[str] = None

    blockers: Optional[List[str]] = Field(
        default=None, description="Informational only -- never a model feature. Any non-empty "
                                  "list is echoed back and also forces operational_confidence "
                                  "considerations exactly like the batch snapshot's own rows.")

    def to_row(self) -> dict:
        """The dict shape `build_days_forecast`/`FeaturePipeline` expect -- same keys as a
        `feature_row_dev.csv` row, `blockers` re-encoded as the JSON-array STRING those consume
        (never a Python list) so `_parse_blockers` handles it identically either way."""
        import json
        d = self.model_dump(exclude={"blockers"})
        d["blockers"] = json.dumps(self.blockers) if self.blockers else None
        return d
