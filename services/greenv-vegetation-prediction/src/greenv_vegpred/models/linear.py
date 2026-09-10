"""Phase 7 — the linear family: OLS, Ridge, Lasso (the "interpretable simple ML" models).

Standardised features (StandardScaler, fit on TRAIN only) for all three, so any L1/L2 penalty
weighs every feature comparably regardless of raw units (cm vs degC vs mm).

Ridge (original Phase 7 pass): alpha=0.01 stands in for near-unregularised OLS; 1.0 and 10.0 add
real shrinkage. Three values only, chosen on VALIDATION.

OLS and Lasso (Phase 7 addendum, closing the original TASK.md text's "OLS + Ridge / Lasso" item):
OLS has no hyperparameter (`OLS_GRID` is a single trivial config, kept only so the addendum script
can reuse the same select_*_model grid-search loop uniformly). Lasso's alpha grid is small and
explicit -- see reports/hyperparameters.md for why these three values and for whether OLS/Lasso
add anything over Ridge (they do not: see the addendum comparison; Ridge remains the linear
family's representative and Random Forest stays the frozen choice either way).
"""
from __future__ import annotations
from functools import partial

from sklearn.linear_model import Lasso, LinearRegression, Ridge

from .ml_common import SklearnHeightModel, SklearnDaysUntil30Model

ALPHA_GRID = [0.01, 1.0, 10.0]
OLS_GRID = [None]                    # no hyperparameter; single configuration
LASSO_GRID = [0.001, 0.01, 0.1]      # small, explicit, fixed before looking at results


def make_height_model(alpha, feature_list, categorical_cols, name="ridge"):
    return SklearnHeightModel(
        name=f"{name}(alpha={alpha})",
        # functools.partial, not a lambda/closure: lambdas cannot be pickled by joblib.dump,
        # which is needed to save the fitted model artifact (Phase 7 "save reproducibly").
        estimator_factory=partial(Ridge, alpha=alpha, random_state=42),
        feature_list=feature_list, categorical_cols=categorical_cols, scale_numeric=True,
    )


def make_days_model(alpha, feature_list, categorical_cols, name="ridge_days_until_30cm"):
    return SklearnDaysUntil30Model(
        name=f"{name}(alpha={alpha})",
        estimator_factory=partial(Ridge, alpha=alpha, random_state=42),
        feature_list=feature_list, categorical_cols=categorical_cols, scale_numeric=True,
    )


def make_ols_height_model(_cfg, feature_list, categorical_cols, name="ols"):
    return SklearnHeightModel(
        name=name,
        estimator_factory=partial(LinearRegression),
        feature_list=feature_list, categorical_cols=categorical_cols, scale_numeric=True,
    )


def make_ols_days_model(_cfg, feature_list, categorical_cols, name="ols_days_until_30cm"):
    return SklearnDaysUntil30Model(
        name=name,
        estimator_factory=partial(LinearRegression),
        feature_list=feature_list, categorical_cols=categorical_cols, scale_numeric=True,
    )


def make_lasso_height_model(alpha, feature_list, categorical_cols, name="lasso"):
    return SklearnHeightModel(
        name=f"{name}(alpha={alpha})",
        estimator_factory=partial(Lasso, alpha=alpha, random_state=42, max_iter=5000),
        feature_list=feature_list, categorical_cols=categorical_cols, scale_numeric=True,
    )


def make_lasso_days_model(alpha, feature_list, categorical_cols, name="lasso_days_until_30cm"):
    return SklearnDaysUntil30Model(
        name=f"{name}(alpha={alpha})",
        estimator_factory=partial(Lasso, alpha=alpha, random_state=42, max_iter=5000),
        feature_list=feature_list, categorical_cols=categorical_cols, scale_numeric=True,
    )
