# Integration with GreenV (Phase 12) — the minimal V1 wiring, and the real future path

**Phase 12 artifact of [`docs/VEGETATION_PREDICTION_TASK.md`](../../../../docs/VEGETATION_PREDICTION_TASK.md).**

**Scope note, stated up front:** TASK.md's own original Phase 12 text describes a
**documentation-only** phase — "Document — and stub, **without wiring**" — with "no existing
GreenV file is touched" as a completion criterion. The actual, detailed instruction given for
this pass explicitly asked for the opposite: a real, minimal, demonstrated wiring into
`apps/web`, built and tested locally. That instruction is what this document and this pass's code
changes follow — `apps/web/src/pages/DashboardPage.jsx` **was** touched, on purpose, by explicit
request, and that deviation from the original text's completion criterion is recorded here rather
than silently overridden.

## 1. Analysis before writing any code

### A. Where do the current mocks enter the dashboard?

`App.jsx` fetches two static files once at startup — `/rocada_polygons.geojson` (642 map
polygons: `name`, `centroid_lat/lon`, `area_m2`, `vegetation_level` — a classification value
already baked into the file, not computed at runtime) and `/marco_km.geojson` — and passes the
result down as a `geojson` prop to `DashboardPage` and `MapPage`. `mockOrders.js` seeds
`localStorage` with synthetic ordens de serviço on first load. `mockTrends.js`
(`generateWeeklyProgress`) and `vegetationHistory.js` (`estimateHistoricalCounts`, a
deterministic per-feature hash) fabricate the weekly progress chart and the "how the corridor
looked N weeks ago" time-scrubber — **entirely client-side, with no backend of any kind** behind
either.

### B. Best integration point

`DashboardPage.jsx`. It already renders a grid of `Card`s (KPIs, corridor map, charts,
"pontos críticos" list, team productivity) fed by `useMemo`-derived stats from the same `geojson`
+ `orders` props — a clear, established "one more `Card`" seam, already used four times on that
page for unrelated, independently-loading pieces of information. `MapPage.jsx` was considered and
rejected as the primary seam — see §C/§13.

### C/D. Which API fields map directly, and which need transforming

