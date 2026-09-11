/**
 * O cliente HTTP da GreenV, e as três coisas que a API exige de um navegador.
 *
 * 1. `credentials: 'include'` em toda requisição. A sessão vive em cookies `__Host-` que o
 *    JavaScript não consegue ler; omitir isto faz tudo responder 401 sem explicação.
 * 2. `X-CSRF-Token` em toda escrita, com o valor do cookie `greenv_csrf`, que é o único legível.
 *    Sem ele o filtro simplesmente não autentica, e o resultado também é 401 — não um erro de
 *    CSRF, o que torna a causa difícil de adivinhar.
 * 3. Uma tentativa de renovação quando o acesso expira. O token de acesso dura quinze minutos e
 *    o de renovação sete dias, então uma aba aberta por mais de quinze minutos encontra 401 numa
 *    requisição que deveria funcionar.
 */

/** Em desenvolvimento o proxy do Vite serve a API sob a mesma origem; em produção é o mesmo host. */
const BASE = import.meta.env.VITE_API_BASE ?? ''

export class ApiError extends Error {
  constructor(status, code, detail) {
    super(detail || code || `HTTP ${status}`)
    this.status = status
    this.code = code
  }
}

function csrfToken() {
  const match = document.cookie.match(/(?:^|;\s*)greenv_csrf=([^;]*)/)
  return match ? decodeURIComponent(match[1]) : null
}

async function raw(path, { method = 'GET', body, headers = {}, accept = 'application/json' } = {}) {
  const outgoing = { accept, ...headers }
  if (body !== undefined) outgoing['content-type'] = 'application/json'
  if (method !== 'GET' && method !== 'HEAD') {
    const token = csrfToken()
    if (token) outgoing['X-CSRF-Token'] = token
  }
  return fetch(BASE + path, {
    method,
    credentials: 'include',
    headers: outgoing,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

async function failure(response) {
  // A API responde RFC 7807 em quase tudo, e o token OAuth responde no formato do RFC 6749.
  // Ler os dois evita que um erro legítimo apareça como "erro desconhecido".
  let code = null
  let detail = null
  try {
    const problem = await response.json()
    code = problem.type?.replace('urn:greenv:error:', '') ?? problem.error ?? null
    detail = problem.detail ?? problem.error_description ?? problem.title ?? null
  } catch {
    // corpo vazio ou não-JSON; o status ainda diz o suficiente
  }
  return new ApiError(response.status, code, detail)
}

let refreshing = null

/**
 * Uma requisição, renovando a sessão uma única vez se o acesso tiver expirado.
 *
 * A renovação é compartilhada entre chamadas simultâneas: uma tela que carrega quatro listas ao
 * mesmo tempo faria quatro renovações concorrentes, e como cada uma rotaciona o token de
 * renovação, três delas seriam invalidadas e derrubariam a sessão que acabaram de renovar.
 */
export async function request(path, options = {}) {
  let response = await raw(path, options)
  if (response.status === 401 && !options.noRetry && path !== '/v2/auth/refresh') {
    refreshing = refreshing ?? raw('/v2/auth/refresh', { method: 'POST' }).finally(() => { refreshing = null })
    const renewed = await refreshing
    if (renewed.ok) response = await raw(path, options)
  }
  if (!response.ok) throw await failure(response)
  return response
}

export async function getJson(path) {
  return (await request(path)).json()
}

export async function sendJson(path, method, body) {
  const response = await request(path, { method, body })
  return response.status === 204 ? null : response.json()
}
