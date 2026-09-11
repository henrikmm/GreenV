"""Phase 11 — feature-spec leakage guarantees (item 7) and roçada-cycle causality in the actual
feature builder (item 8), on tiny, deterministic, hand-built fixtures — never the real generator
or the real weather pull.

Pre-push remediation (Codex independent review, R01/R02/R04) adds `TestDaysUntil30cmIntervention`
and `TestDaysUntil30cmTemporalIsolation` below, on the same kind of tiny hand-built fixture.
"""
from __future__ import annotations
from datetime import date, timedelta

import pytest

from greenv_vegpred.features import feature_spec
from greenv_vegpred.features.build import (
    TEST_START, TRAIN_END, VALID_END, VALID_START, build_trecho_rows,
)

FORBIDDEN = {
    "true_height_cm", "nivel_true", "generator_seed", "generator_params_hash",
    "generator_version", "provenance", "dataset", "scenario", "data_source",
}


class TestNoLeakageInFrozenFeatureLists:
    """This is the test the phase asked to be future-proof: it must fail the moment anyone adds
    a leakage column to KEEP or CANDIDATE, without needing to know in advance which one."""

    def test_forbidden_columns_are_not_in_keep(self):
        assert FORBIDDEN.isdisjoint(feature_spec.KEEP)

    def test_forbidden_columns_are_not_in_candidate(self):
        assert FORBIDDEN.isdisjoint(feature_spec.CANDIDATE)

    def test_forbidden_columns_are_not_in_keep_and_candidate_columns(self):
        assert FORBIDDEN.isdisjoint(feature_spec.keep_and_candidate_columns())

    def test_no_target_column_in_keep_or_candidate(self):
        targets = set(feature_spec.TARGET)
        assert targets.isdisjoint(feature_spec.KEEP)
        assert targets.isdisjoint(feature_spec.CANDIDATE)

    def test_forbidden_columns_are_all_classified_as_exclude_leakage(self):
        # Every name this test forbids must actually live in EXCLUDE_LEAKAGE -- if a name were
        # forbidden here but NOT in that list, the classification module and this test would have
        # silently drifted apart.
        assert FORBIDDEN <= set(feature_spec.EXCLUDE_LEAKAGE)

    def test_assert_no_leakage_raises_on_a_planted_leakage_column(self):
        with pytest.raises(ValueError):
            feature_spec.assert_no_leakage(feature_spec.KEEP + ["true_height_cm"])

    def test_assert_no_leakage_accepts_the_frozen_keep_list(self):
        feature_spec.assert_no_leakage(feature_spec.KEEP)  # must not raise

    def test_keep_plus_candidate_extension_matches_train_and_evaluate_ml(self):
        """`scripts/train_and_evaluate_ml.py` builds `keep_plus_candidate` as
        `feature_spec.KEEP + CANDIDATE_EXTENSION` (5 specific columns, Phase 7). This test pins
        that CANDIDATE_EXTENSION is still a subset of feature_spec.CANDIDATE (not a drifted,
        parallel list) and still passes leakage validation as a combined set."""
        candidate_extension = ["height_lag2_cm", "rocada_in_new_cycle", "dry_season_flag",
                               "water_deficit_90d", "vegetation_type"]
        assert set(candidate_extension) <= set(feature_spec.CANDIDATE)
        extended = feature_spec.KEEP + candidate_extension
        feature_spec.assert_no_leakage(extended)  # must not raise
        assert FORBIDDEN.isdisjoint(extended)


# --------------------------------------------------------------------- roçada cycle causality

