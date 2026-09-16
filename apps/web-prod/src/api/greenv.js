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

  /**
   * Uma página dos trechos da sessão, em ordem de captura.
   *
   * Já devolveu a sessão inteira, e a tela buscava os quadros de cada trecho em seguida — uma
   * requisição por trecho. Uma hora de campo são trezentos e sessenta deles, então isso era
   * trezentas e sessenta chamadas antes de desenhar qualquer coisa.
   */
  segments(sessionId, { level, limit = 25, offset = 0 } = {}) {
    const query = new URLSearchParams({ limit, offset })
    if (level != null) query.set('level', level)
    return getJson(`/v2/capture-sessions/${sessionId}/segments?${query}`)
  },

  /** Quantos trechos da sessão caem em cada nível, para os chips acima da lista. */
  segmentsSummary(sessionId) {
    return getJson(`/v2/capture-sessions/${sessionId}/segments/summary`)
  },

  segment(sessionId, segmentIndex) {
    return getJson(`/v2/capture-sessions/${sessionId}/segments/${segmentIndex}`)
  },

  /**
   * Os trechos de um segmento enviado, em ordem.
   *
   * O segmento é a unidade do upload — dez segundos de vídeo — e o trecho é a janela de cerca de
   * 25 m em que ele foi medido. A lista acima continua sendo uma linha por segmento; esta abre um
   * deles nos trechos que ele rendeu. Um segmento medido antes do corte não tem nenhum, e a
   * resposta vem vazia.
   */
  windows(sessionId, segmentIndex) {
    return getJson(`/v2/capture-sessions/${sessionId}/segments/${segmentIndex}/windows`)
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

  /**
   * O pacote inteiro, para quem quiser a grade de células e a proveniência.
   *
   * Com `windowIndex` é o pacote daquele trecho; sem ele, o do segmento medido inteiro.
   */
  measurement(sessionId, segmentIndex, windowIndex = null) {
    return getJson(
      `/v2/capture-sessions/${sessionId}/segments/${segmentIndex}/measurement${windowQuery(windowIndex)}`)
  },

  /**
   * A reconstrução de onde a medida saiu: `scene.glb` é a malha e `result.npz` são os arrays de
   * profundidade de onde a nuvem de pontos é remontada.
   *
   * Vem por `fetch` e não por um link direto porque a resposta pode ser 409 — um trecho medido
   * antes de o serviço de profundidade passar a guardar o que calculou não tem arquivo nenhum —
   * e um link levaria a pessoa a uma aba com JSON de erro em vez de uma mensagem na tela.
   * Dezenas de megabytes: quem chama mostra que está baixando.
   *
   * Cada trecho tem a reconstrução dele: sem `windowIndex` o arquivo seria o do segmento medido
   * inteiro, que é outra geometria.
   */
  async depthArtifact(sessionId, segmentIndex, fileName, windowIndex = null) {
    const response = await request(
      `/v2/capture-sessions/${sessionId}/segments/${segmentIndex}/depth/${fileName}`
        + windowQuery(windowIndex),
      { accept: 'application/octet-stream' })
    return response.blob()
  },
}

/** `?window=2`, ou nada quando a leitura é do segmento inteiro. */
function windowQuery(windowIndex) {
  return windowIndex == null ? '' : `?window=${windowIndex}`
}

/**
 * Entrega um blob ao navegador com o nome que ele deve ter em disco.
 *
 * O atributo `download` de uma âncora é ignorado quando o endereço é de outra origem, e a API
 * mora noutro subdomínio — daí o blob local, que é da mesma origem da página e por isso obedece.
 * A URL é revogada depois, e não na hora: revogar antes de o navegador terminar cancela o
 * download em silêncio.
 */
export function saveBlob(blob, fileName) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.append(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 30_000)
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
