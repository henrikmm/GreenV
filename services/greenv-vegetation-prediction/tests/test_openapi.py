"""Phase 11 — OpenAPI schema generation and the presence of the contract fields this API is
built on. Deliberately NOT a full-YAML snapshot diff (the phase's own instruction names that as a
brittle-maintenance risk) — only structural existence checks against the live `app.openapi()`.
"""
from __future__ import annotations

from greenv_vegpred.api.app import app

EXPECTED_PATHS = {
    "/health", "/api/v1/summary", "/api/v1/forecasts/ranking",
    "/api/v1/forecasts", "/api/v1/forecasts/{trecho_id}", "/api/v1/forecasts/predict",
}

CRITICAL_FORECAST_FIELDS = {
    "interval", "operational_confidence", "vegetation_level",
    "rodovia", "sentido", "km_start", "km_end", "captured_at", "data_provenance",
}


def test_openapi_generates_without_error():
    schema = app.openapi()
    assert schema["openapi"].startswith("3.")


def test_expected_paths_are_present():
    schema = app.openapi()
    assert EXPECTED_PATHS <= set(schema["paths"].keys())


def test_forecast_schema_has_the_critical_fields():
    schema = app.openapi()
    props = schema["components"]["schemas"]["Forecast"]["properties"]
    assert CRITICAL_FORECAST_FIELDS <= set(props.keys())


def test_interval_confidence_and_operational_confidence_are_distinct_fields():
    schema = app.openapi()
    forecast_props = schema["components"]["schemas"]["Forecast"]["properties"]
    assert "operational_confidence" in forecast_props
    assert "confidence" not in forecast_props  # only nested inside Interval, never top-level
    interval_props = schema["components"]["schemas"]["Interval"]["properties"]
    assert "confidence" in interval_props


def test_data_provenance_is_pinned_to_synthetic():
    schema = app.openapi()
    prop = schema["components"]["schemas"]["Forecast"]["properties"]["data_provenance"]
    assert "synthetic" in str(prop)


def test_predict_request_schema_has_no_leakage_fields():
    schema = app.openapi()
    props = schema["components"]["schemas"]["PredictRequest"]["properties"]
    for forbidden in ("true_height_cm", "generator_seed", "provenance", "dataset"):
        assert forbidden not in props


def test_predict_request_forbids_extra_fields():
    schema = app.openapi()
    predict_schema = schema["components"]["schemas"]["PredictRequest"]
    assert predict_schema.get("additionalProperties") is False
