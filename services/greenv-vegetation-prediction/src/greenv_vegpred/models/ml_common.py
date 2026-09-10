"""Phase 7 — shared preprocessing and model wrappers for the ML candidates.

Every model here implements the same duck-typed protocol the Phase 6 harness expects:
  .fit(train_rows) -> self
  .predict_height(row, horizon_days) -> (value_cm, meta)      (height models)
  .predict(row) -> (value_days_or_None, meta)                  (days-until-30cm models)

Feature lists come ONLY from greenv_vegpred.features.feature_spec (KEEP / a small, justified
CANDIDATE extension) -- never raw CSV columns, never true_height_cm, never an administrative
identity column (see feature_spec.EXCLUDE_LEAKAGE).
"""
from __future__ import annotations
import numpy as np
from sklearn.preprocessing import StandardScaler

CATEGORICAL_COLS = {"operational_status", "vegetation_type"}
HEIGHT_CLIP = (0.0, 150.0)     # physical bounds, Phase 4 synthetic-model.md H_HARDCAP
DAYS_HORIZON = 120.0           # same right-censoring horizon as Phase 5/6


def fnum(x):
    return None if x in (None, "") else float(x)


class FeaturePipeline:
    """Fit-on-train-only preprocessing: median imputation (numeric) + one-hot with an implicit
    'unknown' bucket (categorical, an unseen or missing value -> an all-zero one-hot row) +
    optional standardisation (linear models only; tree models are scale-invariant)."""

    def __init__(self, feature_list, categorical_cols=CATEGORICAL_COLS, scale_numeric=False):
        self.feature_list = list(feature_list)
        self.categorical_cols = [c for c in self.feature_list if c in categorical_cols]
        self.numeric_cols = [c for c in self.feature_list if c not in categorical_cols]
        self.scale_numeric = scale_numeric

    def fit(self, rows):
        self.medians = {}
        for c in self.numeric_cols:
            vals = [fnum(r.get(c)) for r in rows if fnum(r.get(c)) is not None]
            self.medians[c] = float(np.median(vals)) if vals else 0.0
        self.cat_categories = {}
        for c in self.categorical_cols:
            cats = sorted({r[c] for r in rows if r.get(c) not in (None, "")})
            self.cat_categories[c] = cats
        if self.scale_numeric:
            Xnum = self._numeric_matrix(rows)
            self.scaler = StandardScaler().fit(Xnum)
        else:
            self.scaler = None
        return self

    def _numeric_matrix(self, rows):
        if not self.numeric_cols:
            return np.zeros((len(rows), 0))
        cols = []
        for c in self.numeric_cols:
            col = np.array([fnum(r.get(c)) if fnum(r.get(c)) is not None else self.medians[c] for r in rows],
                           dtype=float)
            cols.append(col)
        return np.column_stack(cols)

    def transform(self, rows):
        Xnum = self._numeric_matrix(rows)
        if self.scaler is not None:
            Xnum = self.scaler.transform(Xnum)
        blocks = [Xnum]
        names = list(self.numeric_cols)
        for c in self.categorical_cols:
            cats = self.cat_categories[c]
            onehot = np.zeros((len(rows), len(cats)))
            for i, r in enumerate(rows):
                v = r.get(c)
                if v in cats:
                    onehot[i, cats.index(v)] = 1.0
                # else: all-zero row = the implicit "unknown" bucket (unseen or missing value)
            blocks.append(onehot)
            names += [f"{c}={cat}" for cat in cats]
        return np.hstack(blocks) if blocks else np.zeros((len(rows), 0)), names


class SklearnHeightModel:
    """One fitted sklearn regressor PER horizon (7/14/30 d) -- direct framing, not recursive
    (TASK.md's stated risk: recursive multi-step error accumulation). Each horizon's model is fit
    only on TRAIN rows whose target for that horizon exists (no imputed targets, ever)."""

    def __init__(self, name, estimator_factory, feature_list, categorical_cols=CATEGORICAL_COLS,
                scale_numeric=False):
        self.name = name
        self.estimator_factory = estimator_factory
        self.feature_list = feature_list
        self.categorical_cols = categorical_cols
        self.scale_numeric = scale_numeric
        self.sub = {}

    def fit(self, train_rows):
        for h in (7, 14, 30):
            tgt = f"target_height_plus_{h}d_cm"
            rows_h = [r for r in train_rows if fnum(r.get(tgt)) is not None]
            pipe = FeaturePipeline(self.feature_list, self.categorical_cols, self.scale_numeric).fit(rows_h)
            X, _ = pipe.transform(rows_h)
            y = np.array([float(r[tgt]) for r in rows_h])
            est = self.estimator_factory()
            est.fit(X, y)
            self.sub[h] = (pipe, est, len(rows_h))
        return self

    def predict_height(self, row, horizon_days):
        pipe, est, _ = self.sub[horizon_days]
        X, _ = pipe.transform([row])
        pred = float(est.predict(X)[0])
        pred = max(HEIGHT_CLIP[0], min(HEIGHT_CLIP[1], pred))
        return pred, {}

    def predict_height_batch(self, rows, horizon_days):
        """Vectorised path used only for hyperparameter search speed; mathematically identical to
        calling predict_height row-by-row (same pipeline, same estimator, same clip)."""
        pipe, est, _ = self.sub[horizon_days]
        X, _ = pipe.transform(rows)
        pred = est.predict(X)
        return np.clip(pred, HEIGHT_CLIP[0], HEIGHT_CLIP[1])

    def feature_names(self, horizon_days=7):
        pipe = self.sub[horizon_days][0]
        _, names = pipe.transform([{c: "" for c in self.feature_list}])
        return names


class SklearnDaysUntil30Model:
    """One fitted sklearn regressor on target_days_until_30cm, with right-censored rows CAPPED at
    the 120-day horizon rather than dropped -- see reports/hyperparameters.md for why this simple
    approximation was chosen over dropping censored rows (survivorship bias) or a full survival
    model (Kaplan-Meier / AFT / Cox, explicitly out of scope: "sem inventar uma solução
    complexa"). Every Phase 5 row has either a real value or censored=1 (never neither), so no
    filtering is needed at fit time.

    A prediction > 120 days is itself reported as censored, symmetric with how the training
    target was capped -- so the censoring-agreement bookkeeping stays comparable to the Phase 6
    baselines' own convention."""

    def __init__(self, name, estimator_factory, feature_list, categorical_cols=CATEGORICAL_COLS,
                scale_numeric=False):
        self.name = name
        self.estimator_factory = estimator_factory
        self.feature_list = feature_list
        self.categorical_cols = categorical_cols
        self.scale_numeric = scale_numeric

    def fit(self, train_rows):
        y = np.array([
            DAYS_HORIZON if r["target_days_until_30cm_censored"] == "1" else float(r["target_days_until_30cm"])
            for r in train_rows
        ])
        self.pipe = FeaturePipeline(self.feature_list, self.categorical_cols, self.scale_numeric).fit(train_rows)
        X, _ = self.pipe.transform(train_rows)
        self.est = self.estimator_factory()
        self.est.fit(X, y)
        return self

    def predict(self, row):
        X, _ = self.pipe.transform([row])
        pred = max(0.0, float(self.est.predict(X)[0]))
        if pred > DAYS_HORIZON:
            return None, {"censored": True}
        return pred, {"censored": False}

    def predict_batch(self, rows):
        X, _ = self.pipe.transform(rows)
        pred = np.clip(self.est.predict(X), 0.0, None)
        return pred
