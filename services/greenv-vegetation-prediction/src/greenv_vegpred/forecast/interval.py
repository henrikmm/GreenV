"""Phase 9 — individual prediction intervals (split conformal) and the operational forecast
object for "em quantos dias este trecho deve atingir 30 cm?".

SEMANTICS of `days_until_critical` — this is the answer this module gives an operational meaning
to, and the meaning is deliberately narrow: the number of days, counted from the row's own
`as_of_date`, until the FROZEN Random Forest model (Phase 7's `keep_plus_candidate` framing-B
direct regressor, unchanged since Phase 7) expects the trecho's height to cross the 30 cm
operational threshold, ASSUMING NO FUTURE ROÇADA OR OTHER UNKNOWN INTERVENTION resets its growth
in the meantime. A roçada this row's own model was never told about -- because it hasn't happened
yet, and no future roçada is ever used as a feature (feature_spec.py) -- will invalidate this
projection retroactively. That is a structural limitation of forecasting from a single anchor
observation, stated here rather than hidden: **the projection is conditional on "business as
usual", not a claim about what will actually happen if the trecho is cut again.**

Status values a caller must handle, in order of precedence:
  "critical"           current height already >= 30 cm -> days_until_critical = 0, deterministic,
                       no model call, no interval needed (both bounds trivially 0).
  "insufficient_data"   the row is missing its own anchor `height_cm`. Every OTHER KEEP/CANDIDATE
                       feature already has a documented, defensible imputation rule from Phase 7
                       (ml_common.FeaturePipeline, fit on TRAIN only) -- but height_cm is the one
                       number this entire prediction is anchored on, so imputing it would invent
                       the forecast's own premise rather than approximate a secondary input.
  "beyond_horizon"      the frozen model's own point estimate already exceeds the 120-day horizon
                       (SklearnDaysUntil30Model.predict returns None in that case) -- reported as
                       censored, never replaced by an invented number.
  "forecast"            a finite point estimate and interval exist, both clamped into [0, 120].

Conformal quantile and interval construction: see `conformal_quantile` and `build_days_forecast`.
Calibration (which VALIDATION residuals feed `q_by_confidence`) is computed once, offline, by
`scripts/calibrate_and_evaluate_intervals.py`, and frozen into
`data/models/interval_calibration.json` BEFORE that script ever reads a TEST or OOD row -- this
module itself takes the calibration numbers as a plain argument and does not compute them.

TWO DIFFERENT "CONFIDENCE" CONCEPTS -- do not conflate them, in code or in a report:
  `interval["confidence"]`   the STATISTICAL nominal coverage level of the conformal band (0.80 or
                             0.90) -- a property of the METHOD, identical for every row evaluated
                             at that level, and it says nothing about any one row's own data
                             quality. Maps to `prediction.interval_nominal_coverage` in schema.sql.
  `operational_confidence`   a per-row OPERATIONAL judgement of how much to trust this row's own
                             source observation, in {"low", "medium", "high"} -- derived from
                             `operational_status`/`blockers`, not from the interval width or the
                             nominal level. Maps to `prediction.confidence` in schema.sql, whose
                             own CHECK constraint already enforces the rule implemented below:
                             `operational_status <> 'not-ready' OR confidence = 'low'`.
A `not-ready` source can still carry a wide-but-real 90% conformal interval (interval["confidence"]
= 0.90, unchanged) while its `operational_confidence` is forced to "low" -- these two numbers are
allowed to disagree, on purpose; that disagreement IS the information a consumer needs.
"""
from __future__ import annotations
import json as _json
import math

import numpy as np

from ..models.ml_common import DAYS_HORIZON, HEIGHT_CLIP, fnum

CRITICAL_HEIGHT_CM = 30.0


def _operational_confidence(operational_status: str, status: str) -> str:
    """low/medium/high, matching schema.sql's `prediction.confidence` CHECK constraint exactly:
    a not-ready source is ALWAYS "low", regardless of how good the model's own interval looks.
    Otherwise: "high" only for the deterministic `critical` case (no model uncertainty is even
    involved -- the anchor observation alone already answers the question); "medium" for every
    real model-based forecast from a ready source. This is a small, fixed, documented rule, not a
    learned or opaque score."""
    if operational_status == "not-ready":
        return "low"
    if status == "critical":
        return "high"
    return "medium"


def _parse_blockers(raw):
    """`blockers` on the source row is a JSON array string (e.g. '["depth-degraded"]', or '[]'
    when there are none). Returns the parsed list, or None when empty/absent/unparseable -- never
    an empty list, so a consumer can test `if blockers:` instead of length-checking."""
    if not raw:
        return None
    try:
        parsed = _json.loads(raw)
    except (TypeError, ValueError):
        return None
    return parsed or None