def make_obs_row(observation_date, height, iso_week, days_since_rocada, **overrides):
    row = {
        "observation_date": observation_date, "observed_height_cm": str(height),
        "rodovia": "SP-021", "sentido": "norte", "iso_year": "2025", "iso_week": str(iso_week),
        "scenario": "main", "data_source": "synthetic", "provenance": "synthetic",
        "days_since_rocada": str(days_since_rocada),
        "gdd_week_tb10_c": "30.0", "gdd_week_tb15_c": "20.0", "gdd_week_tb17_c": "15.0",
        "tmin_week_c": "15.0", "tmean_week_c": "20.0", "tmax_week_c": "25.0",
        "rain_7d_mm": "5.0", "rain_14d_mm": "10.0", "rain_30d_mm": "20.0",
        "shortwave_7d_mj_m2": "100.0", "et0_7d_mm": "20.0", "water_deficit_30d": "0.0",
        "week_of_year": str(iso_week), "season_sin": "0.5", "season_cos": "0.5",
        "dry_season_flag": "0", "vegetation_type": "braquiaria",
        "km_mid": "0.25", "centroid_lat": "-23.5", "centroid_lon": "-46.8",
        "grid_cell_id": "open-meteo:era5_seamless:-23.5:-46.8",
        "operational_status": "ready", "blockers": "[]",
        "cell_coverage": "1.0", "frame_count": "10", "nivel_observed": "1",
        "week_days_present": "7",
        "true_height_cm": str(height), "nivel_true": "1",
        "generator_version": "v1-test", "generator_seed": "42", "generator_params_hash": "testhash",
    }
    row.update(overrides)
    return row


NO_OP_DEFICIT_90D = lambda as_of_date: 0.0  # noqa: E731 -- tiny, deterministic test stub


@pytest.fixture
def four_week_series_with_one_rocada():
    """4 weekly observations, one roçada between week 2 and week 3 (i.e. the 3rd observation is
    the first of a new cycle). Heights: 5.0 -> 8.0 -> [cut] -> 2.0 -> 6.0."""
    obs = [
        make_obs_row("2025-01-06", 5.0, iso_week=2, days_since_rocada=20),
        make_obs_row("2025-01-13", 8.0, iso_week=3, days_since_rocada=27),
        make_obs_row("2025-01-20", 2.0, iso_week=4, days_since_rocada=5),
        make_obs_row("2025-01-27", 6.0, iso_week=5, days_since_rocada=12),
    ]
    roc_dates = [date(2025, 1, 15)]
    return obs, roc_dates