| API field | Frontend use | Transform needed? |
|---|---|---|
| `vegetation_level` | Feeds `classification.js`'s existing `LEVELS[level]` for label/color | **None** — the exact lookup `apps/web` already uses for its own map polygons |
| `operational_status`, `operational_confidence`, `status`, `data_provenance` | Displayed/branched on directly | **None** |
| `days_until_critical`, `interval.{lower,upper}_days` | Rendered as `"~N dias · faixa M–P dias"` | Formatting only (rounding, a template string) — **not** a recomputation |
| `current_height_cm`, `critical_height_cm` | Displayed with a `cm` suffix | Formatting only |
| `rodovia`, `sentido`, `km_start`, `km_end` | Displayed as `"KM a,b–c,d"` + sentido | Formatting only (`formatKmRange`, a small local helper — `classification.js`'s own `formatKm` takes one value, this needs a range) |
| `captured_at` | Not surfaced in this V1 panel (redundant with the snapshot's own recency for a demo) | — |
| ranking order (the array order itself) | Rendered top-to-bottom, unmodified | **None** — no client-side re-sort |

**No field required recomputing a classification, a ranking, or a censoring/interval rule** — the
one deliberate design goal of this pass (item 4).

### E. Is there a safe correspondence between API trechos and map entities?

**No — checked directly, not assumed.** `rocada_polygons.geojson`'s 642 features carry only
`{name, centroid_lat, centroid_lon, area_m2, vegetation_level}`; there is no `trecho_id`, no
`rodovia`/`sentido`/`km` field of any kind, and their centroids cluster in a small area rather
than sweeping the full 0-29.3 km corridor the prediction API's 118 trechos cover. These are two
independently-generated synthetic geometries with no shared key. See §13 for what map integration
would need instead — no correspondence was invented here.

## 2. V1 architecture implemented

```
apps/web (Dashboard, DashboardPage.jsx)
        │  new Card: <VegetationPredictionPanel />
        ▼
src/services/vegetationPredictionApi.js      -- the ONLY fetch() call site for this backend
        │  GET /api/v1/summary
        │  GET /api/v1/forecasts/ranking?limit=N
        │  GET /api/v1/forecasts/{trecho_id}
        ▼  HTTP, CORS (verified live, see §17)
Vegetation Prediction API  (services/greenv-vegetation-prediction, unchanged)
        ▼
forecast/ranking service → Phase 9 snapshot (data/forecast/ranking_current.json) + frozen model
```

No `fetch()` against this backend exists anywhere else in `apps/web` — every read goes through
`vegetationPredictionApi.js`, per the instruction to not scatter HTTP calls across components.

## 3. Files created/altered

| File | Change |
|---|---|
| `apps/web/src/services/vegetationPredictionApi.js` | **New.** The single API client: `getSummary()`, `getRanking(limit)`, `getForecast(trechoId)`. Centralised base URL, error wrapping, 404→`null` handling. |
| `apps/web/src/components/VegetationPredictionPanel.jsx` | **New.** The one UI component: synthetic-data banner, summary KPIs, ranking list, click-to-load individual forecast detail, loading/error states for both the list and the detail. |
| `apps/web/src/pages/DashboardPage.jsx` | **2 lines changed**: one import, one `<VegetationPredictionPanel />` added as the last `Card` on the page. Nothing else in this file was touched. |
| `apps/web/.env.example` | **New.** `VITE_VEGETATION_PREDICTION_API_URL=http://127.0.0.1:8000`. |

Nothing else under `apps/web` was modified. `apps/mobile`, `measurement/`, `infrastructure/`,
`compose.yaml`, and every other service are untouched (verified via `git status`, see the final
report).

## 4. Configuration

One Vite env var, `VITE_VEGETATION_PREDICTION_API_URL`, read once in
`vegetationPredictionApi.js`, defaulting to `http://127.0.0.1:8000` — matching the prediction
API's own documented local-dev CORS origins (`reports/api.md`) for the Vite dev server's default
port (5173, confirmed in `apps/web/vite.config.js`). `.env.example` documents it; no `.env` file
was created or committed (the root `.gitignore` already excludes `.env`/`.env.local`).

## 5. Not duplicating backend rules

Confirmed by construction, not just by intent: the panel's only per-row "logic" is (a) a lookup
into `classification.js`'s existing `LEVELS` map, keyed by the API's own `vegetation_level`, and
(b) string formatting of numbers already computed by the backend. No days-until-critical formula,
no ranking comparator, and no nivel-threshold logic was written on the frontend — grep confirms
`VegetationPredictionPanel.jsx` contains no comparison against `10`/`30` for classification
purposes, only the pass-through `critical_height_cm` label display.

## 6. Synthetic data visibility

A persistent badge, "**Previsões demonstrativas — dados sintéticos**" (the exact wording asked
for), sits in the panel's header next to the title — visible in every state (loading, error, and
loaded), not just on success. `data_provenance` is never overridden or hidden; nothing in this
panel is presented as a real SP-021 field measurement.

## 7. Loading / error handling

- **Loading:** "Carregando previsão…" replaces the KPI/ranking area while `summary` + `ranking`
  are in flight (`Promise.all`, so both load together); the detail panel shows "Carregando
  detalhe de {id}…" independently while a clicked row's forecast is being fetched.
- **Error (API offline, network failure, non-2xx):** "Previsão temporariamente indisponível." —
  exactly the required message — replaces the panel's data area. **No fallback to a fake
  forecast.** The rest of the dashboard (KPIs, corridor map, charts, orders) is unaffected, since
  it never depended on this panel's data in the first place — the two data sources were already
  independent before this pass.
- A 404 from `GET /forecasts/{id}` is treated as a real, documented answer (`null`), not an error
  path — though in practice it cannot be reached from this panel, since every id offered for
  selection came from the ranking response itself.

## 8. `not-ready` handling

The panel never "corrects" a `not-ready` row. It renders `vegetation_level` and
`operational_confidence` exactly as returned — confirmed live against a real row in the current
snapshot (`SP-021:norte:001000`: `current_height_cm: 31.74`, `operational_status: "not-ready"`,
**`vegetation_level: 0`**, `operational_confidence: "low"`) — and adds a small, explicit
"⚠ not-ready" note next to that row's confidence badge, and a fuller "origem not-ready —
classificação/confiança não são confiáveis" note in the individual-forecast detail view. The
height (31.74 cm, clearly above 30) is still shown as-is; the panel does not infer or display a
"corrected" Nível 3 from it.

## 9. `critical` / `beyond_horizon` / `insufficient_data`

- **`critical`**: rendered as "Já no limite crítico" (list) / the full label "Crítico — já ≥ 30
  cm" (detail) — never a day estimate.
- **`beyond_horizon`**: "Sem cruzamento previsto dentro de 120 dias" — no day count is invented.
- **`insufficient_data`**: "Dados insuficientes para estimar" — the panel is not surprised by a
  `null` `days_until_critical`/`interval`; it branches on `status` first, exactly as the API's own
  documented status precedence (`reports/forecast-method.md`) specifies.

## 10. Wide intervals — not hidden

`describeEstimate()` always renders both the point estimate AND the range together —
`"~27 dias · faixa estimada 0–62 dias"` — never the point estimate alone. There is no code path
that drops the interval to make a number look more certain than it is.

## 11. Map integration

**Not done — by evidence, not by default.** See §1E: the current `rocada_polygons.geojson` has no
`trecho_id`, `rodovia`, `sentido`, or `km` field on any of its 642 features, and its geometry does
not correspond to the prediction API's 118-trecho SP-021 grid. Connecting the two would mean
inventing a correspondence with no stable key — explicitly disallowed. **What would be needed:**
regenerating (or re-tagging) `rocada_polygons.geojson` so each polygon carries a `trecho_id` (or
`rodovia`+`sentido`+`km_start`/`km_end`) matching the prediction module's own `trecho` identity —
a data-generation task for whichever team owns that GeoJSON's source, not a frontend change. This
does not block the V1 demo: the ranking/detail panel is fully functional and useful without map
placement.

## 12. Local integration test

Both processes started locally and torn down after the check — this is **HTTP-level verification,
not a visual browser inspection**, stated explicitly because no browser/screenshot tool was
available in this session (see below).

```
# Terminal 1
cd services/greenv-vegetation-prediction
uvicorn greenv_vegpred.api.app:app --app-dir src --port 8000

# Terminal 2
cd apps/web
npm run dev          # Vite on http://localhost:5173
```

Confirmed live:
- `GET http://127.0.0.1:8000/health` → 200, `model_available: true`.
- Vite serves `http://localhost:5173/` → 200; `src/main.jsx`, `src/pages/DashboardPage.jsx`,
  `src/services/vegetationPredictionApi.js`, and `src/components/VegetationPredictionPanel.jsx`
  all transform through Vite's dev pipeline without error (each fetched directly, HTTP 200, valid
  JS emitted).
