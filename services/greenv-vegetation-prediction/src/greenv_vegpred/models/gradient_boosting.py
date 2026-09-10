"""Phase 7 — Gradient Boosting: scikit-learn HistGradientBoostingRegressor.

Chosen over XGBoost/LightGBM per TASK.md's own preference ("keeps the module pip-only and
portable") -- this pass already had to install scikit-learn itself (absent from the base
environment; numpy was present but sklearn/scipy were not), so a second native dependency was not
justified without a concrete reason HistGradientBoosting could not meet. No feature scaling
(tree-based). Four hand-picked configurations, chosen on VALIDATION only (see
reports/hyperparameters.md).
"""
from __future__ import annotations
from functools import partial

from sklearn.ensemble import HistGradientBoostingRegressor

from .ml_common import SklearnHeightModel, SklearnDaysUntil30Model

# (learning_rate, max_iter, max_leaf_nodes)
CONFIG_GRID = [
    (0.05, 100, 15),
    (0.05, 300, 31),
    (0.10, 100, 31),
    (0.10, 300, 15),
]


def _factory(learning_rate, max_iter, max_leaf_nodes):
    # functools.partial, not a lambda: must be picklable for joblib.dump (Phase 7 artifact save).
    return partial(HistGradientBoostingRegressor, learning_rate=learning_rate, max_iter=max_iter,
                   max_leaf_nodes=max_leaf_nodes, random_state=42)


def make_height_model(cfg, feature_list, categorical_cols, name="gradient_boosting"):
    lr, it, leaves = cfg
    return SklearnHeightModel(
        name=f"{name}(lr={lr},iter={it},leaves={leaves})",
        estimator_factory=_factory(lr, it, leaves),
        feature_list=feature_list, categorical_cols=categorical_cols, scale_numeric=False,
    )


def make_days_model(cfg, feature_list, categorical_cols, name="gradient_boosting_days_until_30cm"):
    lr, it, leaves = cfg
    return SklearnDaysUntil30Model(
        name=f"{name}(lr={lr},iter={it},leaves={leaves})",
        estimator_factory=_factory(lr, it, leaves),
        feature_list=feature_list, categorical_cols=categorical_cols, scale_numeric=False,
    )
