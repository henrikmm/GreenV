"""Phase 11 — `parse_trecho_id`. Every case here was checked against the real 118-trecho corpus
during Phase 10's closure (50,655 rows across `feature_row_dev.csv` + the OOD holdout) before the
parser was written — these tests fix that already-verified behaviour, not new assumptions.
"""
from __future__ import annotations

from greenv_vegpred.api.trecho_meta import parse_trecho_id


class TestParseTrechoId:
    def test_typical_trecho_norte(self):
        meta = parse_trecho_id("SP-021:norte:000000")
        assert meta == {"rodovia": "SP-021", "sentido": "norte", "km_start": 0.0, "km_end": 0.5}

    def test_typical_trecho_sul_mid_corridor(self):
        meta = parse_trecho_id("SP-021:sul:010500")
        assert meta == {"rodovia": "SP-021", "sentido": "sul", "km_start": 10.5, "km_end": 11.0}

    def test_corridor_end_segment_is_shorter_than_500m(self):
        # The real, verified exception: km 29.0-29.3 (not 29.0-29.5) in both directions.
        meta = parse_trecho_id("SP-021:norte:029000")
        assert meta["km_start"] == 29.0
        assert meta["km_end"] == 29.3

        meta_sul = parse_trecho_id("SP-021:sul:029000")
        assert meta_sul["km_end"] == 29.3

    def test_every_other_segment_is_exactly_500m(self):
        for suffix in ("000500", "001000", "015000", "028500"):
            meta = parse_trecho_id(f"SP-021:norte:{suffix}")
            assert round(meta["km_end"] - meta["km_start"], 3) == 0.5

    def test_none_input_returns_none(self):
        assert parse_trecho_id(None) is None

    def test_empty_string_returns_none(self):
        assert parse_trecho_id("") is None

    def test_malformed_input_returns_none_not_a_guess(self):
        for bad in ("garbage", "SP-021:norte", "SP-021:norte:abc", "SP-021-norte-000000", "SP-021:NORTE:000000"):
            assert parse_trecho_id(bad) is None
