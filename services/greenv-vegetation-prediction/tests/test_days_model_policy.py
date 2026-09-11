"""B2 (Codex R01/R02/R04 remediation) — the corrected days_until_30cm training/scoring policy:
`SklearnDaysUntil30Model` must fit and score ONLY on genuine `event` rows below the 30cm
threshold, and must never coerce a censored row's unknown time-to-event into a numeric target
(least of all the value 120). Uses tiny, deterministic, hand-built fixtures — never the real
33k-row CSV or the frozen Random Forest artifact.

B2.1 (Codex independent review, rolling-origin label-window leakage) adds
`TestRollingOriginDaysTrainRows` below, on the same kind of tiny hand-built fixture.
"""
from __future__ import annotations
from datetime import date

import pytest
from sklearn.linear_model import LinearRegression

from greenv_vegpred.evaluate.harness import evaluate_days_until_30cm_model, stratify_days
from greenv_vegpred.features import feature_spec
from greenv_vegpred.models.ml_common import (
    DAYS_HORIZON, SklearnDaysUntil30Model, days_event_rows, days_outcome_counts,
    rolling_origin_days_train_rows,
)


def make_day_row(height_cm, outcome, days_value=None, **overrides):
    """A minimal feature_row-shaped dict with only what SklearnDaysUntil30Model/harness need."""
    row = {
        "height_cm": str(height_cm), "gdd_week_tb15_c": "20.0",
        "target_days_until_30cm": "" if days_value is None else str(days_value),
        "target_days_until_30cm_outcome": outcome,
        "target_days_until_30cm_censored": "0" if outcome == "event" else "1",
    }
    row.update(overrides)
    return row


FEATURES = ["height_cm", "gdd_week_tb15_c"]


def make_model():
    return SklearnDaysUntil30Model(
        name="linreg_test", estimator_factory=LinearRegression, feature_list=FEATURES,
        categorical_cols=set(),
    )


class TestDaysEventRowsFilter:
    def test_event_rows_below_30cm_are_included(self):
        rows = [make_day_row(15.0, "event", 20.0)]
        assert days_event_rows(rows) == rows

    def test_censored_intervention_is_excluded(self):
        rows = [make_day_row(15.0, "censored_intervention")]
        assert days_event_rows(rows) == []

    def test_censored_horizon_is_excluded(self):
        rows = [make_day_row(15.0, "censored_horizon")]
        assert days_event_rows(rows) == []

    def test_censored_end_of_followup_is_excluded(self):
        rows = [make_day_row(15.0, "censored_end_of_followup")]
        assert days_event_rows(rows) == []

    def test_event_at_or_above_30cm_is_excluded(self):
        # Already-critical anchors are the forecast layer's deterministic 0-day/critical case,
        # never a case the regressor should train or be scored on -- even though build.py itself
        # legitimately reports outcome="event" (days=0) for these.
        rows = [make_day_row(30.0, "event", 0.0), make_day_row(35.0, "event", 0.0)]
        assert days_event_rows(rows) == []

    def test_mixed_population_keeps_only_the_eligible_rows(self):
        event_row = make_day_row(15.0, "event", 20.0)
        rows = [
            event_row,
            make_day_row(10.0, "censored_intervention"),
            make_day_row(20.0, "censored_horizon"),
            make_day_row(25.0, "censored_end_of_followup"),
            make_day_row(35.0, "event", 0.0),  # already critical, still excluded
        ]
        assert days_event_rows(rows) == [event_row]


class TestDaysOutcomeCounts:
    def test_counts_all_four_categories_below_30cm_only(self):
        rows = [
            make_day_row(15.0, "event", 20.0),
            make_day_row(10.0, "censored_intervention"),
            make_day_row(20.0, "censored_horizon"),
            make_day_row(25.0, "censored_end_of_followup"),
            make_day_row(35.0, "event", 0.0),  # >=30cm, excluded from the below-30 denominator
        ]
        counts = days_outcome_counts(rows)
        assert counts == {
            "event": 1, "censored_intervention": 1, "censored_horizon": 1,
            "censored_end_of_followup": 1, "n_below_30cm": 4,
        }


