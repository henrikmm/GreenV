"""Phase 11 — `build_days_forecast` (item 9) and `conformal_quantile` (item 10), the frozen
Phase 9 logic. Every case uses a deterministic `FakeDaysModel` stub instead of the real,
unversioned Random Forest — the real q80/q90 values in `data/models/interval_calibration.json`
are never touched or recomputed by anything in this file.
"""
from __future__ import annotations
import math

import pytest

from greenv_vegpred.forecast.interval import build_days_forecast, conformal_quantile

MODEL_NAME = "test-model"
Q = {0.80: 5.0, 0.90: 10.0}  # deliberately round, hand-checkable test values -- NOT the real calibration


class TestBuildDaysForecastStatuses:
    def test_a_height_at_or_above_30_is_critical_with_zero_days(self, row_factory, fake_days_model):
        row = row_factory(height_cm="35.0")
        model = fake_days_model(point=999)  # must not even be consulted
        fc = build_days_forecast(row, model, Q, MODEL_NAME, confidence=0.90)
        assert fc["status"] == "critical"
        assert fc["days_until_critical"] == 0.0
        assert fc["interval"] == {"lower_days": 0.0, "upper_days": 0.0, "confidence": 0.90}

    def test_a_height_of_exactly_30_is_also_critical(self, row_factory, fake_days_model):
        row = row_factory(height_cm="30.0")
        fc = build_days_forecast(row, fake_days_model(point=999), Q, MODEL_NAME)
        assert fc["status"] == "critical"

    def test_b_finite_model_point_below_30_is_forecast(self, row_factory, fake_days_model):
        row = row_factory(height_cm="15.0")
        fc = build_days_forecast(row, fake_days_model(point=20.0), Q, MODEL_NAME, confidence=0.90)
        assert fc["status"] == "forecast"
        assert fc["days_until_critical"] == 20.0
        assert fc["interval"]["lower_days"] == 10.0   # 20 - 10
        assert fc["interval"]["upper_days"] == 30.0   # 20 + 10
        assert fc["interval"]["upper_is_horizon_bound"] is False

    def test_c_model_reports_no_crossing_is_beyond_horizon(self, row_factory, fake_days_model):
        row = row_factory(height_cm="2.0")
        fc = build_days_forecast(row, fake_days_model(censored=True), Q, MODEL_NAME)
        assert fc["status"] == "beyond_horizon"
        assert fc["days_until_critical"] is None
        assert fc["interval"] is None

    def test_d_missing_anchor_height_is_insufficient_data(self, row_factory, fake_days_model):
        row = row_factory(height_cm="")
        fc = build_days_forecast(row, fake_days_model(point=20.0), Q, MODEL_NAME)
        assert fc["status"] == "insufficient_data"
        assert fc["reason"] == "missing_anchor_height_cm"
        assert fc["days_until_critical"] is None
        assert fc["interval"] is None

    def test_e_lower_bound_clamps_to_zero_not_negative(self, row_factory, fake_days_model):
        row = row_factory(height_cm="5.0")
        fc = build_days_forecast(row, fake_days_model(point=3.0), Q, MODEL_NAME, confidence=0.90)
        # raw lower would be 3 - 10 = -7
        assert fc["interval"]["lower_days"] == 0.0

    def test_f_upper_bound_clamps_to_horizon_and_flags_it(self, row_factory, fake_days_model):
        row = row_factory(height_cm="5.0")
        fc = build_days_forecast(row, fake_days_model(point=115.0), Q, MODEL_NAME, confidence=0.90)
        # raw upper would be 115 + 10 = 125 > 120
        assert fc["interval"]["upper_days"] == 120.0
        assert fc["interval"]["upper_is_horizon_bound"] is True
        assert fc["status"] == "forecast"  # a finite point estimate still exists

    def test_g_not_ready_forces_operational_confidence_low_even_when_forecast(self, row_factory, fake_days_model):
        row = row_factory(height_cm="10.0", operational_status="not-ready")
        fc = build_days_forecast(row, fake_days_model(point=20.0), Q, MODEL_NAME)
        assert fc["operational_confidence"] == "low"
        assert fc["status"] == "forecast"   # the point estimate/interval are NOT suppressed
        assert fc["interval"]["confidence"] == 0.90  # unchanged -- the two concepts don't mix

    def test_g_not_ready_forces_low_even_when_critical(self, row_factory, fake_days_model):
        row = row_factory(height_cm="40.0", operational_status="not-ready")
        fc = build_days_forecast(row, fake_days_model(point=999), Q, MODEL_NAME)
        assert fc["operational_confidence"] == "low"
        assert fc["status"] == "critical"

    def test_h_ready_forecast_is_medium(self, row_factory, fake_days_model):
        row = row_factory(height_cm="10.0", operational_status="ready")
        fc = build_days_forecast(row, fake_days_model(point=20.0), Q, MODEL_NAME)
        assert fc["operational_confidence"] == "medium"

    def test_h_ready_critical_is_high(self, row_factory, fake_days_model):
        row = row_factory(height_cm="40.0", operational_status="ready")
        fc = build_days_forecast(row, fake_days_model(point=999), Q, MODEL_NAME)
        assert fc["operational_confidence"] == "high"

    def test_blockers_are_echoed_back_parsed(self, row_factory, fake_days_model):
        row = row_factory(height_cm="10.0", blockers='["depth-degraded"]')
        fc = build_days_forecast(row, fake_days_model(point=20.0), Q, MODEL_NAME)
        assert fc["blockers"] == ["depth-degraded"]

    def test_empty_blockers_list_becomes_none_not_an_empty_list(self, row_factory, fake_days_model):
        row = row_factory(height_cm="10.0", blockers="[]")
        fc = build_days_forecast(row, fake_days_model(point=20.0), Q, MODEL_NAME)
        assert fc["blockers"] is None

    def test_data_provenance_defaults_to_synthetic(self, row_factory, fake_days_model):
        row = row_factory(height_cm="10.0")
        row.pop("provenance", None)
        fc = build_days_forecast(row, fake_days_model(point=20.0), Q, MODEL_NAME)
        assert fc["data_provenance"] == "synthetic"

    def test_no_administrative_fields_leak_into_the_object(self, row_factory, fake_days_model):
        row = row_factory(height_cm="10.0")
        fc = build_days_forecast(row, fake_days_model(point=20.0), Q, MODEL_NAME)
        for forbidden in ("generator_seed", "generator_params_hash", "dataset", "true_height_cm"):
            assert forbidden not in fc


