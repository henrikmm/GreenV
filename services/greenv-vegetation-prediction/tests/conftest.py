"""Phase 11 — shared fixtures for the whole suite.

`pythonpath = ["src"]` in `pyproject.toml` puts `greenv_vegpred` on `sys.path` for every test
here, without per-file `sys.path` hacks (the pattern every Phase 7-9 script used instead, since
they run standalone rather than under pytest).
"""
from __future__ import annotations
from pathlib import Path

import pytest

MODULE_ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = MODULE_ROOT / "models" / "artifacts"
DAYS_RF_PATH = ARTIFACTS / "days_until_30cm__random_forest__keep_plus_candidate.joblib"


@pytest.fixture(scope="session")
def module_root() -> Path:
    return MODULE_ROOT


@pytest.fixture(scope="session")
def schema_sql_text() -> str:
    return (MODULE_ROOT / "src" / "greenv_vegpred" / "schema.sql").read_text(encoding="utf-8")


def make_row(**overrides) -> dict:
    """A minimal, self-consistent feature_row-shaped dict (string values, matching what
    `csv.DictReader` itself produces) for tests that don't need the full 33k-row CSV. Every
    KEEP/CANDIDATE-adjacent column the frozen pipeline reads is present with a plausible default;
    override only what a given test cares about."""
    row = {
        "trecho_id": "SP-021:norte:000000", "as_of_date": "2026-09-10",
        "rodovia": "SP-021", "sentido": "norte", "provenance": "synthetic",
        "height_cm": "15.0", "height_prev_cm": "12.0", "height_lag2_cm": "10.0",
        "weekly_growth_cm": "3.0", "days_since_rocada": "20",
        "gdd_week_tb15_c": "30.0", "tmin_week_c": "15.0",
        "rain_30d_mm": "50.0", "water_deficit_30d": "0.0", "water_deficit_90d": "0.0",
        "operational_status": "ready", "blockers": "[]",
        "rocada_in_new_cycle": "0", "dry_season_flag": "0", "vegetation_type": "braquiaria",
        "target_height_plus_7d_cm": "", "target_height_plus_14d_cm": "", "target_height_plus_30d_cm": "",
        "target_days_until_30cm": "", "target_days_until_30cm_censored": "0",
    }
    row.update(overrides)
    return row


@pytest.fixture
def row_factory():
    return make_row


class FakeDaysModel:
    """A deterministic stand-in for `SklearnDaysUntil30Model`, used wherever a test needs to
    control exactly what `.predict()` returns without loading the real (unversioned, >5MB)
    Random Forest artifact -- see item 9/14's explicit permission to do this for unit tests."""

    def __init__(self, point=None, censored=False):
        self._point = point
        self._censored = censored

    def predict(self, row):
        if self._censored:
            return None, {"censored": True}
        return self._point, {"censored": False}


@pytest.fixture
def fake_days_model():
    return FakeDaysModel


@pytest.fixture(scope="session")
def real_days_model_available() -> bool:
    return DAYS_RF_PATH.exists()


requires_rf_model = pytest.mark.requires_rf_model