class TestSklearnDaysUntil30ModelNeverTrainsOnCensoredRows:
    def test_fit_ignores_censored_intervention_rows(self):
        train = [make_day_row(15.0, "event", 20.0), make_day_row(15.0, "event", 22.0),
                make_day_row(15.0, "event", 18.0), make_day_row(15.0, "event", 21.0),
                make_day_row(15.0, "censored_intervention")]
        model = make_model().fit(train)
        assert model.n_train_event_rows == 4  # the censored row never reached .fit()'s y array

    def test_fit_ignores_censored_horizon_rows_and_never_uses_120_as_a_label(self):
        train = [make_day_row(15.0, "event", 20.0), make_day_row(15.0, "event", 22.0),
                make_day_row(15.0, "event", 18.0), make_day_row(15.0, "event", 21.0),
                make_day_row(15.0, "censored_horizon")]
        model = make_model().fit(train)
        assert model.n_train_event_rows == 4
        # a LinearRegression fit only on event rows in [18, 22] cannot have memorised 120 as a
        # label -- confirmed structurally via n_train_event_rows above, not by inspecting weights.

    def test_fit_ignores_censored_end_of_followup_rows(self):
        train = [make_day_row(15.0, "event", 20.0), make_day_row(15.0, "event", 22.0),
                make_day_row(15.0, "event", 18.0), make_day_row(15.0, "event", 21.0),
                make_day_row(15.0, "censored_end_of_followup")]
        model = make_model().fit(train)
        assert model.n_train_event_rows == 4

    def test_fit_excludes_already_critical_event_rows(self):
        train = [make_day_row(15.0, "event", 20.0), make_day_row(15.0, "event", 22.0),
                make_day_row(15.0, "event", 18.0), make_day_row(15.0, "event", 21.0),
                make_day_row(35.0, "event", 0.0)]  # already >=30cm
        model = make_model().fit(train)
        assert model.n_train_event_rows == 4


class TestEvaluationNeverScoresCensoredRowsAsKnownTimes:
    def test_evaluate_days_until_30cm_model_scores_only_event_rows(self):
        train = [make_day_row(15.0, "event", 20.0), make_day_row(15.0, "event", 22.0),
                make_day_row(15.0, "event", 18.0), make_day_row(15.0, "event", 21.0)]
        model = make_model().fit(train)
        val = train + [make_day_row(15.0, "censored_intervention"),
                      make_day_row(15.0, "censored_horizon"),
                      make_day_row(15.0, "censored_end_of_followup")]
        dm = evaluate_days_until_30cm_model(model, val)
        assert dm["n_event_rows"] == 4
        assert dm["n_censored_any"] == 3
        assert dm["outcome_counts_below_30cm"]["censored_intervention"] == 1
        assert dm["outcome_counts_below_30cm"]["censored_horizon"] == 1
        assert dm["outcome_counts_below_30cm"]["censored_end_of_followup"] == 1

    def test_stratify_days_ignores_censored_rows(self):
        train = [make_day_row(15.0, "event", 20.0, nivel_observed="2") for _ in range(40)]
        model = make_model().fit(train)
        val = train + [make_day_row(15.0, "censored_horizon", nivel_observed="2")] * 40
        out = stratify_days(model, val, lambda r: r["nivel_observed"], min_n=30)
        assert out["2"]["n"] == 40  # only the event rows, not the 40 censored ones alongside them


class TestPredictBeyondHorizonPathStillExists:
    """R03: the >120 -> censored path in .predict() is kept (a contract behaviour), even though
    training labels no longer contain 120 itself."""

    def test_a_prediction_above_horizon_is_still_reported_as_censored(self, monkeypatch):
        model = make_model().fit([make_day_row(15.0, "event", 20.0), make_day_row(15.0, "event", 22.0),
                                  make_day_row(15.0, "event", 18.0), make_day_row(15.0, "event", 21.0)])
        monkeypatch.setattr(model.est, "predict", lambda X: [150.0])
        pred, meta = model.predict(make_day_row(15.0, "event", 20.0))
        assert pred is None
        assert meta == {"censored": True}


