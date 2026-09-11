# The API (Phase 10) — exposing the Phase 1-9 module over local HTTP

**Phase 10 artifact of [`docs/VEGETATION_PREDICTION_TASK.md`](../../../../docs/VEGETATION_PREDICTION_TASK.md).**
This phase adds **only** an HTTP layer on top of what Phases 1-9 already built: no model was
retrained, no interval was recalibrated, no hyperparameter or feature changed, and no metric from
Phases 6-9 was touched. Everything this API returns is `data_provenance: "synthetic"` — **it is
not a validated field service for the SP-021/Rodoanel Oeste corridor.**

## Architecture

```
        Dashboard (apps/web, future — not modified in this phase)
              │  HTTP (GET/POST, JSON)
              ▼
  GreenV Vegetation Prediction API      (FastAPI, src/greenv_vegpred/api/)
      routes.py  → schemas.py (Pydantic request/response contracts)
              │
              ▼
      service.py → trecho_meta.py       (thin business logic + deterministic trecho
              │                          identity/classification enrichment)
              ▼
      loader.py  → forecast/{interval,ranking}.py    (Phase 9, unchanged)
              │
    ┌─────────┴─────────────────────────────┐
    ▼                                        ▼
Phase 9 snapshot                    Frozen Phase 7 model + Phase 9 calibration
(data/forecast/ranking_current.json)  (models/artifacts/*.joblib, data/models/interval_calibration.json)
    — always used for the read           — only loaded for the on-demand
      endpoints (ranking/list/              POST /predict endpoint; every
      summary/forecast-by-id)                other endpoint never touches it
```

No import from `measurement/`, no RabbitMQ client, no connection to the GreenV Postgres DB, and no
change to `apps/web`, `apps/mobile`, `measurement/`, `infrastructure/`, or `compose.yaml` — the API
is entirely self-contained inside `services/greenv-vegetation-prediction/`.

## Endpoints

| Method | Path | Source of data | Notes |
|---|---|---|---|
| GET | `/health` | `loader.ArtifactStore` flags only | Never runs a prediction. |
| GET | `/api/v1/summary` | Phase 9 snapshot | Counts computed live, never hard-coded. |
| GET | `/api/v1/forecasts/ranking` | Phase 9 snapshot | `?limit=N`, `1 <= N <= 500` (422 otherwise). |
| GET | `/api/v1/forecasts` | Phase 9 snapshot | Optional `?status=` / `?operational_status=` exact-match filters. |
| GET | `/api/v1/forecasts/{trecho_id}` | Phase 9 snapshot | 404 if not in the current snapshot. |
| POST | `/api/v1/forecasts/predict` | Frozen Phase 7 model + Phase 9 calibration | 503 if the model is needed (`height_cm < 30`) and unavailable. |

The scope decision behind this list, checked directly rather than assumed: TASK.md's own original
Phase 10 text names a different, larger endpoint set (`/v1/trechos`, `/v1/trechos.geojson`,
`/v1/trechos/{id}/history`, `POST /v1/rocada-events`, a SQLite-backed store) — this pass implements
the actual, narrower instruction given for this phase instead (a read API over the Phase 9
forecast/ranking objects, plus one on-demand inference endpoint), which is the smaller,
already-scoped, already-built surface. History/`rocada-events`/GeoJSON endpoints were not built —
nothing in Phases 1-9 produces a `trecho` history table or accepts a `rocada` write, so building
those now would mean inventing a second, undiscussed feature rather than exposing an existing one.

## Trecho identity and classification (closed in this pass)

Two fields TASK.md's original text asked for and the first Phase 10 pass left as a `trecho_id`
join were closed by deterministic derivation, verified against the real data first — see
`src/greenv_vegpred/api/trecho_meta.py` for the full reasoning:

- **`rodovia` / `sentido` / `km_start` / `km_end`** — parsed from `trecho_id` itself, whose
  format `schema.sql` already documents verbatim (`'SP-021:norte:000000'
  (rodovia:sentido:km_start_m, zero-padded)`). `km_end` is **not** assumed to be a uniform
  `km_start + 0.5` — checked against all 50,655 rows across `feature_row_dev.csv` and the OOD
  holdout first: 116 of the 118 trechos are exactly 500 m, but the corridor's final ~300 m segment
  in each direction (km 29.0–29.3) is shorter, so `km_end = min(km_start + 0.5, 29.3)` — the
  documented corridor length, not a guess — which reproduces the correct value for every trecho
  checked, including those two.
