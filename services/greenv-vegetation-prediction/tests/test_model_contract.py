"""Phase 11 — loading the SMALL, git-committed model artifacts (Ridge/OLS/Lasso/HistGB, all
under 1 MB, committed since the Phase 7/8 checkpoint) and confirming they satisfy the expected
protocol and are deterministic. The large Random Forest artifacts (13-45 MB, NOT committed —
AGENTS.md's 5 MB limit) are tested only if actually present on disk, under `requires_rf_model`,
so the STANDARD suite never depends on them being in the checkout (item 14's explicit requirement).
"""
from __future__ import annotations
from pathlib import Path

import pytest
from joblib import load

ARTIFACTS = Path(__file__).resolve().parents[1] / "models" / "artifacts"

SMALL_HEIGHT_MODELS = [
    "height__ridge__keep_plus_candidate.joblib",
    "height__lasso__keep_plus_candidate.joblib",
    "height__ols__keep_plus_candidate.joblib",
    "height__gradient_boosting__keep_plus_candidate.joblib",
]
SMALL_DAYS_MODELS = [
    "days_until_30cm__ridge__keep_plus_candidate.joblib",
    "days_until_30cm__lasso__keep_plus_candidate.joblib",
    "days_until_30cm__ols__keep_plus_candidate.joblib",
    "days_until_30cm__gradient_boosting__keep_plus_candidate.joblib",
]


@pytest.mark.parametrize("filename", SMALL_HEIGHT_MODELS)
class TestSmallHeightModelContract:
    def test_loads_and_exposes_the_height_protocol(self, filename):
        model = load(ARTIFACTS / filename)
        assert hasattr(model, "predict_height")
        assert hasattr(model, "predict_height_batch")

    def test_prediction_is_deterministic(self, filename, row_factory):
        model = load(ARTIFACTS / filename)
        row = row_factory()
        p1, _ = model.predict_height(row, 7)
        p2, _ = model.predict_height(row, 7)
        assert p1 == p2

    def test_prediction_is_physically_bounded(self, filename, row_factory):
        model = load(ARTIFACTS / filename)
        pred, _ = model.predict_height(row_factory(), 7)
        assert 0.0 <= pred <= 150.0


@pytest.mark.parametrize("filename", SMALL_DAYS_MODELS)
class TestSmallDaysModelContract:
    def test_loads_and_exposes_the_days_protocol(self, filename):
        model = load(ARTIFACTS / filename)
        assert hasattr(model, "predict")
        assert hasattr(model, "predict_batch")

    def test_prediction_is_deterministic(self, filename, row_factory):
        model = load(ARTIFACTS / filename)
        row = row_factory()
        p1, _ = model.predict(row)
        p2, _ = model.predict(row)
        assert p1 == p2


class TestLargeRandomForestArtifactOptional:
    """Skipped automatically when the >5MB artifact isn't in this checkout -- it never is in a
    fresh `git clone` (excluded by `.gitignore`); present only if someone ran
    `scripts/train_and_evaluate_ml.py` locally, as this development environment has."""

    RF_DAYS_PATH = ARTIFACTS / "days_until_30cm__random_forest__keep_plus_candidate.joblib"

    @pytest.mark.requires_rf_model
    def test_random_forest_days_model_is_deterministic(self, row_factory):
        if not self.RF_DAYS_PATH.exists():
            pytest.skip(f"{self.RF_DAYS_PATH.name} not present in this checkout "
                       f"(not committed to git, >5MB per AGENTS.md) -- optional test")
        model = load(self.RF_DAYS_PATH)
        row = row_factory()
        p1, _ = model.predict(row)
        p2, _ = model.predict(row)
        # Approximate, not bit-exact: this RandomForestRegressor is fit with n_jobs=-1 (Phase 7,
        # frozen), so averaging predictions across trees happens in a parallel reduction whose
        # summation order is not guaranteed identical between calls -- observed directly during
        # this phase: two calls on the same row can differ in the ~15th significant digit
        # (e.g. 26.495973624766556 vs 26.495973624766552, a ~1e-15 relative difference). That is
        # ordinary floating-point non-associativity under parallel execution, not a real
        # prediction instability, so `pytest.approx` (relative tolerance 1e-6) is the correct
        # assertion here -- tightening it to `==` was this test's own mistake, not a model bug,
        # and no Phase 7 hyperparameter (including n_jobs) was changed to "fix" it.
        assert p1 == pytest.approx(p2, rel=1e-6)
