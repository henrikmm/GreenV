"""Phase 9 — the operational ranking: order trechos by urgency, from a list of already-built
forecast objects (`interval.build_days_forecast` outputs). No new score, learned or otherwise, is
introduced here -- the ordering key is exactly the tuple the phase's own instruction specified,
and nothing else:

  1. `status == "critical"`        (days_until_critical == 0) -- maximum priority.
  2. `status == "forecast"`         ordered by ascending `days_until_critical` (soonest first).
  3. `status == "beyond_horizon"`   after every finite forecast (there IS a number, it's just
                                    None; no way to say how urgent, so it cannot outrank a finite
                                    one, but it is not "no data" either).
  4. `status == "insufficient_data"` last (there is nothing to rank at all).

Ties within a bucket are broken by `trecho_id`, ascending, string comparison -- a deterministic,
documented, content-free tie-break (not a second hidden score).
"""
from __future__ import annotations

_STATUS_ORDER = {"critical": 0, "forecast": 1, "beyond_horizon": 2, "insufficient_data": 3}


def _rank_key(forecast: dict):
    status = forecast.get("status")
    bucket = _STATUS_ORDER.get(status, len(_STATUS_ORDER))  # unknown status sorts last, never crashes
    days = forecast.get("days_until_critical")
    days_key = days if days is not None else float("inf")
    return (bucket, days_key, str(forecast.get("trecho_id")))


def rank_forecasts(forecasts: list[dict]) -> list[dict]:
    """Returns a NEW list, sorted by urgency (see module docstring), each entry with a 1-indexed
    `rank` field added. Does not mutate the input objects (returns shallow copies with `rank`
    inserted first, so the field order reads naturally when serialized)."""
    ordered = sorted(forecasts, key=_rank_key)
    out = []
    for i, fc in enumerate(ordered, start=1):
        out.append({"rank": i, **fc})
    return out
