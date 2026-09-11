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
from datetime import date, timedelta

import numpy as np
from sklearn.preprocessing import StandardScaler

CATEGORICAL_COLS = {"operational_status", "vegetation_type"}
HEIGHT_CLIP = (0.0, 150.0)     # physical bounds, Phase 4 synthetic-model.md H_HARDCAP
DAYS_HORIZON = 120.0           # same right-censoring horizon as Phase 5/6


def fnum(x):
    return None if x in (None, "") else float(x)


CRITICAL_HEIGHT_CM = 30.0


def days_event_rows(rows):
    """B2 (Codex R01/R02/R04 remediation) — the only rows a `days_until_30cm` regressor may be
    FIT or SCORED on: the anchor is below the operational threshold (an already-critical anchor
    is a deterministic 0-day/`critical` case, handled once and for all by the forecast layer --
    see `forecast/interval.py::build_days_forecast` -- never a case this regressor should learn
    from or be judged on), and the label is a genuine `event`: the true, exact days-to-crossing
    is known.

    `censored_intervention` / `censored_horizon` / `censored_end_of_followup` rows are NEVER
    included here, and never coerced into a numeric target (in particular, never 120 -- see
    `build_days_until_30cm`'s own docstring in `features/build.py` for why each of those three
    means a different kind of "we don't actually know the time-to-event", not a fourth kind of
    known observation)."""
    return [r for r in rows
           if fnum(r.get("height_cm")) is not None and float(r["height_cm"]) < CRITICAL_HEIGHT_CM
           and r.get("target_days_until_30cm_outcome") == "event"]


def days_outcome_counts(rows):
    """Diagnostic-only counts of the four-way censoring taxonomy, for a population of rows below
    the critical threshold (matches `days_event_rows`'s own height filter, so the four counts here
    sum to the same denominator `days_event_rows` draws its `event` count from). Never used to
    build a numeric target -- reporting only."""
    below = [r for r in rows if fnum(r.get("height_cm")) is not None and float(r["height_cm"]) < CRITICAL_HEIGHT_CM]
    out = {"event": 0, "censored_intervention": 0, "censored_horizon": 0, "censored_end_of_followup": 0}
    for r in below:
        outcome = r.get("target_days_until_30cm_outcome")
        if outcome in out:
            out[outcome] += 1
    out["n_below_30cm"] = len(below)
    return out


def event_date(row):
    """The calendar date a row's own reported crossing actually falls on -- `as_of_date +
    target_days_until_30cm` -- defined only for `outcome == "event"` rows (a censored row has no
    known event date by definition). Shared by `days_event_rows`'s callers and by
    `rolling_origin_days_train_rows` below, so the same date arithmetic is never duplicated."""
    as_of = date.fromisoformat(row["as_of_date"])
    return as_of + timedelta(days=int(float(row["target_days_until_30cm"])))


def rolling_origin_days_train_rows(rows, eval_start):
    """B2.1 (Codex independent review) — the rolling-origin backtest's own analogue of
    `label_observation_cutoff` (features/build.py, R01's fixed-split fix). Partitioning TRAIN by
    `as_of_date <= origin` alone does not stop a row's LABEL from reaching into the eval window
    that same origin is about to be scored on: `target_days_until_30cm` can point up to 120 days
    past its anchor, which can land inside `[eval_start, eval_end]` even for an anchor dated well
    before `origin`. This filters out exactly those rows before a days model is ever fit on them.

    Only rows whose `event_date()` (see above) falls on or after `eval_start` are removed —
    `>=`, not `>`: a crossing exactly on `eval_start` belongs to the window being protected, same
    convention as the fixed-split cutoff. A row that is not a clean `event` is left untouched here
    (it is dropped anyway, downstream, by `days_event_rows` inside `SklearnDaysUntil30Model.fit()`
    — irrelevant to this filter, which only concerns label-window leakage from real event labels).

    Only ever applied to the DAYS model's own training rows — the HEIGHT model keeps using the
    unfiltered row set (Codex-confirmed: real height-target offsets never exceed 28 days, safely
    inside the existing 30-day embargo already used to separate `origin` from `eval_start`)."""
    out = []
    for r in rows:
        if r.get("target_days_until_30cm_outcome") == "event" and event_date(r) >= eval_start:
            continue
        out.append(r)
    return out


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
    """One fitted sklearn regressor on target_days_until_30cm.

    B2 (Codex R01/R02/R04 remediation): fits ONLY on `days_event_rows(train_rows)` -- anchors
    below 30cm whose target_days_until_30cm_outcome is a genuine `event`. The earlier version
    capped every censored row (intervention, horizon, and end-of-followup alike) at the 120-day
    horizon and fit on all of them as if 120 were an observed event time; that conflated three
    different kinds of "we don't know the true time-to-event" into a single fabricated number and
    is exactly what this fix removes -- see reports/target-construction.md. This regressor does
    not model censoring/survival at all now (dropping censored rows, not a Kaplan-Meier/AFT/Cox
    model -- "sem inventar uma solução complexa", per instruction); it is a plain conditional-mean
    regressor over the population where the crossing was actually observed before any
    intervention or follow-up limit. See R03 in reports/target-construction.md for what this does
    and does not let the model claim about `beyond_horizon`.

    A prediction > 120 days is still reported as censored -- the code path is kept (removing it
    would be a contract change), but training labels no longer contain the value 120 itself, so
    this branch is even less likely to fire than before, not a validated capability of this model."""

    def __init__(self, name, estimator_factory, feature_list, categorical_cols=CATEGORICAL_COLS,
                scale_numeric=False):
        self.name = name
        self.estimator_factory = estimator_factory
        self.feature_list = feature_list
        self.categorical_cols = categorical_cols
        self.scale_numeric = scale_numeric

    def fit(self, train_rows):
        train_rows = days_event_rows(train_rows)
        y = np.array([float(r["target_days_until_30cm"]) for r in train_rows])
        self.pipe = FeaturePipeline(self.feature_list, self.categorical_cols, self.scale_numeric).fit(train_rows)
        X, _ = self.pipe.transform(train_rows)
        self.est = self.estimator_factory()
        self.est.fit(X, y)
        self.n_train_event_rows = len(train_rows)
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
