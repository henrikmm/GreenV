import { getJson, request, sendJson, ApiError } from './client'
import { vegetationLevel } from '@greenv/web-core'

/**
 * As rotas da GreenV, traduzidas para o vocabulário das telas.
 *
 * Esta camada existe para que nenhum componente saiba o formato do JSON da API. Quando a API
 * muda um nome, muda aqui e em nenhum outro lugar.
 */

export const auth = {
  async signIn(email, password) {
    try {
      const user = await sendJson('/v2/auth/login', 'POST', { email, password })
      return { ok: true, user: toSession(user) }
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        return { ok: false, error: 'E-mail ou senha inválidos.' }
      }
      return { ok: false, error: error.message || 'Não foi possível entrar.' }
    }
  },

  async signOut() {
    try {
      await request('/v2/auth/logout', { method: 'POST', noRetry: true })
    } catch {
      // Sair localmente vale mesmo que o servidor não responda.
    }
  },

  /**
   * Descobre se já existe uma sessão aberta.
   *
   * Os cookies são invisíveis ao JavaScript, então perguntar ao servidor é a única maneira. Um
   * 401 aqui é a resposta normal para quem não entrou, não um erro.
   */
  async restore() {
    try {
      return toSession(await getJson('/v2/auth/me'))
    } catch {
      return null
    }
  },
}

function toSession(user) {
  if (!user) return null
  const name = user.displayName || user.email
  return {
    name,
    email: user.email,
    role: user.role,
    initials: name.split(/\s+/).slice(0, 2).map(part => part[0]?.toUpperCase() ?? '').join(''),
  }
}

export const sessions = {
  list({ limit = 50, offset = 0, measuredOnly = false, rodovia, sentido, state } = {}) {
    const query = new URLSearchParams({ limit, offset })
    if (measuredOnly) query.set('measuredOnly', 'true')
    if (rodovia) query.set('rodovia', rodovia)
    if (sentido) query.set('sentido', sentido)
    if (state) query.set('state', state)
    return getJson(`/v2/capture-sessions?${query}`)
  },

  get(sessionId) {
    return getJson(`/v2/capture-sessions/${sessionId}`)
  },

  segments(sessionId) {
    return getJson(`/v2/capture-sessions/${sessionId}/segments`)
  },

  /** O desenho da sessão: a trilha e a faixa medida, já em GeoJSON. */
  track(sessionId) {
    return getJson(`/v2/capture-sessions/${sessionId}/track`)
  },

  frames(sessionId, segmentIndex) {
    return getJson(`/v2/capture-sessions/${sessionId}/segments/${segmentIndex}/frames`)
  },

  /** O pacote inteiro, para quem quiser a grade de células e a proveniência. */
  measurement(sessionId, segmentIndex) {
    return getJson(`/v2/capture-sessions/${sessionId}/segments/${segmentIndex}/measurement`)
  },
}

export const measurements = {
  list({ limit = 100, offset = 0 } = {}) {
    return getJson(`/v2/measurements?limit=${limit}&offset=${offset}`)
  },
}

/**
 * O nível de um segmento medido, com o mesmo cuidado que o resto do sistema tem.
 *
 * A API já calcula o nível a partir do percentil das células. Aqui só se garante que um valor
 * ausente não vire nível 1: `vegetationLevel` devolve 0, "não avaliado", e nunca a prioridade
 * mais baixa. Uma altura que ninguém mediu não é uma altura baixa.
 */
export function levelOfSegment(segment) {
  return vegetationLevel(segment?.measurementLevel)
}