def conformal_quantile(residuals, alpha: float) -> float:
    """Split-conformal quantile: the ceil((n+1)(1-alpha))-th smallest absolute residual among
    `residuals` (a finite calibration sample, drawn once from VALIDATION, never touched by model
    fitting and never touched by TEST/OOD). This is the standard split-conformal-regression
    formula (Vovk et al. 2005; Lei et al. 2018). It TARGETS a marginal (1-alpha) coverage for
    [point - q, point + q] under exchangeability between the calibration residuals and the
    test-time residual -- but that assumption is only approximately satisfied here (VALIDATION
    also informed the Phase 7 model-selection decision, and the data has temporal structure with
    measured drift; see reports/forecast-method.md's callout). Treat this as a simple,
    reproducible, well-understood BAND CONSTRUCTION, and treat the actually-measured TEST coverage
    (also in that report) as the number to trust -- not a from-scratch invented method, but also
    not a distribution-free proof for this dataset.

    Returns +inf (an honestly unusable interval, not a falsely narrow one) if the calibration
    sample is too small to support the requested confidence level."""
    residuals = np.sort(np.abs(np.asarray(residuals, dtype=float)))
    n = len(residuals)
    if n == 0:
        return float("inf")
    k = math.ceil((n + 1) * (1 - alpha))
    if k > n:
        return float("inf")
    return float(residuals[k - 1])


def build_days_forecast(row, days_model, q_by_confidence, model_name, confidence=0.90):
    """`q_by_confidence`: {confidence_level: q_hat_days}, e.g. {0.80: 5.1, 0.90: 7.8}, frozen from
    VALIDATION-only calibration (see module docstring). `days_model` is the unmodified, already
    -fitted Phase 7 SklearnDaysUntil30Model artifact (framing B). Returns one forecast object,
    shaped exactly as Phase 9 specified it, at the single requested `confidence` level -- call
    twice (0.80 and 0.90) for both."""
    h0 = fnum(row.get("height_cm"))
    operational_status = row.get("operational_status")
    blockers = _parse_blockers(row.get("blockers"))
    base = {
        "trecho_id": row.get("trecho_id"),
        "as_of_date": row.get("as_of_date"),
        "current_height_cm": h0,
        "critical_height_cm": CRITICAL_HEIGHT_CM,
        "operational_status": operational_status,
        "blockers": blockers,
        "model": model_name,
        "data_provenance": row.get("provenance") or "synthetic",
    }

    if h0 is None:
        return {**base, "days_until_critical": None, "interval": None,
               "status": "insufficient_data", "reason": "missing_anchor_height_cm",
               "operational_confidence": "low"}  # missing data is never "medium" or "high"

    if h0 >= CRITICAL_HEIGHT_CM:
        return {**base, "days_until_critical": 0.0,
               "interval": {"lower_days": 0.0, "upper_days": 0.0, "confidence": confidence},
               "status": "critical", "reason": "already_at_or_above_threshold",
               "operational_confidence": _operational_confidence(operational_status, "critical")}

    point, _meta = days_model.predict(row)
    if point is None:
        return {**base, "days_until_critical": None, "interval": None,
               "status": "beyond_horizon", "reason": "no_defensible_crossing_within_120_day_horizon",
               "operational_confidence": _operational_confidence(operational_status, "beyond_horizon")}

    q = q_by_confidence[confidence]
    if not math.isfinite(q):
        # calibration sample was too small for this confidence level -- say so, don't fake a width.
        return {**base, "days_until_critical": round(point, 1), "interval": None,
               "status": "insufficient_data", "reason": "calibration_sample_too_small_for_confidence",
               "operational_confidence": "low"}

    lower = max(0.0, point - q)
    upper_raw = point + q
    upper = min(DAYS_HORIZON, upper_raw)
    # A not-ready source's point estimate/interval is KEPT (the frozen model and its VALIDATION
    # calibration already saw not-ready rows mixed into both fit and calibration -- see
    # reports/forecast-method.md for the measured ready/not-ready split of the calibration
    # population), not suppressed: suppressing it would be a new, undiscussed behaviour change.
    # What changes is `operational_confidence`, forced to "low" below, independent of the
    # statistical `interval["confidence"]`, which stays whatever nominal level was requested.
    return {**base, "days_until_critical": round(point, 1),
           "interval": {"lower_days": round(lower, 1), "upper_days": round(upper, 1),
                       "confidence": confidence, "upper_is_horizon_bound": bool(upper_raw > DAYS_HORIZON)},
           "status": "forecast", "reason": None,
           "operational_confidence": _operational_confidence(operational_status, "forecast")}


def build_height_forecast(row, height_model, q_by_horizon_confidence, horizon_days, confidence=0.90):
    """Secondary structure (TASK.md's original Phase 9 text also names +7/+14/+30 heights as part
    of the operational output). Same split-conformal philosophy as `build_days_forecast`, applied
    to the frozen height model's own residuals at one horizon. `q_by_horizon_confidence`:
    {(horizon_days, confidence): q_hat_cm}."""
    point, _meta = height_model.predict_height(row, horizon_days)
    q = q_by_horizon_confidence[(horizon_days, confidence)]
    if not math.isfinite(q):
        return {"horizon_days": horizon_days, "point_estimate_cm": round(point, 2),
               "lower_cm": None, "upper_cm": None, "confidence": confidence,
               "status": "insufficient_data"}
    lower = max(HEIGHT_CLIP[0], point - q)
    upper = min(HEIGHT_CLIP[1], point + q)
    return {"horizon_days": horizon_days, "point_estimate_cm": round(point, 2),
           "lower_cm": round(lower, 2), "upper_cm": round(upper, 2), "confidence": confidence,
           "status": "forecast"}
