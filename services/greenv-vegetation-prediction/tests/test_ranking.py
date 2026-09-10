"""Phase 11 — `rank_forecasts`, on a small, hand-built, artificial list. Confirms the exact
frozen ordering rule and that neither `operational_confidence` nor interval width is a hidden
second ranking key.
"""
from __future__ import annotations

from greenv_vegpred.forecast.ranking import rank_forecasts


def fc(trecho_id, status, days_until_critical=None, operational_confidence="medium", interval=None):
    return {
        "trecho_id": trecho_id, "status": status, "days_until_critical": days_until_critical,
        "operational_confidence": operational_confidence, "interval": interval,
    }


class TestRankingOrder:
    def test_critical_before_forecast_before_beyond_horizon_before_insufficient_data(self):
        forecasts = [
            fc("z-insufficient", "insufficient_data"),
            fc("y-beyond", "beyond_horizon"),
            fc("x-forecast", "forecast", days_until_critical=10.0),
            fc("w-critical", "critical", days_until_critical=0.0),
        ]
        ranked = rank_forecasts(forecasts)
        assert [r["trecho_id"] for r in ranked] == ["w-critical", "x-forecast", "y-beyond", "z-insufficient"]
        assert [r["rank"] for r in ranked] == [1, 2, 3, 4]

    def test_forecast_bucket_orders_by_ascending_days_until_critical(self):
        forecasts = [
            fc("far", "forecast", days_until_critical=90.0),
            fc("near", "forecast", days_until_critical=5.0),
            fc("mid", "forecast", days_until_critical=30.0),
        ]
        ranked = rank_forecasts(forecasts)
        assert [r["trecho_id"] for r in ranked] == ["near", "mid", "far"]

    def test_ties_broken_by_ascending_trecho_id(self):
        forecasts = [
            fc("SP-021:norte:005000", "critical", days_until_critical=0.0),
            fc("SP-021:norte:001000", "critical", days_until_critical=0.0),
            fc("SP-021:norte:003000", "critical", days_until_critical=0.0),
        ]
        ranked = rank_forecasts(forecasts)
        assert [r["trecho_id"] for r in ranked] == [
            "SP-021:norte:001000", "SP-021:norte:003000", "SP-021:norte:005000",
        ]

    def test_operational_confidence_never_affects_order(self):
        """Two otherwise-identical forecasts, differing ONLY in operational_confidence, must keep
        the tie-break (trecho_id) order -- operational_confidence is not a ranking key."""
        forecasts = [
            fc("b-trecho", "forecast", days_until_critical=10.0, operational_confidence="low"),
            fc("a-trecho", "forecast", days_until_critical=10.0, operational_confidence="high"),
        ]
        ranked = rank_forecasts(forecasts)
        assert [r["trecho_id"] for r in ranked] == ["a-trecho", "b-trecho"]

    def test_interval_width_never_affects_order(self):
        """Same days_until_critical, wildly different interval widths -- order must depend only
        on trecho_id (the documented tie-break), never on how wide/narrow the interval is."""
        forecasts = [
            fc("b-trecho", "forecast", days_until_critical=10.0,
              interval={"lower_days": 0.0, "upper_days": 100.0, "confidence": 0.9}),
            fc("a-trecho", "forecast", days_until_critical=10.0,
              interval={"lower_days": 9.0, "upper_days": 11.0, "confidence": 0.9}),
        ]
        ranked = rank_forecasts(forecasts)
        assert [r["trecho_id"] for r in ranked] == ["a-trecho", "b-trecho"]

    def test_no_hidden_score_field_is_added(self):
        forecasts = [fc("a", "forecast", days_until_critical=1.0)]
        ranked = rank_forecasts(forecasts)
        assert set(ranked[0].keys()) == {"trecho_id", "status", "days_until_critical",
                                         "operational_confidence", "interval", "rank"}

    def test_input_list_is_not_mutated(self):
        original = [fc("a", "critical", days_until_critical=0.0)]
        original_copy = dict(original[0])
        rank_forecasts(original)
        assert original[0] == original_copy  # no "rank" key was added in place

    def test_beyond_horizon_entries_among_themselves_break_by_trecho_id_only(self):
        forecasts = [fc("z", "beyond_horizon"), fc("a", "beyond_horizon")]
        ranked = rank_forecasts(forecasts)
        assert [r["trecho_id"] for r in ranked] == ["a", "z"]

    def test_empty_list_returns_empty_list(self):
        assert rank_forecasts([]) == []
