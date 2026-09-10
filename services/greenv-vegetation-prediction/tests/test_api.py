"""Phase 11 — HTTP contract tests via FastAPI's `TestClient`. Real snapshot data
(`data/forecast/ranking_current.json`, produced in Phase 9) backs the read endpoints; the
model-absent scenario (item 13) is simulated by monkeypatching `loader.DAYS_MODEL_PATH` to a path
that doesn't exist — the real, unversioned `.joblib` is never moved, deleted, or overwritten.
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from greenv_vegpred.api import loader
from greenv_vegpred.api.app import app


@pytest.fixture
def client():
    return TestClient(app)


@pytest.fixture(autouse=True)
def _fresh_store():
    """Every test gets a freshly-constructed ArtifactStore -- an earlier test's monkeypatched
    path must never leak into a later test via the process-wide lru_cache singleton."""
    loader.get_store.cache_clear()
    yield
    loader.get_store.cache_clear()


class TestHealth:
    def test_returns_200_and_expected_shape(self, client):
        r = client.get("/health")
        assert r.status_code == 200
        body = r.json()
        assert body["status"] in ("ok", "degraded")
        assert body["service"] == "greenv-vegetation-prediction"
        assert body["data_provenance"] == "synthetic"
        assert isinstance(body["model_available"], bool)
        assert isinstance(body["snapshot_available"], bool)


class TestSummary:
    def test_returns_200_with_counts_that_add_up(self, client):
        r = client.get("/api/v1/summary")
        assert r.status_code == 200
        body = r.json()
        assert body["critical_height_cm"] == 30.0
        assert body["data_provenance"] == "synthetic"
        assert body["total_trechos"] == (
            body["critical"] + body["forecast"] + body["beyond_horizon"] + body["insufficient_data"]
        )


class TestRanking:
    def test_limit_returns_exactly_that_many(self, client):
        r = client.get("/api/v1/forecasts/ranking?limit=3")
        assert r.status_code == 200
        assert len(r.json()) == 3

    def test_ranks_are_sequential_starting_at_1(self, client):
        r = client.get("/api/v1/forecasts/ranking?limit=5")
        assert [row["rank"] for row in r.json()] == [1, 2, 3, 4, 5]

    @pytest.mark.parametrize("bad_limit", [0, -1, 501])
    def test_invalid_limit_is_422(self, client, bad_limit):
        r = client.get(f"/api/v1/forecasts/ranking?limit={bad_limit}")
        assert r.status_code == 422

    def test_new_contract_fields_present_and_not_conflated(self, client):
        row = client.get("/api/v1/forecasts/ranking?limit=1").json()[0]
        for field in ("rodovia", "sentido", "km_start", "km_end", "captured_at",
                      "vegetation_level", "operational_confidence"):
            assert field in row
        assert "confidence" not in row  # only nested under `interval`, never top-level


class TestForecastByTrecho:
    def test_existing_trecho_returns_200(self, client):
        trecho_id = client.get("/api/v1/forecasts/ranking?limit=1").json()[0]["trecho_id"]
        r = client.get(f"/api/v1/forecasts/{trecho_id}")
        assert r.status_code == 200
        assert r.json()["trecho_id"] == trecho_id

    def test_nonexistent_trecho_is_404(self, client):
        r = client.get("/api/v1/forecasts/SP-021:norte:999999")
        assert r.status_code == 404
        assert "detail" in r.json()


class TestListForecasts:
    def test_filters_by_status(self, client):
        r = client.get("/api/v1/forecasts?status=forecast")
        body = r.json()
        assert r.status_code == 200 and len(body) > 0
        assert all(row["status"] == "forecast" for row in body)

    def test_filters_by_operational_status(self, client):
        r = client.get("/api/v1/forecasts?operational_status=not-ready")
        body = r.json()
        assert r.status_code == 200 and len(body) > 0
        assert all(row["operational_status"] == "not-ready" for row in body)


VALID_PREDICT_PAYLOAD = {
    "trecho_id": "demo:001", "as_of_date": "2026-09-10", "height_cm": 15.0,
    "height_prev_cm": 12.0, "weekly_growth_cm": 3.0, "days_since_rocada": 20,
    "gdd_week_tb15_c": 30.0, "tmin_week_c": 15.0, "rain_30d_mm": 50.0,
    "water_deficit_30d": 0.0, "operational_status": "ready",
}


class TestPredict:
    def test_valid_request_above_threshold_never_needs_the_model(self, client):
        r = client.post("/api/v1/forecasts/predict", json={**VALID_PREDICT_PAYLOAD, "height_cm": 35.0})
        assert r.status_code == 200
        assert r.json()["status"] == "critical"

    def test_valid_request_below_threshold_200_or_503_depending_on_model(self, client):
        r = client.post("/api/v1/forecasts/predict", json=VALID_PREDICT_PAYLOAD)
        assert r.status_code in (200, 503)

    def test_generator_seed_is_rejected_with_422(self, client):
        r = client.post("/api/v1/forecasts/predict", json={**VALID_PREDICT_PAYLOAD, "generator_seed": 42})
        assert r.status_code == 422

    def test_true_height_cm_is_rejected_with_422(self, client):
        r = client.post("/api/v1/forecasts/predict", json={**VALID_PREDICT_PAYLOAD, "true_height_cm": 999.0})
        assert r.status_code == 422

    def test_missing_required_height_cm_is_422(self, client):
        payload = dict(VALID_PREDICT_PAYLOAD)
        del payload["height_cm"]
        r = client.post("/api/v1/forecasts/predict", json=payload)
        assert r.status_code == 422


class TestPredictRejectsNonFiniteNumbers:
    """Pre-push hardening regression (audit findings F-02/F-03): NaN/Infinity/-Infinity must be
    rejected with 422, never reach the model (which would raise an unhandled `ValueError` deep in
    scikit-learn, surfacing as an unrelated 500) and never be silently accepted into a seemingly
    normal 200 response. httpx's own `json=` convenience encoder refuses to serialize these
    values client-side (`allow_nan=False`), so each case below sends a hand-crafted raw JSON body
    via `content=` -- the only way to exercise the server's own (Pydantic) validation."""

    @staticmethod
    def _post_raw(client, body: str):
        return client.post(
            "/api/v1/forecasts/predict", content=body.encode("utf-8"),
            headers={"content-type": "application/json"},
        )

    @pytest.mark.parametrize("token", ["NaN", "Infinity", "-Infinity"])
    def test_non_finite_height_cm_is_422(self, client, token):
        body = ('{"trecho_id":"demo:001","as_of_date":"2026-09-10","height_cm":%s}' % token)
        r = self._post_raw(client, body)
        assert r.status_code == 422
        assert r.json()["detail"][0]["loc"][-1] == "height_cm"

    @pytest.mark.parametrize("token", ["NaN", "Infinity"])
    def test_non_finite_auxiliary_feature_is_422(self, client, token):
        """`tmin_week_c` has no `ge`/`le` bound at all -- before this hardening, a NaN here was
        silently accepted (HTTP 200, a seemingly normal prediction, no error signal)."""
        body = (
            '{"trecho_id":"demo:001","as_of_date":"2026-09-10","height_cm":15.0,'
            '"tmin_week_c":%s}' % token
        )
        r = self._post_raw(client, body)
        assert r.status_code == 422
        assert r.json()["detail"][0]["loc"][-1] == "tmin_week_c"

    def test_response_body_for_non_finite_input_is_json_serializable_and_has_no_stack_trace(self, client):
        """The rejected raw value is echoed back in the error detail; it must be sanitized to a
        plain string (`"inf"`/`"nan"`) rather than crash the response encoder -- see `app.py`'s
        `validation_exception_handler`/`_json_safe`."""
        r = self._post_raw(client, '{"trecho_id":"demo:001","as_of_date":"2026-09-10","height_cm":Infinity}')
        assert r.status_code == 422
        assert "Traceback" not in r.text
        assert r.json()["detail"][0]["input"] == "inf"

    def test_valid_finite_payload_still_returns_200(self, client):
        r = client.post("/api/v1/forecasts/predict", json=VALID_PREDICT_PAYLOAD)
        assert r.status_code == 200