class TestRocadaCycleCausality:
    def test_first_observation_after_a_cut_resets_lags_and_growth(self, four_week_series_with_one_rocada):
        obs, roc_dates = four_week_series_with_one_rocada
        rows = build_trecho_rows("main", "SP-021:norte:000000", obs, roc_dates,
                                 NO_OP_DEFICIT_90D, {}, is_dev=True)
        by_date = {r["as_of_date"]: r for r in rows}
        row3 = by_date["2025-01-20"]  # the first observation of the new cycle
        assert row3["rocada_in_new_cycle"] == 1
        assert row3["height_prev_cm"] is None
        assert row3["height_lag2_cm"] is None
        assert row3["weekly_growth_cm"] is None

    def test_lag2_does_not_cross_the_rocada_boundary(self, four_week_series_with_one_rocada):
        obs, roc_dates = four_week_series_with_one_rocada
        rows = build_trecho_rows("main", "SP-021:norte:000000", obs, roc_dates,
                                 NO_OP_DEFICIT_90D, {}, is_dev=True)
        by_date = {r["as_of_date"]: r for r in rows}
        row4 = by_date["2025-01-27"]  # second observation of the new cycle: prev is in-cycle,
        assert row4["height_prev_cm"] == 2.0                             # but lag2 would reach
        assert row4["weekly_growth_cm"] == pytest.approx(4.0)             # back across the cut --
        assert row4["height_lag2_cm"] is None                             # must stay None.

    def test_pre_rocada_rows_compute_lags_normally(self, four_week_series_with_one_rocada):
        obs, roc_dates = four_week_series_with_one_rocada
        rows = build_trecho_rows("main", "SP-021:norte:000000", obs, roc_dates,
                                 NO_OP_DEFICIT_90D, {}, is_dev=True)
        by_date = {r["as_of_date"]: r for r in rows}
        row2 = by_date["2025-01-13"]
        assert row2["rocada_in_new_cycle"] == 0
        assert row2["height_prev_cm"] == 5.0
        assert row2["weekly_growth_cm"] == pytest.approx(3.0)

    def test_days_since_rocada_is_passed_through_from_the_source_observation(self, four_week_series_with_one_rocada):
        # build_trecho_rows does not recompute days_since_rocada itself -- it trusts the upstream
        # (Phase 4 synthetic / Phase 3 real pipeline) value, which is itself only ever computed
        # from past rocada_events. This test pins the passthrough, not a recomputation.
        obs, roc_dates = four_week_series_with_one_rocada
        rows = build_trecho_rows("main", "SP-021:norte:000000", obs, roc_dates,
                                 NO_OP_DEFICIT_90D, {}, is_dev=True)
        by_date = {r["as_of_date"]: r for r in rows}
        assert by_date["2025-01-20"]["days_since_rocada"] == 5
        assert by_date["2025-01-27"]["days_since_rocada"] == 12

    def test_a_future_rocada_event_never_affects_any_built_row(self, four_week_series_with_one_rocada):
        """The core causality guarantee: adding a roçada event dated AFTER every observation in
        this series must produce byte-for-byte identical rows to not adding it at all."""
        obs, roc_dates = four_week_series_with_one_rocada
        rows_without_future = build_trecho_rows("main", "SP-021:norte:000000", obs, roc_dates,
                                                NO_OP_DEFICIT_90D, {}, is_dev=True)
        rows_with_future = build_trecho_rows(
            "main", "SP-021:norte:000000", obs, roc_dates + [date(2099, 1, 1)],
            NO_OP_DEFICIT_90D, {}, is_dev=True,
        )
        assert rows_without_future == rows_with_future

    def test_true_height_cm_is_only_ever_written_to_the_diagnostic_column(self, four_week_series_with_one_rocada):
        obs, roc_dates = four_week_series_with_one_rocada
        rows = build_trecho_rows("main", "SP-021:norte:000000", obs, roc_dates,
                                 NO_OP_DEFICIT_90D, {}, is_dev=True)
        for r in rows:
            assert "true_height_cm" not in r
            assert "true_height_cm_DIAGNOSTIC_ONLY" in r


# ------------------------------------------------- days_until_30cm: R01/R02/R04 remediation

def make_weekly_series(start_date, heights):
    """One observation per week, starting at `start_date`, heights given in order. No roçada by
    default (callers pass their own `roc_dates`)."""
    obs = []
    for w, h in enumerate(heights):
        d = start_date + timedelta(weeks=w)
        obs.append(make_obs_row(d.isoformat(), h, iso_week=(w % 52) + 1, days_since_rocada=w * 7))
    return obs


def build_and_get(obs, roc_dates, as_of_date):
    rows = build_trecho_rows("main", "SP-021:norte:000000", obs, roc_dates,
                             NO_OP_DEFICIT_90D, {}, is_dev=True)
    return {r["as_of_date"]: r for r in rows}[as_of_date]


