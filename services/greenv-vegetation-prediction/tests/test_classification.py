"""Phase 11 — the vegetation_level (Nível 0-3) classification, exactly as currently frozen in
`schema.sql` (`height_observation.nivel`) and confirmed compatible with
`apps/web/src/utils/classification.js` during Phase 10's closure. No new semantics are tested for
here — only the existing, documented rule, at its exact boundaries.
"""
from __future__ import annotations
import pytest

from greenv_vegpred.api.trecho_meta import vegetation_level


class TestVegetationLevelBoundaries:
    def test_not_ready_is_always_zero_even_with_a_real_height(self):
        assert vegetation_level(height_cm=35.0, operational_status="not-ready") == 0

    def test_missing_height_is_zero(self):
        assert vegetation_level(height_cm=None, operational_status="ready") == 0

    def test_height_zero_is_level_1(self):
        assert vegetation_level(height_cm=0.0, operational_status="ready") == 1

    def test_height_just_below_10_is_level_1(self):
        assert vegetation_level(height_cm=9.99, operational_status="ready") == 1

    def test_height_exactly_10_is_level_2_not_1(self):
        # Frozen boundary: the rule is `< 10 -> 1`, so exactly 10 falls through to `<= 30 -> 2`.
        assert vegetation_level(height_cm=10.0, operational_status="ready") == 2

    def test_height_just_below_30_is_level_2(self):
        assert vegetation_level(height_cm=29.99, operational_status="ready") == 2

    def test_height_exactly_30_is_level_2_not_3(self):
        # Frozen boundary: the rule is `<= 30 -> 2`, so exactly 30 cm is level 2, NOT level 3 --
        # even though the OPERATIONAL forecast threshold (build_days_forecast's `critical_height_cm`)
        # treats height >= 30 as "critical". These are two independent, individually-frozen rules
        # that both happen to use 30 as a boundary but disagree at the exact value -- documented
        # here and in reports/tests.md, not "fixed", since neither rule is a bug on its own.
        assert vegetation_level(height_cm=30.0, operational_status="ready") == 2

    def test_height_just_above_30_is_level_3(self):
        assert vegetation_level(height_cm=30.01, operational_status="ready") == 3

    def test_tall_height_is_level_3(self):
        assert vegetation_level(height_cm=100.0, operational_status="ready") == 3

    @pytest.mark.parametrize("operational_status", ["ready", None, "unknown", ""])
    def test_only_not_ready_literally_triggers_the_override(self, operational_status):
        # Any other operational_status string (including None/unknown) falls through to the
        # height-based rule -- only the literal string "not-ready" overrides it.
        assert vegetation_level(height_cm=50.0, operational_status=operational_status) == 3
