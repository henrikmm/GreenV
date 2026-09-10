"""Phase 7 — Random Forest Regressor.

No feature scaling (tree splits are scale-invariant). Four hand-picked configurations, not a full
cartesian grid, kept small and enumerated explicitly -- chosen on VALIDATION only (see
reports/hyperparameters.md).
"""
from __future__ import annotations
from functools import partial

from sklearn.ensemble import RandomForestRegressor

from .ml_common import SklearnHeightModel, SklearnDaysUntil30Model

# (n_estimators, max_depth, min_samples_leaf)
CONFIG_GRID = [
    (100, 6, 20),      # shallow, heavily regularised
    (100, None, 5),    # deep, lightly regularised
    (300, 6, 5),        # many shallow trees
    (300, None, 20),    # many deep-but-leaf-regularised trees
]


def _factory(n_estimators, max_depth, min_samples_leaf):
    # functools.partial, not a lambda: must be picklable for joblib.dump (Phase 7 artifact save).
    return partial(RandomForestRegressor, n_estimators=n_estimators, max_depth=max_depth,
                   min_samples_leaf=min_samples_leaf, n_jobs=-1, random_state=42)


def make_height_model(cfg, feature_list, categorical_cols, name="random_forest"):
    n, d, leaf = cfg
    return SklearnHeightModel(
        name=f"{name}(n={n},depth={d},leaf={leaf})",
        estimator_factory=_factory(n, d, leaf),
        feature_list=feature_list, categorical_cols=categorical_cols, scale_numeric=False,
    )


def make_days_model(cfg, feature_list, categorical_cols, name="random_forest_days_until_30cm"):
    n, d, leaf = cfg
    return SklearnDaysUntil30Model(
        name=f"{name}(n={n},depth={d},leaf={leaf})",
        estimator_factory=_factory(n, d, leaf),
        feature_list=feature_list, categorical_cols=categorical_cols, scale_numeric=False,
    )