class TestModelAbsent:
    """Item 13 — simulated WITHOUT touching the real `.joblib` on disk."""

    @pytest.fixture
    def client_without_model(self, monkeypatch, tmp_path):
        monkeypatch.setattr(loader, "DAYS_MODEL_PATH", tmp_path / "does-not-exist.joblib")
        loader.get_store.cache_clear()
        yield TestClient(app)
        loader.get_store.cache_clear()

    def test_health_reports_model_unavailable(self, client_without_model):
        assert client_without_model.get("/health").json()["model_available"] is False

    def test_snapshot_endpoints_still_return_200(self, client_without_model):
        assert client_without_model.get("/api/v1/forecasts/ranking?limit=2").status_code == 200
        assert client_without_model.get("/api/v1/summary").status_code == 200

    def test_predict_below_threshold_is_503(self, client_without_model):
        r = client_without_model.post("/api/v1/forecasts/predict", json=VALID_PREDICT_PAYLOAD)
        assert r.status_code == 503

    def test_predict_at_or_above_threshold_is_still_200(self, client_without_model):
        r = client_without_model.post(
            "/api/v1/forecasts/predict", json={**VALID_PREDICT_PAYLOAD, "height_cm": 35.0}
        )
        assert r.status_code == 200
        assert r.json()["status"] == "critical"


