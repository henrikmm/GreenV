"""Phase 10 — the ASGI application: `greenv_vegpred.api.app:app`.

Run locally with:
    cd services/greenv-vegetation-prediction
    uvicorn greenv_vegpred.api.app:app --reload --app-dir src --port 8000

This wires HTTP onto the module Phases 1-9 already built. It does not train, recalibrate, or
reselect anything; see `loader.py` for how the frozen artifacts are (or are not) found, and
`routes.py` / `service.py` for what each endpoint actually does.
"""
from __future__ import annotations

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from .routes import API_VERSION, router

DESCRIPTION = """
Read API for the GreenV vegetation-height forecasting **prototype** (Phases 1-10 of
`docs/VEGETATION_PREDICTION_TASK.md`).

**All vegetation data behind this API is synthetic** (`data_provenance: "synthetic"` on every
forecast). Nothing here has been validated against real field data on the SP-021/Rodoanel Oeste
corridor -- see `reports/model-comparison.md` and `reports/forecast-method.md` for the full
methodology and its measured limitations (interval width, OOD degradation, temporal instability).

- Critical operational threshold: **30 cm**.
- Primary model: the **Random Forest** frozen in Phase 7 (`keep_plus_candidate`, framing B).
- Uncertainty: a **split-conformal** interval, calibrated once on VALIDATION (Phase 9), never
  re-tuned against TEST/OOD.
"""

app = FastAPI(
    title="GreenV Vegetation Prediction API",
    description=DESCRIPTION,
    version=API_VERSION,
)

# Local development CORS only. This API has no authentication and issues no cookies/credentials,
# so `allow_credentials` stays False -- the specific unsafe pattern being avoided is
# `allow_origins=["*"]` combined with `allow_credentials=True`; this configuration avoids both
# halves of that at once by listing explicit local origins and never allowing credentials.
# No production origin is configured here -- there is no production deployment for this
# prototype; a real one would need its own explicit, reviewed origin list, not a copy of this one.
LOCAL_DEV_ORIGINS = [
    "http://localhost:5173", "http://127.0.0.1:5173",   # apps/web's Vite dev server (default port)
    "http://localhost:3000", "http://127.0.0.1:3000",   # a common alternate dev port
]
app.add_middleware(
    CORSMiddleware,
    allow_origins=LOCAL_DEV_ORIGINS,
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)

app.include_router(router)


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    """Belt-and-suspenders: FastAPI/Starlette already return a generic, traceback-free 500 by
    default when `debug=False` (the default, unchanged here) -- this handler exists only to
    GUARANTEE that shape explicitly rather than rely on it implicitly, and to log the real
    exception server-side (never the client-visible body) without ever logging a token,
    credential, or request body field."""
    import logging
    logging.getLogger("greenv_vegpred.api").exception("unhandled error on %s %s", request.method, request.url.path)
    return JSONResponse(status_code=500, content={"detail": "internal error"})


@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException) -> JSONResponse:
    """The default handler already does exactly this; declared explicitly so the safe shape
    (`{"detail": ...}`, no stack trace) is guaranteed rather than assumed."""
    return JSONResponse(status_code=exc.status_code, content={"detail": exc.detail})