class TestDaysUntil30cmIntervention:
    """R02 fixtures, exactly as specified: a future roçada must stop the search, never be crossed
    to find a later, causally unrelated post-cut crossing."""

    def test_intervention_before_crossing_is_censored_not_event(self):
        # 20 -> 25 -> [roçada] -> 8 -> 15 -> 31, anchored well inside TRAIN, far from any boundary.
        start = date(2024, 6, 3)
        obs = make_weekly_series(start, [20.0, 25.0, 8.0, 15.0, 31.0])
        roc_dates = [start + timedelta(days=10)]  # between week 1 (day 7) and week 2 (day 14)
        row = build_and_get(obs, roc_dates, start.isoformat())

        assert row["target_days_until_30cm_outcome"] == "censored_intervention"
        assert row["target_days_until_30cm"] is None       # never the post-cut day-28 crossing
        assert row["target_days_until_30cm_censored"] == 1  # legacy field: censored, not event
        assert row["target_days_until_30cm_censoring_time_days"] == 10
        assert row["target_days_until_30cm_followup_end_cause"] is None

    def test_crossing_before_any_rocada_is_a_clean_event(self):
        start = date(2024, 6, 3)
        obs = make_weekly_series(start, [20.0, 25.0, 31.0])  # no roçada at all
        row = build_and_get(obs, [], start.isoformat())

        assert row["target_days_until_30cm_outcome"] == "event"
        assert row["target_days_until_30cm"] == 14.0  # week index 2 = day 14
        assert row["target_days_until_30cm_censored"] == 0
        assert row["target_days_until_30cm_censoring_time_days"] is None
        assert row["target_days_until_30cm_followup_end_cause"] is None

    def test_full_120_days_cleared_without_crossing_or_rocada_is_censored_horizon(self):
        start = date(2024, 6, 3)
        # 19 weekly observations (0, 7, ..., 126 days): the full 120-day horizon is genuinely
        # observed (data continues to day 126), height never crosses, no roçada.
        obs = make_weekly_series(start, [20.0] * 19)
        row = build_and_get(obs, [], start.isoformat())

        assert row["target_days_until_30cm_outcome"] == "censored_horizon"
        assert row["target_days_until_30cm"] is None
        assert row["target_days_until_30cm_censored"] == 1
        assert row["target_days_until_30cm_censoring_time_days"] == 120.0
        assert row["target_days_until_30cm_followup_end_cause"] is None

    def test_series_ending_at_40_days_is_censored_end_of_followup_not_horizon(self):
        start = date(2025, 10, 6)  # inside TEST -- no split-boundary cutoff applies
        # 6 weekly observations (0, 8, 16, 24, 32, 40 days): the series just stops at day 40.
        obs = [make_obs_row((start + timedelta(days=8 * w)).isoformat(), 20.0,
                            iso_week=(w % 52) + 1, days_since_rocada=8 * w) for w in range(6)]
        row = build_and_get(obs, [], start.isoformat())

        assert row["target_days_until_30cm_outcome"] == "censored_end_of_followup"
        assert row["target_days_until_30cm"] is None
        assert row["target_days_until_30cm_censored"] == 1
        assert row["target_days_until_30cm_censoring_time_days"] == 40.0
        assert row["target_days_until_30cm_followup_end_cause"] == "dataset_end"
        # never misreported as "the full 120-day horizon was observed"
        assert row["target_days_until_30cm_censoring_time_days"] != 120.0