class TestArtifactErrorsAreSanitized:
    """Pre-push hardening regression (audit finding F-01): a missing or corrupt snapshot/
    calibration file must never surface an absolute local filesystem path (which, on this
    developer's machine, embeds the OS username), a traceback, or a raw Python exception string
    in the client-facing response body. Uses `tmp_path` -- the real, committed artifacts under
    `data/` are never touched, moved, or overwritten."""

    def test_missing_snapshot_error_has_no_absolute_path(self, monkeypatch, tmp_path):
        missing = tmp_path / "does-not-exist.json"
        monkeypatch.setattr(loader, "SNAPSHOT_PATH", missing)
        loader.get_store.cache_clear()
        client = TestClient(app, raise_server_exceptions=False)

        r = client.get("/api/v1/forecasts/ranking")

        assert r.status_code == 503
        assert str(tmp_path) not in r.text
        assert "does-not-exist.json" in r.text  # filename alone is fine, expected
        assert "Traceback" not in r.text

    def test_missing_calibration_error_has_no_absolute_path(self, monkeypatch, tmp_path):
        missing = tmp_path / "also-missing.json"
        monkeypatch.setattr(loader, "CALIB_PATH", missing)
        loader.get_store.cache_clear()
        client = TestClient(app, raise_server_exceptions=False)

        r = client.get("/health")

        assert r.status_code == 200
        assert r.json()["calibration_available"] is False
        assert str(tmp_path) not in r.text

    def test_corrupt_snapshot_error_has_no_absolute_path_or_raw_exception(self, monkeypatch, tmp_path):
        corrupt = tmp_path / "corrupt.json"
        corrupt.write_text("{not valid json", encoding="utf-8")
        monkeypatch.setattr(loader, "SNAPSHOT_PATH", corrupt)
        loader.get_store.cache_clear()
        client = TestClient(app, raise_server_exceptions=False)

        r = client.get("/api/v1/forecasts/ranking")

        assert r.status_code == 503
        assert str(tmp_path) not in r.text
        assert "Expecting property name" not in r.text  # raw JSONDecodeError message, unsanitized

    def test_corrupt_calibration_error_has_no_absolute_path_or_raw_exception(self, monkeypatch, tmp_path):
        corrupt = tmp_path / "corrupt-calib.json"
        corrupt.write_text("{not valid json", encoding="utf-8")
        monkeypatch.setattr(loader, "CALIB_PATH", corrupt)
        loader.get_store.cache_clear()
        client = TestClient(app, raise_server_exceptions=False)

        r = client.get("/health")

        assert r.status_code == 200
        assert r.json()["calibration_available"] is False
        assert str(tmp_path) not in r.text


class TestNoSensitiveDataLeaks:
    FORBIDDEN_SUBSTRINGS = ("generator_seed", "generator_params_hash", "generator_version",
                           "Traceback", "site-packages")

    def test_ranking_response_never_mentions_forbidden_strings(self, client):
        text = client.get("/api/v1/forecasts/ranking").text
        for s in self.FORBIDDEN_SUBSTRINGS:
            assert s not in text

    def test_unhandled_error_never_returns_a_stack_trace(self, monkeypatch):
        def boom(*a, **kw):
            raise RuntimeError("deliberate failure planted by this test")
        monkeypatch.setattr("greenv_vegpred.api.service.get_summary", boom)
        no_raise_client = TestClient(app, raise_server_exceptions=False)
        r = no_raise_client.get("/api/v1/summary")
        assert r.status_code == 500
        assert r.json() == {"detail": "internal error"}
        assert "Traceback" not in r.text and "RuntimeError" not in r.text
