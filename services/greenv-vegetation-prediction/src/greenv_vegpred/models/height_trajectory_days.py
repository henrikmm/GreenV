"""Phase 7 addendum — framing (A) for days_until_30cm: derive the crossing day from an
already-fitted direct height model's own +7d/+14d/+30d predictions, rather than regressing on
target_days_until_30cm directly (framing (B), already implemented in ml_common.py's
SklearnDaysUntil30Model).

No new training happens here -- this wraps an existing height model
(`models/artifacts/height__random_forest__keep_plus_candidate.joblib`), unchanged.

Method, in order of preference:

  1. `height_cm` at the anchor row is already >= 30 cm -> 0 days.
  2. Piecewise-linear interpolation across the four KNOWN points (day 0 = the anchor row's own
     current height; days 7 / 14 / 30 = the height model's own predictions) finds the day inside
     [0, 30] where the interpolated curve first reaches 30 cm. This is the well-supported part of
     framing (A): every point it interpolates between is a real model prediction.
  3. If the curve has not reached 30 cm by day 30, ONE linear extrapolation of the last known
     segment (day 14 -> day 30) projects forward to find the crossing day, capped at the same
     120-day horizon framing (B) uses (`ml_common.DAYS_HORIZON`). This is the most speculative part
     of framing (A) -- a straight line drawn past the only two points that inform it, on a process
     that is known (Phase 4's own logistic/monomolecular growth curves) to decelerate as height
     approaches its ceiling, so this branch will tend to UNDER-estimate the true wait for
     slow-growing trechos. It is only attempted while the day-14→day-30 slope is still positive;
     see `reports/hyperparameters.md` for how often each branch actually fires on VALIDATION.
  4. A non-positive last-segment slope, or an extrapolated day past the 120-day horizon, is
     reported as censored (`predict` returns `(None, {"censored": True, ...})`), mirroring framing
     (B)'s own censoring convention so the two stay comparable.

No real future weather, no future roçada, and no true_height_cm are used anywhere in this file --
only the wrapped height model's own forecasts, which in turn only ever saw KEEP/CANDIDATE features.
"""
from __future__ import annotations

from .ml_common import DAYS_HORIZON, fnum

THRESHOLD_CM = 30.0


class HeightTrajectoryDaysModel:
    """`height_model` must already be a fitted SklearnHeightModel exposing
    `.predict_height(row, horizon_days)` for horizon_days in {7, 14, 30}."""

    def __init__(self, height_model, name="framing_a_from_random_forest_keep_plus_candidate"):
        self.height_model = height_model
        self.name = name

    def fit(self, train_rows):
        return self  # height_model is already fitted; kept for protocol parity only.

    def predict(self, row):
        h0 = fnum(row.get("height_cm"))
        if h0 is None:
            return None, {"censored": True, "method": "missing_anchor_height"}
        if h0 >= THRESHOLD_CM:
            return 0.0, {"censored": False, "method": "already_above_threshold"}

        h7, _ = self.height_model.predict_height(row, 7)
        h14, _ = self.height_model.predict_height(row, 14)
        h30, _ = self.height_model.predict_height(row, 30)
        pts = [(0.0, h0), (7.0, h7), (14.0, h14), (30.0, h30)]

        for (d0, v0), (d1, v1) in zip(pts, pts[1:]):
            if v0 < THRESHOLD_CM <= v1:
                frac = (THRESHOLD_CM - v0) / (v1 - v0) if v1 != v0 else 0.0
                return round(d0 + frac * (d1 - d0), 2), {"censored": False, "method": "interpolated"}

        (d0, v0), (d1, v1) = pts[-2], pts[-1]
        slope = (v1 - v0) / (d1 - d0)
        if slope <= 0:
            return None, {"censored": True, "method": "non_increasing_tail"}
        day = d1 + (THRESHOLD_CM - v1) / slope
        if day > DAYS_HORIZON:
            return None, {"censored": True, "method": "extrapolated_beyond_horizon"}
        return round(day, 2), {"censored": False, "method": "extrapolated"}

    @staticmethod
    def _cross(h0, h7, h14, h30):
        """The same four-point interpolation/extrapolation rule as `predict`, pure Python, no ML
        calls -- used by `predict_batch` once h0/h7/h14/h30 have already been computed in bulk."""
        if h0 >= THRESHOLD_CM:
            return 0.0, "already_above_threshold"
        pts = [(0.0, h0), (7.0, h7), (14.0, h14), (30.0, h30)]
        for (d0, v0), (d1, v1) in zip(pts, pts[1:]):
            if v0 < THRESHOLD_CM <= v1:
                frac = (THRESHOLD_CM - v0) / (v1 - v0) if v1 != v0 else 0.0
                return round(d0 + frac * (d1 - d0), 2), "interpolated"
        (d0, v0), (d1, v1) = pts[-2], pts[-1]
        slope = (v1 - v0) / (d1 - d0)
        if slope <= 0:
            return None, "non_increasing_tail"
        day = d1 + (THRESHOLD_CM - v1) / slope
        if day > DAYS_HORIZON:
            return None, "extrapolated_beyond_horizon"
        return round(day, 2), "extrapolated"

    def predict_batch(self, rows):
        """Mathematically identical to calling `predict` row-by-row, but computes h7/h14/h30 for
        every row with THREE vectorised `.predict_height_batch` calls instead of 3*N single-row
        calls -- the same performance fix applied in `recursive.py`, for the same reason (avoids
        re-spawning the wrapped RandomForestRegressor's n_jobs=-1 backend thousands of times).
        Returns a list of (pred_days_or_None, meta) tuples, same shape as `predict`."""
        h0s = [fnum(r.get("height_cm")) for r in rows]
        h7s = self.height_model.predict_height_batch(rows, 7)
        h14s = self.height_model.predict_height_batch(rows, 14)
        h30s = self.height_model.predict_height_batch(rows, 30)
        out = []
        for h0, h7, h14, h30 in zip(h0s, h7s, h14s, h30s):
            if h0 is None:
                out.append((None, {"censored": True, "method": "missing_anchor_height"}))
                continue
            day, method = self._cross(h0, h7, h14, h30)
            out.append((day, {"censored": day is None, "method": method}))
        return out
