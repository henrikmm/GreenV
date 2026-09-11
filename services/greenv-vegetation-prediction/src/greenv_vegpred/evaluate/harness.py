"""Phase 6 — shared evaluation harness.

Extracted, unchanged in logic, from the inline code scripts/evaluate_baselines.py had before
this pass (same mae/rmse/r2 formulas, same days-until-30cm scoring and censoring rules, same
stratification buckets) so that:
  * the three pre-existing baselines (persistence, last_growth, seasonal_climatology) and
    naive_days_until_30cm produce EXACTLY the same numbers as before this refactor;
  * Phase 7 (ML models) and Phase 8 (the full comparison + backtesting + ablation) can reuse
    the same functions instead of re-implementing metrics.

Model protocol expected by this harness (duck-typed, not enforced by inheritance):
    height model:  .fit(train_rows) -> self ;  .predict_height(row, horizon_days) -> (value, meta)
    days model:    .fit(train_rows) -> self ;  .predict(row) -> (value_or_None, meta)
`meta` is a dict that MAY carry "used_fallback" / "censored" / other flags; never required.
"""
from __future__ import annotations
from collections import defaultdict

import numpy as np

from ..models.ml_common import days_event_rows, days_outcome_counts


def fnum(x):
    return None if x in (None, "") else float(x)


# --------------------------------------------------------------------- core metrics (height)
def mae(y, p):
    y, p = np.asarray(y, float), np.asarray(p, float)
    return float(np.mean(np.abs(y - p)))


def rmse(y, p):
    y, p = np.asarray(y, float), np.asarray(p, float)
    return float(np.sqrt(np.mean((y - p) ** 2)))


def r2(y, p):
    y, p = np.asarray(y, float), np.asarray(p, float)
    ss_res = np.sum((y - p) ** 2)
    ss_tot = np.sum((y - np.mean(y)) ** 2)
    return float(1 - ss_res / ss_tot) if ss_tot > 0 else float("nan")


def height_metrics(pairs):
    """pairs: list of (true_cm, pred_cm). Returns MAE/RMSE (primary) and R2 (auxiliary)."""
    if len(pairs) < 5:
        return {"n": len(pairs), "mae": None, "rmse": None, "r2": None}
    y = [a for a, _ in pairs]
    p = [b for _, b in pairs]
    return {"n": len(pairs), "mae": round(mae(y, p), 3), "rmse": round(rmse(y, p), 3), "r2": round(r2(y, p), 3)}


def evaluate_height_model(model, val_rows, horizon_days: int, target_key: str):
    """Evaluate one fitted height model on one horizon. Returns the height_metrics dict plus
    n_target_missing_excluded and n_used_fallback. Rows whose target is NULL are excluded (a
    real capture gap, never imputed) -- counted, not hidden."""
    usable = [r for r in val_rows if fnum(r[target_key]) is not None]
    n_missing = len(val_rows) - len(usable)
    pairs, fallback_n = [], 0
    for r in usable:
        pred, meta = model.predict_height(r, horizon_days)
        pairs.append((float(r[target_key]), pred))
        if meta.get("used_fallback"):
            fallback_n += 1
    m = height_metrics(pairs)
    m["n_target_missing_excluded"] = n_missing
    m["n_used_fallback"] = fallback_n
    return m


# --------------------------------------------------------------- core metrics (days_until_30cm)
def days_metrics(pairs):
    """pairs: list of (true_days, pred_days), true never censored, pred never None here."""
    if len(pairs) < 5:
        return {"n": len(pairs), "mae_days": None, "median_abs_error_days": None,
                "within_3d_pct": None, "within_7d_pct": None}
    err = [abs(t - p) for t, p in pairs]
    return {
        "n": len(pairs),
        "mae_days": round(float(np.mean(err)), 2),
        "median_abs_error_days": round(float(np.median(err)), 2),
        "within_3d_pct": round(100 * float(np.mean([e <= 3 for e in err])), 1),
        "within_7d_pct": round(100 * float(np.mean([e <= 7 for e in err])), 1),
    }