- **`captured_at`** — the same value as `as_of_date`. This CSV-based pipeline has no timestamp
  finer than a date (unlike `schema.sql`'s `height_observation.observed_at`, a full UTC
  timestamp that nothing upstream of this API actually populates) — so `captured_at` is an
  honest alias, not a fabricated time-of-day.
- **`vegetation_level`** (0-3) — `schema.sql`'s own `height_observation.nivel` GENERATED column
  formula, applied verbatim: `not-ready` or missing height → `0`; `<10cm` → `1`; `10-30cm` → `2`;
  `>30cm` → `3`. Read `apps/web/src/utils/classification.js` (read-only; `apps/web` untouched) to
  check compatibility before adding this — its `LEVELS`/`vegetationLevel()` use the **identical**
  thresholds and the same "missing/invalid → 0, never silently downgraded" rule, so this is one
  shared rule, not two systems that happen to agree by luck. Computed by ONE function
  (`trecho_meta.vegetation_level`), applied once at the API boundary (`service._enrich`) — never
  duplicated between the forecast module and the API.

None of this touched `src/greenv_vegpred/forecast/` (Phase 9, still exactly as checkpointed) —
enrichment happens only in the API's own `service.py`, on the dict already returned by the
snapshot or by `build_days_forecast`.

## Two different "confidence" fields — never merged (see `schemas.py`, `interval.py`)

- **`interval.confidence`** — the statistical nominal coverage level of the conformal band (0.80
  or 0.90). Same for every row at that level; not a data-quality judgement.
- **`operational_confidence`** — `"low"` / `"medium"` / `"high"`, a per-row judgement of the
  *source observation's* trustworthiness (forced to `"low"` whenever `operational_status ==
  "not-ready"`, regardless of the interval).

A response can legitimately show `interval.confidence: 0.9` and `operational_confidence: "low"`
on the same row — that disagreement is exactly the information a consumer needs, not a bug.

## Example responses

`GET /health`
```json
{"status":"ok","service":"greenv-vegetation-prediction","version":"0.1.0",
 "model_available":true,"calibration_available":true,"snapshot_available":true,
 "data_provenance":"synthetic"}
```

`GET /api/v1/summary`
```json
{"total_trechos":118,"critical":61,"forecast":57,"beyond_horizon":0,"insufficient_data":0,
 "critical_height_cm":30.0,"data_provenance":"synthetic"}
```

`GET /api/v1/forecasts/ranking?limit=2` (real output, current pass — now with `rodovia`/`sentido`/
`km_start`/`km_end`/`captured_at`/`vegetation_level`):
```json
[
 {"trecho_id":"SP-021:norte:000000","as_of_date":"2026-08-30","captured_at":"2026-08-30",
  "rodovia":"SP-021","sentido":"norte","km_start":0.0,"km_end":0.5,
  "current_height_cm":65.7,"critical_height_cm":30.0,"vegetation_level":3,
  "operational_status":"ready","blockers":null,"days_until_critical":0.0,
  "interval":{"lower_days":0.0,"upper_days":0.0,"confidence":0.9,"upper_is_horizon_bound":null},
  "status":"critical","reason":"already_at_or_above_threshold","operational_confidence":"high",
  "model":"random_forest__keep_plus_candidate__framing_b","data_provenance":"synthetic","rank":1},
 {"trecho_id":"SP-021:norte:001000","as_of_date":"2026-08-30","captured_at":"2026-08-30",
  "rodovia":"SP-021","sentido":"norte","km_start":1.0,"km_end":1.5,
  "current_height_cm":31.74,"critical_height_cm":30.0,"vegetation_level":0,
  "operational_status":"not-ready","blockers":["depth-degraded"],"days_until_critical":0.0,
  "interval":{"lower_days":0.0,"upper_days":0.0,"confidence":0.9,"upper_is_horizon_bound":null},
  "status":"critical","reason":"already_at_or_above_threshold","operational_confidence":"low",
  "model":"random_forest__keep_plus_candidate__framing_b","data_provenance":"synthetic","rank":2}
]
```
Row 2 shows three independent signals agreeing to disagree, correctly: urgency ranking looks only
at `status`/`days_until_critical` (a `not-ready` source is still ranked by its own state);
`operational_confidence` separately flags it `"low"`; and `vegetation_level` is `0`, **not** `3`
— even though `current_height_cm` (31.74) is above 30 — because `not-ready` overrides the height
threshold in the frozen classification rule (schema.sql's `nivel` formula, §"Trecho identity and
classification" above), the same way it caps `operational_confidence`.

`GET /api/v1/forecasts/{trecho_id}` (existing) → `200`, same shape as one ranking entry.
`GET /api/v1/forecasts/{trecho_id}` (unknown id) → `404 {"detail":"no forecast for trecho_id='...'"}`.

`POST /api/v1/forecasts/predict` (valid, `height_cm < 30`, model present) — **re-run live against
the corrected model (B2), value changed:**
```json
{"trecho_id":"demo:001","current_height_cm":15.96,"days_until_critical":38.0,
 "interval":{"lower_days":6.6,"upper_days":69.5,"confidence":0.9,"upper_is_horizon_bound":false},
 "status":"forecast","operational_confidence":"medium","data_provenance":"synthetic"}
