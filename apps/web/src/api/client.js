// The single door to the API.
//
// The session lives in HttpOnly cookies, so this file never sees a token and never stores one.
// What it does instead: send credentials on every call, echo the CSRF cookie back in a header, and
// recover from the fifteen-minute access token expiring without bothering the user.

const BASE = import.meta.env.VITE_GREENV_API_URL || '/api'

const CSRF_COOKIE = 'greenv_csrf'
const CSRF_HEADER = 'X-CSRF-Token'

/** Fired when a refresh fails, so the app can fall back to the login screen. */
export const SESSION_ENDED = 'greenv:session-ended'

export class ApiError extends Error {
  constructor(status, code, detail) {
    super(detail || code || `HTTP ${status}`)
    this.status = status
    this.code = code
    this.detail = detail
  }
}

function readCsrfToken() {
  const match = document.cookie.match(new RegExp(`(?:^|; )${CSRF_COOKIE}=([^;]*)`))
  return match ? decodeURIComponent(match[1]) : null
}

// One refresh at a time. Five calls failing together must not fire five rotations - each rotation
// retires the previous refresh token, so the losers would look like a stolen token being replayed
// and the server would revoke the whole session.
let refreshInFlight = null

function refreshOnce() {
  if (!refreshInFlight) {
    refreshInFlight = rawRequest('/v2/auth/refresh', { method: 'POST' })
      .then((response) => response.ok)
      .catch(() => false)
      .finally(() => { refreshInFlight = null })
  }
  return refreshInFlight
}

function rawRequest(path, { method = 'GET', body } = {}) {
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  if (method !== 'GET' && method !== 'HEAD') {
    const csrf = readCsrfToken()
    if (csrf) headers[CSRF_HEADER] = csrf
  }

  return fetch(`${BASE}${path}`, {
    method,
    headers,
    // Without this the browser sends no cookies and every call is anonymous.
    credentials: 'include',
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

async function toError(response) {
  let code = null
  let detail = null
  try {
    const problem = await response.json()
    code = problem.title ?? null
    detail = problem.detail ?? null
  } catch {
    // A non-JSON error body is not worth failing over.
  }
  return new ApiError(response.status, code, detail)
}

/**
 * @param {string} path
 * @param {{ method?: string, body?: unknown, retry?: boolean }} options
 *   `retry: false` for the auth routes themselves, or a failed refresh would refresh again forever.
 */
export async function request(path, { method = 'GET', body, retry = true } = {}) {
  let response = await rawRequest(path, { method, body })

  if (response.status === 401 && retry) {
    if (await refreshOnce()) {
      response = await rawRequest(path, { method, body })
    } else {
      window.dispatchEvent(new CustomEvent(SESSION_ENDED))
      throw await toError(response)
    }
  }

  if (!response.ok) throw await toError(response)
  if (response.status === 204) return null
  return response.json()
}

export const api = {
  login: (email, password) =>
    request('/v2/auth/login', { method: 'POST', body: { email, password }, retry: false }),
  logout: () => request('/v2/auth/logout', { method: 'POST', retry: false }),
  me: () => request('/v2/auth/me', { retry: false }),
}