- **CORS, the actual mechanism a browser needs**, confirmed via a real `OPTIONS` preflight AND a
  real `GET` from the API, both carrying `Origin: http://localhost:5173`:
  `access-control-allow-origin: http://localhost:5173` present on every response
  (`/api/v1/summary`, `/api/v1/forecasts/ranking`, `/api/v1/forecasts/{trecho_id}`).
- `GET /api/v1/summary` → the real snapshot's counts (`118` total, `61` critical, `57` forecast).
- `GET /api/v1/forecasts/ranking?limit=1` → the real top-ranked trecho, full shape.
- `GET /api/v1/forecasts/{trecho_id}` → 200 for an existing id, matching shape.
- The `not-ready` / `vegetation_level: 0` case (§8) was confirmed against a real snapshot row.
- `npm run build` → succeeds (2942 modules, no errors; a pre-existing "chunk > 500kB" advisory
  warning, unrelated to this change, was not addressed — out of scope).

**What was NOT confirmed:** actual pixel rendering in a browser (no browser/screenshot tool was
available in this session — this is stated explicitly rather than implied or glossed over). The
evidence above (successful builds, successful dev-server module transforms for every new/changed
file, and live, header-verified CORS + correct JSON from all three consumed endpoints) is the
strongest verification available without one, and it exercises the exact request path the browser
would use.

