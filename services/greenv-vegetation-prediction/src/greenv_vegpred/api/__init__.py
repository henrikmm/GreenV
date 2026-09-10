"""Phase 10 — the API layer. Exposes the Phase 7-9 module (frozen model + calibrated intervals +
ranking) over local HTTP, using FastAPI. See `app.py` for the ASGI application object.

This package only wires HTTP onto what Phases 1-9 already built: no model is trained,
recalibrated, or reselected here, and no new business logic beyond simple request/response
shaping lives in this package's routes -- see `service.py`.
"""