class TestDaysUntil30cmInterventionWithoutAFollowingObservation:
    """B1.1 microfix: a known roçada must censor the search even when no observation exists
    between it and `observable_end_ord` -- the roçada is a fact on its own recorded date, it does
    not need a later observation to "confirm" it happened."""

    def test_caso_a_rocada_before_split_cutoff_with_no_observation_in_between(self):
        # anchor = VALID_END; roçada +28d; VALIDATION's own cutoff is +30d (day before
        # TEST_START); the only later observation is +35d, past the cutoff -- so nothing is ever
        # visited on/after the roçada, yet the roçada is still a known fact at +28d.
        start = VALID_END  # 2025-08-31
        rocada_date = start + timedelta(days=28)
        obs = [
            make_obs_row(start.isoformat(), 20.0, iso_week=35, days_since_rocada=0),
            make_obs_row((start + timedelta(days=35)).isoformat(), 8.0, iso_week=40, days_since_rocada=0),
        ]
        row = build_and_get(obs, [rocada_date], start.isoformat())

        assert row["target_days_until_30cm_outcome"] == "censored_intervention"
        assert row["target_days_until_30cm"] is None
        assert row["target_days_until_30cm_censoring_time_days"] == 28.0
        assert row["target_days_until_30cm_followup_end_cause"] is None

    def test_caso_b_rocada_near_the_120_day_horizon_with_no_observation_in_between(self):
        # TEST split (no cutoff); the full 120 days are observable (data continues to +126d);
        # roçada at +118d, no observation between +118d and the next one at +126d (past horizon).
        start = date(2025, 10, 6)
        rocada_date = start + timedelta(days=118)
        obs = [
            make_obs_row(start.isoformat(), 20.0, iso_week=41, days_since_rocada=0),
            make_obs_row((start + timedelta(days=126)).isoformat(), 8.0, iso_week=59, days_since_rocada=0),
        ]
        row = build_and_get(obs, [rocada_date], start.isoformat())

        assert row["split"] == "test"
        assert row["target_days_until_30cm_outcome"] == "censored_intervention"
        assert row["target_days_until_30cm_censoring_time_days"] == 118.0

    def test_caso_c_crossing_strictly_before_a_later_rocada_is_still_a_clean_event(self):
        start = date(2024, 6, 3)
        crossing_date = start + timedelta(days=21)
        rocada_date = start + timedelta(days=28)
        obs = [
            make_obs_row(start.isoformat(), 20.0, iso_week=23, days_since_rocada=0),
            make_obs_row(crossing_date.isoformat(), 31.0, iso_week=26, days_since_rocada=21),
        ]
        row = build_and_get(obs, [rocada_date], start.isoformat())

        assert row["target_days_until_30cm_outcome"] == "event"
        assert row["target_days_until_30cm"] == 21.0

    def test_caso_d_rocada_and_crossing_on_the_same_date_is_censored_intervention(self):
        # Convention preserved: a tie goes to intervention, not event -- the observation dated
        # on/after the roçada is itself already post-cut, so an apparent same-day crossing can
        # never be trusted as a continuation of the pre-cut trajectory.
        start = date(2024, 6, 3)
        tie_date = start + timedelta(days=21)
        obs = [
            make_obs_row(start.isoformat(), 20.0, iso_week=23, days_since_rocada=0),
            make_obs_row(tie_date.isoformat(), 31.0, iso_week=26, days_since_rocada=0),
        ]
        row = build_and_get(obs, [tie_date], start.isoformat())

        assert row["target_days_until_30cm_outcome"] == "censored_intervention"
        assert row["target_days_until_30cm"] is None
        assert row["target_days_until_30cm_censoring_time_days"] == 21.0