## 13. Limitations

- No visual/pixel confirmation of the rendered panel (§12).
- No map placement (§11) — no stable key exists yet between the two GeoJSON sources.
- The panel shows a bounded ranking (`limit=8` by default) and does not paginate the full 118
  trechos — sufficient for a demo, not a full operations table.
- `POST /api/v1/forecasts/predict` (dynamic inference) is **not** wired into the dashboard at all,
  by design (item 12 of this phase's instruction) — the demo depends only on the snapshot-backed
  reads, which work even when the large Random Forest `.joblib` is absent from a checkout.
- No automated frontend test exists for this panel — `apps/web` has no `lint`/`test` script
  configured in `package.json` today, and none was added (installing a new test framework was
  explicitly out of scope for "the smallest useful integration").

## 14. Future architecture: measurement → prediction (documented, not built)

```
greenv-measurement-worker
        │  publishes (RabbitMQ, NOT implemented in this V1)
        ▼
segment.measured.v1                      -- today's real measurement event
        │
        ▼
[MISSING] prediction consumer            -- would subscribe to segment.measured.v1
        │
        ▼
feature materialisation                  -- append one feature_row per segment, causally
        │                                    (Phase 5's own rules: cycle-safe lags, embargo)
        ▼
forecast (this module, unchanged core)   -- build_days_forecast / build_height_forecast
        │
        ▼
API (already built, Phase 10) → dashboard (this phase's wiring)
```

**The already-discovered limitation, restated here as this phase's own finding, not a new one:**
the current `measurement-result-v1.json` envelope has no stable, versioned field for `rodovia`,
`sentido`, `km`, or an aggregated vegetation-height number — it is built to hand off a 3D
reconstruction + segmentation result, not a corridor-height reading keyed to this module's
`trecho` identity. **This means production-ready integration requires evolving that contract
first** (a new, explicit field set — not parsing `report.html`, which is presentation output, not
a contract, and was correctly avoided here). Nothing in `measurement/` or
`greenv-measurement-worker` was changed to work around this in this pass — the gap is named, not
patched.

**Packaging decision, deliberately not made:** whether the Python forecasting core becomes a
subprocess/sidecar called by a thin Spring service (the same pattern `greenv-measurement-worker`
already uses for `measurement/` — spawn a process, exchange JSON, never import across) or a full
Java port is an open, consequential trade-off left for whoever actually builds the V2 consumer,
with the process-boundary pattern named here as the lower-risk default given it is already proven
elsewhere in this repository.

## 15. Recommended future measurement event contract (conceptual — not implemented)

```json
{
  "event_version": "v1",
  "trecho_id": "SP-021:norte:001000",
  "rodovia": "SP-021",
  "sentido": "norte",
  "km_start": 1.0,
  "km_end": 1.5,
  "observed_at": "2026-09-10T14:32:00Z",
  "vegetation_height_cm": 22.4,
  "operational_status": "ready",
  "measurement_quality": {
    "cell_coverage": 0.94,
    "quality_confidence": "high",
    "blockers": []
  }
}
```

This mirrors `schema.sql`'s existing `height_observation` columns (already designed for exactly
this shape in Phase 2) and this module's own `trecho_id` format
(`rodovia:sentido:km_start_m`, documented in `schema.sql` and parsed by
`api/trecho_meta.py::parse_trecho_id`) — so a future consumer would not need a second identity
scheme. No existing service was changed to emit or consume this in this pass; it is recorded here
as the contract a real integration should converge on.

## 16. Confirmation — no model/metric change

No file under `src/greenv_vegpred/models/`, `src/greenv_vegpred/forecast/`, `data/models/`, or
`models/artifacts/` was read for writing, let alone modified, in this phase. `scripts/verify.sh`
was re-run after the frontend changes and reports the same result as before this phase started:
**146 passed, OpenAPI in sync** — the backend is provably unaffected.