def evaluate_days_until_30cm_model(model, val_rows):
    """Full days-until-30cm evaluation (B2, Codex R01/R02/R04 remediation). MAE/median/±3d/±7d are
    scored ONLY on `days_event_rows(val_rows)` -- height<30 anchors whose
    `target_days_until_30cm_outcome == "event"`, the only population with a known, exact
    time-to-event. `censored_intervention`/`censored_horizon`/`censored_end_of_followup` rows are
    NEVER scored as if their (unknown) event time were known; `outcome_counts_below_30cm` reports
    all four categories as diagnostics, not as ground truth for a numeric metric."""
    event_rows = days_event_rows(val_rows)
    outcome_counts = days_outcome_counts(val_rows)
    censored_any = [r for r in val_rows
                   if fnum(r.get("height_cm")) is not None and float(r["height_cm"]) < 30.0
                   and r.get("target_days_until_30cm_outcome") != "event"]

    pairs, fallback_n, pred_censored_when_event_known = [], 0, 0
    for r in event_rows:
        pred, meta = model.predict(r)
        if meta.get("used_fallback"):
            fallback_n += 1
        if pred is None:
            pred_censored_when_event_known += 1
            continue
        pairs.append((float(r["target_days_until_30cm"]), pred))

    # Diagnostic only: does the model ALSO decline to give a number (predict "censored") on rows
    # whose true outcome was itself some form of censoring? This is a qualitative agreement count,
    # never a numeric comparison against an unknown true time-to-event.
    model_censored_agreement = 0
    for r in censored_any:
        _, meta = model.predict(r)
        if meta.get("censored"):
            model_censored_agreement += 1

    dm = days_metrics(pairs)
    dm["n_event_rows"] = len(event_rows)
    dm["n_scored"] = len(pairs)
    dm["n_model_predicted_censored_but_event_was_known"] = pred_censored_when_event_known
    dm["outcome_counts_below_30cm"] = outcome_counts
    dm["n_censored_any"] = len(censored_any)
    dm["model_also_predicted_censored_on_censored_rows"] = model_censored_agreement
    dm["model_also_predicted_censored_on_censored_rows_pct"] = (
        round(100 * model_censored_agreement / len(censored_any), 1) if censored_any else None)
    dm["n_used_fallback_in_scored_or_censored"] = fallback_n
    return dm


# --------------------------------------------------------------------- stratification buckets
def rocada_bucket(r):
    tau = int(r["days_since_rocada"])
    if tau < 14:
        return "recent(<14d)"
    if tau <= 60:
        return "mid(14-60d)"
    return "long(>60d)"


def season_bucket(r):
    return "dry(Apr-Sep)" if r["dry_season_flag"] == "1" else "wet(Oct-Mar)"


def nivel_bucket(r):
    return f"nivel{r['nivel_observed']}"


def stratify_height(models, val_rows, key_fn, horizon_days=7, target_key="target_height_plus_7d_cm", min_n=30):
    """models: dict {name: fitted_model}. Returns {bucket: {n, <name>_mae, ...}}."""
    buckets = defaultdict(list)
    for r in val_rows:
        v = fnum(r[target_key])
        if v is None:
            continue
        k = key_fn(r)
        if k is None:
            continue
        preds = {name: m.predict_height(r, horizon_days)[0] for name, m in models.items()}
        buckets[k].append((v, preds))
    out = {}
    for k, vv in buckets.items():
        if len(vv) < min_n:
            continue
        y = [x[0] for x in vv]
        row = {"n": len(vv)}
        for name in models:
            row[f"{name}_mae"] = round(mae(y, [x[1][name] for x in vv]), 2)
        out[str(k)] = row
    return out


def stratify_days(model, val_rows, key_fn, min_n=30):
    """B2: stratifies only over `days_event_rows` -- same event-only, height<30 population as
    `evaluate_days_until_30cm_model`, never a censored row treated as a known time-to-event."""
    buckets = defaultdict(list)
    for r in days_event_rows(val_rows):
        v = fnum(r["target_days_until_30cm"])
        if v is None:
            continue
        k = key_fn(r)
        if k is None:
            continue
        pred, _ = model.predict(r)
        if pred is None:
            continue
        buckets[k].append((v, pred))
    out = {}
    for k, vv in buckets.items():
        if len(vv) < min_n:
            continue
        err = [abs(t - p) for t, p in vv]
        out[str(k)] = {"n": len(vv), "mae_days": round(float(np.mean(err)), 2)}
    return out
