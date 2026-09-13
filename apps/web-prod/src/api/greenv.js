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

  /**
   * O que um quadro contribuiu para a medição.
   *
   * Separado dos bytes do JPEG porque custa outra coisa: a foto sai direto do armazenamento, e
   * isto percorre todas as células do assessment atrás dos votos daquele quadro.
   */
  frameReadings(sessionId, segmentIndex, fileName) {
    return getJson(
      `/v2/capture-sessions/${sessionId}/segments/${segmentIndex}/frames/${fileName}/readings`)
  },

  /** O pacote inteiro, para quem quiser a grade de células e a proveniência. */
  measurement(sessionId, segmentIndex) {
    return getJson(`/v2/capture-sessions/${sessionId}/segments/${segmentIndex}/measurement`)
  },
}

/**
 * As leituras, ordenadas e filtradas pela API.
 *
 * Isto já foi uma chamada só, pedindo duzentas linhas para ordenar no navegador. Funcionava
 * enquanto tudo coubesse numa resposta: passando disso, a primeira página vira a mais alta de
 * um recorte qualquer e fica igualzinha à mais alta que existe. Agora a ordem, o filtro e a
 * página são da consulta, e os contadores vêm de uma rota própria porque não mudam quando
 * alguém rola a lista.
 */
function measurementParams({ level, capturedFrom, capturedTo, search }) {
  const query = new URLSearchParams()
  if (level != null) query.set('level', level)
  if (capturedFrom) query.set('capturedFrom', capturedFrom)
  if (capturedTo) query.set('capturedTo', capturedTo)
  if (search) query.set('q', search)
  return query
}

export const measurements = {
  list({ sort = 'HEIGHT_DESC', limit = 25, offset = 0, ...filters } = {}) {
    const query = measurementParams(filters)
    query.set('sort', sort)
    query.set('limit', limit)
    query.set('offset', offset)
    return getJson(`/v2/measurements?${query}`)
  },
  summary(filters = {}) {
    // O nível não vai: os contadores respondem por todos eles, senão o chip escolhido seria o
    // único com número e os outros zerariam por estarem fora do próprio filtro.
    const { level, ...rest } = filters
    return getJson(`/v2/measurements/summary?${measurementParams(rest)}`)
  },
}

export const teams = {
  list() {
    return getJson('/v2/teams')
  },
}

export const serviceOrders = {
  list({ status, priority, teamId, search, limit = 50, offset = 0 } = {}) {
    const query = new URLSearchParams({ limit, offset })
    if (status) query.set('status', status)
    if (priority) query.set('priority', priority)
    if (teamId) query.set('teamId', teamId)
    if (search) query.set('search', search)
    return getJson(`/v2/service-orders?${query}`)
  },

  get(orderId) {
    return getJson(`/v2/service-orders/${orderId}`)
  },

  /**
   * Abre uma ordem contra trechos medidos.
   *
   * Só os alvos e o plano vão no corpo. A área, o nível e o centro são lidos dos trechos pela
   * API: uma ordem precisa poder dizer que evidência a justificou, e um cliente que pudesse
   * enviar esses números poderia abrir uma ordem alegando uma altura que ninguém mediu.
   */
  open({ priority, teamId, scheduledFor, notes, targets }) {
    return sendJson('/v2/service-orders', 'POST', {
      priority, teamId: teamId || null, scheduledFor: scheduledFor || null,
      notes: notes || null, targets,
    })
  },

  amend(orderId, changes) {
    return sendJson(`/v2/service-orders/${orderId}`, 'PATCH', changes)
  },

  /** Cancela. A linha permanece: uma ordem aberta é uma decisão que alguém tomou. */
  cancel(orderId) {
    return sendJson(`/v2/service-orders/${orderId}`, 'DELETE')
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
