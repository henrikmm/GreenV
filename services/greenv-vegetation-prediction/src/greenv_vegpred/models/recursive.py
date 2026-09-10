"""Phase 7 addendum — a one-step recursive height model, closing the direct-vs-recursive
comparison item from TASK.md's original Phase 7 text.

This wraps an ALREADY-FITTED direct 7-day-step height model -- no new training happens here, only
a stepping loop around the existing `models/artifacts/height__random_forest__keep.joblib`
artifact -- and reapplies its 7-day submodel repeatedly to reach +14d (2 steps) and +30d (4 steps,
i.e. 28 days: the nearest multiple of the model's native weekly step to the ±3-day tolerance
Phase 5's own target construction already uses for "+30d").

Covariate handling between steps -- the crux of "how are future covariates treated" -- follows one
explicit rule per column, chosen so real future information is never used:

  * height_cm / height_prev_cm / weekly_growth_cm / days_since_rocada: updated mechanically from
    the model's OWN prior-step prediction. Never the true future height, never true_height_cm.
  * days_since_rocada advances by exactly +7 per step, assuming NO roçada occurs during the
    projected horizon -- the future roçada schedule is exactly the kind of operational information
    this pass was told not to use. This is a documented approximation: a trecho actually roçado
    inside the +14d/+30d window will see its recursive projection diverge from the true
    trajectory (and from the direct per-horizon model, which was trained to already account for
    "some rows get roçada'd mid-window" through its training data, rather than assuming none do).
  * gdd_week_tb15_c / tmin_week_c / rain_30d_mm / water_deficit_30d / operational_status: FROZEN at
    the anchor row's (t0) own values for every projected step -- a "persistence of climate"
    assumption. This mirrors the simplification already documented and used for the Phase 6
    mechanistic baseline's own GDD multiplier (reports/model-comparison.md), so it is not a new
    kind of approximation being introduced here. No real future weather is ever read.

Only the `keep` feature set is used (not `keep_plus_candidate`): extending the same recursive
scheme to height_lag2_cm / rocada_in_new_cycle / dry_season_flag / water_deficit_90d /
vegetation_type would multiply the bookkeeping (a second lag to track, a cycle-start flag that
depends on the never-simulated future roçada, a calendar-derived season flag) without changing the
methodological question being asked -- so it was scoped out of this addendum.
"""
from __future__ import annotations

from .ml_common import fnum

STEPS_FOR_HORIZON = {7: 1, 14: 2, 30: 4}


class RecursiveHeightModel:
    """`step_model` must already be a fitted SklearnHeightModel on the `keep` feature set; only
    its `.sub[7]` (the 7-day-ahead submodel) is used, applied repeatedly."""

    def __init__(self, step_model, name="recursive_random_forest_keep"):
        self.step_model = step_model
        self.name = name

    def fit(self, train_rows):
        # No-op: step_model is already fitted (an existing Phase 7 artifact, reused unchanged).
        # Kept only for protocol parity -- the harness never actually calls .fit() on a model it
        # evaluates, but scripts/train_and_evaluate_ml_addendum.py does, for symmetry with every
        # other model built in this phase.
        return self

    @staticmethod
    def _advance(row, pred_height):
        nxt = dict(row)
        nxt["height_prev_cm"] = row.get("height_cm")
        nxt["height_cm"] = str(pred_height)
        prev = fnum(row.get("height_cm"))
        nxt["weekly_growth_cm"] = "" if prev is None else str(pred_height - prev)
        dsr = fnum(row.get("days_since_rocada")) or 0.0
        nxt["days_since_rocada"] = str(dsr + 7.0)
        # gdd_week_tb15_c / tmin_week_c / rain_30d_mm / water_deficit_30d / operational_status are
        # already carried over unchanged via dict(row) above -- the documented "frozen climate"
        # assumption described in this module's docstring.
        return nxt

    def predict_height(self, row, horizon_days):
        steps = STEPS_FOR_HORIZON[horizon_days]
        cur = row
        pred = None
        for _ in range(steps):
            pred, _ = self.step_model.predict_height(cur, 7)
            cur = self._advance(cur, pred)
        return pred, {"recursive_steps": steps}

    def predict_height_batch(self, rows, horizon_days):
        """Mathematically identical to calling predict_height row-by-row -- same step engine,
        same _advance rule -- but batches every step's `.predict_height_batch` call across all
        rows at once. Doing this row-by-row instead (each step a single-row `.predict()` call
        into a RandomForestRegressor with n_jobs=-1) was measured to spend most of its wall time
        re-spawning joblib's parallel backend per call rather than on actual computation; this is
        a pure performance fix, not a methodology change -- every prediction produced is the same
        number `predict_height` would produce for that row."""
        steps = STEPS_FOR_HORIZON[horizon_days]
        cur_rows = list(rows)
        preds = None
        for _ in range(steps):
            preds = self.step_model.predict_height_batch(cur_rows, 7)
            cur_rows = [self._advance(r, p) for r, p in zip(cur_rows, preds)]
        return preds