```

`POST /api/v1/forecasts/predict` with an extra field (`"true_height_cm": 999.0`) → `422`:
```json
{"detail":[{"type":"extra_forbidden","loc":["body","true_height_cm"],"msg":"Extra inputs are not permitted"}]}
```

`POST /api/v1/forecasts/predict` with the model artifact absent, `height_cm < 30` → `503`:
```json
{"detail":"inference model unavailable: days_until_30cm__random_forest__keep_plus_candidate.joblib not found. Large model weights are intentionally not committed to git (AGENTS.md's 5MB limit). Regenerate with: python scripts/train_and_evaluate_ml.py"}
```
The same request with `height_cm >= 30` still returns `200` — the deterministic "already
critical" branch never touches the model at all (verified, see §Smoke tests below).

## The Random Forest model is not in git — how the API handles that

`models/artifacts/*random_forest*.joblib` are 13-45 MB each and excluded from git by
`services/greenv-vegetation-prediction/.gitignore` (AGENTS.md's 5 MB commit limit). `loader.py`:

- checks for the file's existence **lazily, once, on first use** (not required for `/health` to
  answer, and not required at all for any snapshot-backed endpoint);
- never downloads it, never trains a substitute;
- reports its absence explicitly (`model_available: false` on `/health`, a clear `503` with a
  regeneration command on `POST /predict` when it is genuinely needed);
- lets every snapshot-backed endpoint (`ranking`, `forecasts`, `forecasts/{id}`, `summary`)
  **keep working with the model completely absent** — verified directly (see below), not assumed.

To regenerate the missing artifact (deterministic, seeded, no network access):
```
cd services/greenv-vegetation-prediction
python scripts/train_and_evaluate_ml.py
```

## Running locally

```
cd services/greenv-vegetation-prediction
pip install fastapi "uvicorn[standard]" pydantic scikit-learn joblib numpy   # or: pip install -e . (uses pyproject.toml)
uvicorn greenv_vegpred.api.app:app --reload --app-dir src --port 8000
```
Then open `http://127.0.0.1:8000/docs` (interactive OpenAPI UI) or run `scripts/demo.http`.

`pyproject.toml` declares the minimal dependency set for this service only — it does not reopen
Phase 0's setup or affect any other service in this repository.

## CORS

Configured for **local development only**: an explicit allow-list of local dev origins
(`http://localhost:5173`/`5173`/`3000`, matching `apps/web`'s likely Vite dev-server ports),
`allow_credentials=False` (this API has no authentication and issues no cookies), and no wildcard
origin. **`allow_origins=["*"]` combined with credentials was the specific unsafe pattern to
avoid — this configuration avoids both halves of it at once.** No production CORS configuration is
declared, because there is no production deployment for this prototype.

## OpenAPI

FastAPI generates `/openapi.json` and the interactive docs at `/docs` (and `/redoc`) automatically
— confirmed live (see smoke tests). A static snapshot, `api/openapi.v1.yaml`, is committed as
TASK.md's originally-named artifact; it is a **generated file**, regenerated from the running
app's own schema (never hand-edited, so it cannot drift into a second, contradicting contract):
```
python -c "import sys,yaml; sys.path.insert(0,'src'); from greenv_vegpred.api.app import app; yaml.safe_dump(app.openapi(), open('api/openapi.v1.yaml','w'), sort_keys=False, allow_unicode=True)"
```

## Smoke tests (this phase — not Phase 11's full suite)

All run manually against a local `uvicorn` instance, in this order:

| Test | Result |
|---|---|
| `GET /health` | `200`, `model_available: true` |
| `GET /api/v1/summary` | `200`, `{"total_trechos": 118, "critical": 61, "forecast": 57, ...}` |
| `GET /api/v1/forecasts/ranking?limit=5` | `200`, 5 entries, `rank` 1-5, all `critical` (soonest urgency) |
| `GET /api/v1/forecasts/SP-021:norte:000000` | `200` |
| `GET /api/v1/forecasts/SP-021:norte:999999` | `404` |
| `GET /api/v1/forecasts?status=forecast` | `200`, 57 rows (matches the summary's own `forecast` count) |
| `GET /api/v1/forecasts/ranking?limit=0` | `422` (`limit` must be `>= 1`) |
| `POST /predict`, valid, `height_cm=15.96 < 30` | `200`, `status: "forecast"` |
| `POST /predict`, valid, `height_cm=35.0 >= 30` | `200`, `status: "critical"` |
| `POST /predict`, extra field `true_height_cm` | `422`, `"extra_forbidden"` |
| `POST /predict`, missing required `height_cm` | `422`, `"missing"` |
| **Model artifact moved away**, `GET /health` | `200`, `model_available: false` |
| **Model artifact absent**, `POST /predict`, `height_cm=5.0 < 30` | **`503`**, clear regeneration message |
| **Model artifact absent**, `POST /predict`, `height_cm=31.0 >= 30` | **`200`** — critical branch never touches the model |
| **Model artifact absent**, `GET /api/v1/forecasts/ranking?limit=2` | **`200`** — snapshot endpoints unaffected |
| Model artifact restored, `GET /health` | `200`, `model_available: true` again |

**Regression round (this closure pass, confirming the contract additions didn't break anything
above and validating the new fields):**

| Test | Result |
|---|---|
| `GET /health` | `200`, unchanged shape |
| `GET /api/v1/summary` | `200`, `{"total_trechos": 118, "critical": 61, "forecast": 57, ...}` — unchanged |
| `GET /api/v1/forecasts/ranking?limit=3` | `200`, every entry now carries `rodovia`, `sentido`, `km_start`, `km_end`, `captured_at`, `vegetation_level` |
| `GET /api/v1/forecasts/SP-021:norte:000000` | `200`, `rodovia:"SP-021"`, `sentido:"norte"`, `km_start:0.0`, `km_end:0.5`, `vegetation_level:3` |
| `GET /api/v1/forecasts/SP-021:norte:999999` | still `404` |
| `POST /predict` with `"generator_seed": 42` | still `422`, `"extra_forbidden"` |
| Grep of a full ranking response for `generator`/`seed` | **no matches** — nothing leaked |
| **Model artifact moved away**, `GET /api/v1/forecasts/ranking?limit=2` | still **`200`** — snapshot + new fields unaffected by the model's absence (`trecho_meta` parsing needs only `trecho_id`, never the model) |
| Model artifact restored | `GET /health` → `model_available: true` again |
| `api/openapi.v1.yaml` regenerated from the updated app | confirmed `vegetation_level` (with its full description) present in the schema |
| `GET /docs`, `GET /openapi.json` | both `200` |

## Limitations (carried forward, not re-litigated)

- All data is synthetic; this API is a local demo/prototype, not a field-validated service.
- The critical threshold is 30 cm, unchanged from every earlier phase.
- The primary model is the Random Forest frozen in Phase 7; intervals are the split-conformal
  band calibrated in Phase 9 (wide — see `reports/forecast-method.md` §5/§9 — by design, not by
  omission here).
- No authentication exists — this is intentional for a local/demo V1, not an oversight, and no
  fake auth was added to look more "production-ready" than it is.
- `apps/web` is not modified or integrated in this phase — the contract above is what a future,
  minimal dashboard integration would consume.

## Pre-push hardening (post-Phase-13, following a read-only security audit)

**Pre-push hardening: non-finite numeric inputs rejected; artifact loading errors sanitized.**
Two gaps found by a defensive pre-push audit were fixed, with no change to any model,
hyperparameter, threshold, feature, or metric:

- `POST /predict`'s `PredictRequest` now sets `allow_inf_nan=False`, so `NaN`/`Infinity`/
  `-Infinity` on any numeric field are rejected with `422` before reaching the model — previously,
  a non-finite `height_cm` reached `RandomForestRegressor.predict()` and raised an unhandled
  `ValueError` (caught only by the outermost 500 handler), and a `NaN` in an unbounded field like
  `tmin_week_c` was silently accepted into a seemingly normal `200` response.
- `loader.py`'s `calibration()`/`snapshot()` now check the artifact's existence up front and, on
  any load failure, report only the artifact's filename — never the raw exception string, which
  for a missing file embeds the full absolute local filesystem path (and, on this machine, the OS
  username). Matches the pattern `days_model()` already used correctly.

Regression tests added in `tests/test_api.py` (`TestPredictRejectsNonFiniteNumbers`,
`TestArtifactErrorsAreSanitized`); full suite still passes (157/157) and `api/openapi.v1.yaml`
stays in sync (no schema shape change — `allow_inf_nan` is a validation behaviour, not a JSON
Schema constraint).
