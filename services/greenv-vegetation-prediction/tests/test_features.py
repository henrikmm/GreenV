"""Phase 11 — feature-spec leakage guarantees (item 7) and roçada-cycle causality in the actual
feature builder (item 8), on tiny, deterministic, hand-built fixtures — never the real generator
or the real weather pull.
"""
from __future__ import annotations
from datetime import date

import pytest

from greenv_vegpred.features import feature_spec
from greenv_vegpred.features.build import build_trecho_rows

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
