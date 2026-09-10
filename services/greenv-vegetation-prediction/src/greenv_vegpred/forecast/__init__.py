"""Phase 9 — the operational forecast object, its individual prediction intervals, and the
urgency ranking built from them.

See interval.py / ranking.py for the actual logic; this package exists so Phase 10's API layer can
`from greenv_vegpred.forecast import build_days_forecast, build_height_forecast, rank_forecasts`
without reaching into a script.
"""
from .interval import build_days_forecast, build_height_forecast, conformal_quantile
from .ranking import rank_forecasts

__all__ = ["build_days_forecast", "build_height_forecast", "conformal_quantile", "rank_forecasts"]
