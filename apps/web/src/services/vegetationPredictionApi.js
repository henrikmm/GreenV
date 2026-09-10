// GreenV Vegetation Prediction API client (Phase 12 of the prediction module's own TASK.md).
//
// This is the ONLY place in apps/web that talks to that backend -- no component should call
// fetch() against it directly, so the base URL, error handling, and endpoint shapes stay in one
// place. Every field the backend returns (vegetation_level, operational_confidence,
// days_until_critical, the ranking order itself, ...) is used AS GIVEN; nothing here
// recomputes a classification, a ranking, or a days-until-critical estimate -- see
// services/greenv-vegetation-prediction/reports/integration.md for why.
//
// Base URL: VITE_VEGETATION_PREDICTION_API_URL (see .env.example). Defaults to
// http://127.0.0.1:8000 for local development, matching that API's own documented CORS origins
// (reports/api.md) for this app's Vite dev server (port 5173).
//
// Consumed endpoints (the module's frozen, snapshot-backed reads -- never the dynamic
// POST /predict, so the browser never triggers on-demand model inference just to render a
// dashboard):
//   GET /api/v1/summary
//   GET /api/v1/forecasts/ranking
//   GET /api/v1/forecasts/{trecho_id}

const BASE_URL = import.meta.env.VITE_VEGETATION_PREDICTION_API_URL || 'http://127.0.0.1:8000'

export class VegetationPredictionApiError extends Error {}

async function get(path) {
  let response
  try {
    response = await fetch(`${BASE_URL}${path}`)
  } catch (err) {
    // Network-level failure (API offline, CORS rejected, DNS, ...) -- never silently swallowed,
    // never answered with a fabricated forecast. Callers show "previsão temporariamente
    // indisponível" instead.
    throw new VegetationPredictionApiError(`could not reach ${BASE_URL}${path}: ${err.message}`)
  }
  if (response.status === 404) {
    return null // a real, documented "no forecast for this trecho_id" answer, not an error
  }
  if (!response.ok) {
    let detail = ''
    try { detail = (await response.json())?.detail ?? '' } catch { /* body wasn't JSON */ }
    throw new VegetationPredictionApiError(`${path} -> HTTP ${response.status}${detail ? `: ${detail}` : ''}`)
  }
  return response.json()
}

/** GET /api/v1/summary -- { total_trechos, critical, forecast, beyond_horizon,
 * insufficient_data, critical_height_cm, data_provenance }. */
export function getSummary() {
  return get('/api/v1/summary')
}

/** GET /api/v1/forecasts/ranking?limit=N -- the frozen urgency order, already ranked by the
 * backend. `limit` is optional (backend-validated, 1-500). */
export function getRanking(limit) {
  const qs = limit ? `?limit=${encodeURIComponent(limit)}` : ''
  return get(`/api/v1/forecasts/ranking${qs}`)
}

/** GET /api/v1/forecasts/{trecho_id} -- one full forecast object, or `null` on a 404 (trecho not
 * in the current snapshot). */
export function getForecast(trechoId) {
  return get(`/api/v1/forecasts/${encodeURIComponent(trechoId)}`)
}