class TestRollingOriginDaysTrainRows:
    """B2.1 (Codex independent review) — the rolling-origin backtest's own label-window cutoff.
    `origin=2025-06-01`, `EMBARGO_DAYS=30` -> `eval_start=2025-07-01` (matching
    `scripts/evaluate_phase8.py`'s own rolling-origin loop), same shape as the Codex-reported
    example (anchor 2025-05-04, target_days=105, event_date=2025-08-17)."""

    EVAL_START = date(2025, 7, 1)

    def test_caso_a_event_during_embargo_is_allowed(self):
        # anchor 2025-05-04, +20d -> event_date 2025-05-24, well before eval_start.
        row = make_day_row(15.0, "event", 20.0, as_of_date="2025-05-04")
        assert rolling_origin_days_train_rows([row], self.EVAL_START) == [row]

    def test_caso_b_event_exactly_at_eval_start_is_excluded(self):
        # anchor 2025-06-01, +30d -> event_date 2025-07-01, exactly eval_start.
        row = make_day_row(15.0, "event", 30.0, as_of_date="2025-06-01")
        assert rolling_origin_days_train_rows([row], self.EVAL_START) == []

    def test_caso_c_event_inside_the_eval_window_is_excluded(self):
        # the Codex-reported example: anchor 2025-05-04, +105d -> event_date 2025-08-17.
        row = make_day_row(15.0, "event", 105.0, as_of_date="2025-05-04")
        assert rolling_origin_days_train_rows([row], self.EVAL_START) == []

    def test_caso_d_event_well_before_eval_start_is_allowed(self):
        row = make_day_row(15.0, "event", 10.0, as_of_date="2025-01-01")
        assert rolling_origin_days_train_rows([row], self.EVAL_START) == [row]

    @pytest.mark.parametrize("outcome", ["censored_intervention", "censored_horizon", "censored_end_of_followup"])
    def test_caso_efg_censored_rows_never_enter_the_fitted_population(self, outcome):
        # rolling_origin_days_train_rows() itself only filters on event_date -- a censored row has
        # none, so it passes through untouched here; it is days_event_rows() (called internally by
        # SklearnDaysUntil30Model.fit()) that must still drop it. Confirmed by composing both,
        # exactly as .fit() does.
        row = make_day_row(15.0, outcome, as_of_date="2025-05-04")
        after_origin_filter = rolling_origin_days_train_rows([row], self.EVAL_START)
        assert days_event_rows(after_origin_filter) == []

    def test_mixed_population_keeps_only_the_non_leaking_rows(self):
        a = make_day_row(15.0, "event", 20.0, as_of_date="2025-05-04")   # keep (caso A)
        b = make_day_row(15.0, "event", 30.0, as_of_date="2025-06-01")   # drop (caso B)
        c = make_day_row(15.0, "event", 105.0, as_of_date="2025-05-04")  # drop (caso C)
        d = make_day_row(15.0, "event", 10.0, as_of_date="2025-01-01")   # keep (caso D)
        e = make_day_row(15.0, "censored_intervention", as_of_date="2025-05-04")  # untouched here
        result = rolling_origin_days_train_rows([a, b, c, d, e], self.EVAL_START)
        assert result == [a, d, e]
        assert days_event_rows(result) == [a, d]  # e is dropped once days_event_rows runs


class TestLeakageFieldsStayOutOfFeatures:
    NEW_TARGET_METADATA_FIELDS = {
        "target_days_until_30cm_outcome", "target_days_until_30cm_censoring_time_days",
        "target_days_until_30cm_followup_end_cause", "target_days_until_30cm_censored",
    }

    def test_new_censoring_fields_are_not_in_keep_or_candidate(self):
        combined = set(feature_spec.keep_and_candidate_columns())
        assert self.NEW_TARGET_METADATA_FIELDS.isdisjoint(combined)

    def test_any_future_target_column_is_not_in_keep_or_candidate(self):
        combined = set(feature_spec.keep_and_candidate_columns())
        assert set(feature_spec.TARGET).isdisjoint(combined)

    def test_assert_no_leakage_rejects_the_new_censoring_fields(self):
        for field in self.NEW_TARGET_METADATA_FIELDS - {"target_days_until_30cm_censored"}:
            # these three are METADATA, not TARGET/EXCLUDE_LEAKAGE -- assert_no_leakage only
            # guards TARGET/EXCLUDE_LEAKAGE columns, so confirm they are classified as METADATA
            # instead (the actual guarantee: they are absent from KEEP/CANDIDATE, checked above).
            assert field in feature_spec.METADATA
        with pytest.raises(ValueError):
            feature_spec.assert_no_leakage(feature_spec.KEEP + ["target_days_until_30cm_censored"])