class TestConformalQuantile:
    """Hand-checkable per the split-conformal formula: q = the ceil((n+1)(1-alpha))-th smallest
    |residual|. n=9 residuals 1..9 (sorted, already unique) makes the arithmetic exact."""

    RESIDUALS = [1, 2, 3, 4, 5, 6, 7, 8, 9]  # n=9

    def test_80_percent_quantile(self):
        # alpha=0.20, k = ceil(10 * 0.80) = 8 -> 8th smallest of [1..9] = 8
        assert conformal_quantile(self.RESIDUALS, alpha=0.20) == 8

    def test_90_percent_quantile(self):
        # alpha=0.10, k = ceil(10 * 0.90) = 9 -> 9th smallest of [1..9] = 9 (the max)
        assert conformal_quantile(self.RESIDUALS, alpha=0.10) == 9

    def test_insufficient_calibration_sample_returns_infinity(self):
        # n=2, alpha=0.10 -> k = ceil(3 * 0.90) = 3 > n=2 -> not enough data for this confidence.
        q = conformal_quantile([1, 2], alpha=0.10)
        assert math.isinf(q)

    def test_empty_sample_returns_infinity(self):
        assert math.isinf(conformal_quantile([], alpha=0.10))

    def test_uses_absolute_value_of_residuals(self):
        # [-9..-1] must give the same result as [1..9] -- the formula uses |residual|.
        negatives = [-x for x in self.RESIDUALS]
        assert conformal_quantile(negatives, alpha=0.20) == conformal_quantile(self.RESIDUALS, alpha=0.20)

    def test_larger_n_with_known_result(self):
        # n=99, residuals 1..99, alpha=0.10 -> k = ceil(100*0.90) = 90 -> 90th smallest = 90.
        assert conformal_quantile(list(range(1, 100)), alpha=0.10) == 90