class TestDaysUntil30cmTemporalIsolation:
    """R01 fixtures: a TRAIN/VALIDATION label may consume its own embargo, never the next split's
    own dates."""

    def test_train_label_may_land_inside_embargo_1(self):
        # anchor is TRAIN's very last possible date; crossing 20 days later lands inside
        # EMBARGO_1 (2025-01-01 .. 2025-01-30) -- this must still be a clean EVENT.
        start = TRAIN_END  # 2024-12-31
        crossing_date = start + timedelta(days=20)  # 2025-01-20
        # This IS the assertion the fixture depends on: the crossing must genuinely land inside
        # EMBARGO_1, or the test would be proving nothing about the embargo at all.
        assert date(2025, 1, 1) <= crossing_date <= date(2025, 1, 30)
        obs = [
            make_obs_row(start.isoformat(), 20.0, iso_week=1, days_since_rocada=0),
            make_obs_row(crossing_date.isoformat(), 31.0, iso_week=4, days_since_rocada=20),
        ]
        row = build_and_get(obs, [], start.isoformat())

        assert row["split"] == "train"
        assert row["target_days_until_30cm_outcome"] == "event"
        assert row["target_days_until_30cm"] == 20.0

    def test_train_label_must_not_reach_into_validation(self):
        # Same anchor (TRAIN_END); the ONLY crossing is 46 days later, inside VALIDATION
        # (2025-01-31 .. 2025-08-31) -- must NOT be reported as an event.
        start = TRAIN_END  # 2024-12-31
        crossing_date = start + timedelta(days=46)  # 2025-02-15, inside VALIDATION
        assert VALID_START <= crossing_date <= VALID_END
        obs = [
            make_obs_row(start.isoformat(), 20.0, iso_week=1, days_since_rocada=0),
            make_obs_row(crossing_date.isoformat(), 31.0, iso_week=7, days_since_rocada=46),
        ]
        row = build_and_get(obs, [], start.isoformat())

        assert row["split"] == "train"
        assert row["target_days_until_30cm_outcome"] == "censored_end_of_followup"
        assert row["target_days_until_30cm"] is None
        assert row["target_days_until_30cm_followup_end_cause"] == "split_boundary"
        # cutoff is the day before VALID_START -> 30 days after TRAIN_END
        assert row["target_days_until_30cm_censoring_time_days"] == 30.0

    def test_validation_label_may_land_inside_embargo_2(self):
        start = VALID_END  # 2025-08-31
        obs = [
            make_obs_row(start.isoformat(), 20.0, iso_week=35, days_since_rocada=0),
            make_obs_row((start + timedelta(days=15)).isoformat(), 31.0, iso_week=37, days_since_rocada=15),
        ]
        row = build_and_get(obs, [], start.isoformat())

        assert row["split"] == "validation"
        assert row["target_days_until_30cm_outcome"] == "event"
        assert row["target_days_until_30cm"] == 15.0

    def test_validation_label_must_not_reach_into_test(self):
        start = VALID_END  # 2025-08-31
        crossing_date = start + timedelta(days=50)  # 2025-10-20, inside TEST
        assert crossing_date >= TEST_START
        obs = [
            make_obs_row(start.isoformat(), 20.0, iso_week=35, days_since_rocada=0),
            make_obs_row(crossing_date.isoformat(), 31.0, iso_week=42, days_since_rocada=50),
        ]
        row = build_and_get(obs, [], start.isoformat())

        assert row["split"] == "validation"
        assert row["target_days_until_30cm_outcome"] == "censored_end_of_followup"
        assert row["target_days_until_30cm"] is None
        assert row["target_days_until_30cm_followup_end_cause"] == "split_boundary"
        # cutoff is the day before TEST_START -> 30 days after VALID_END
        assert row["target_days_until_30cm_censoring_time_days"] == 30.0

    def test_test_split_gets_the_full_120_day_horizon_when_real_data_supports_it(self):
        start = date(2025, 10, 6)  # inside TEST -- no next split to protect against
        obs = make_weekly_series(start, [20.0] * 19)  # 126 days of real, flat data
        row = build_and_get(obs, [], start.isoformat())

        assert row["split"] == "test"
        assert row["target_days_until_30cm_outcome"] == "censored_horizon"
        assert row["target_days_until_30cm_censoring_time_days"] == 120.0

    def test_test_split_does_not_invent_followup_past_the_real_dataset_end(self):
        start = date(2025, 10, 6)
        obs = [make_obs_row((start + timedelta(days=8 * w)).isoformat(), 20.0,
                            iso_week=(w % 52) + 1, days_since_rocada=8 * w) for w in range(6)]  # 40 days
        row = build_and_get(obs, [], start.isoformat())

        assert row["split"] == "test"
        assert row["target_days_until_30cm_outcome"] == "censored_end_of_followup"
        assert row["target_days_until_30cm_followup_end_cause"] == "dataset_end"
        assert row["target_days_until_30cm_censoring_time_days"] == 40.0


class TestHeightTargetsUnaffectedByTheDaysUntilFix:
    """Section 13's regression check: +7/+14/+30 height targets keep resolving normally across the
    embargo, exactly as before -- this phase's fix touches only target_days_until_30cm."""

    def test_height_targets_resolve_inside_embargo_1_for_a_train_anchor(self):
        start = date(2024, 12, 24)  # TRAIN; +7d -> Dec31 (train), +14d -> Jan7 (embargo),
                                    # +30d -> ~Jan21 (embargo)
        heights = [10.0, 12.0, 14.0, 16.0, 18.0]  # weekly, Dec24, Dec31, Jan7, Jan14, Jan21
        obs = make_weekly_series(start, heights)
        row = build_and_get(obs, [], start.isoformat())

        assert row["split"] == "train"
        assert row["target_height_plus_7d_cm"] == 12.0
        assert row["target_height_plus_14d_cm"] == 14.0
        assert row["target_height_plus_30d_cm"] == 18.0  # nearest grid date within +-3d of +30d
