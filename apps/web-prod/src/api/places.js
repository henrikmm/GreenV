import { useEffect, useState } from 'react'

/**
 * O nome da rua onde a captura foi feita, perguntado ao OpenStreetMap.
 *
 * Eu tinha recusado geocodificação reversa antes, dizendo que ela manda a localização para um
 * terceiro. Estava exagerando: este painel já carrega as telhas do OpenStreetMap centradas
 * exatamente nessas coordenadas, então o mesmo servidor já sabe onde a captura foi. A consulta de
 * nome não acrescenta exposição nenhuma, e a alternativa — mostrar latitude e longitude — não
 * diz nada a quem precisa mandar uma equipe.
 *
 * Três cuidados, que são o que a política de uso do Nominatim pede:
 *
 * - **Uma consulta por vez, com um segundo de intervalo.** A fila abaixo serializa tudo.
 * - **Resultado em cache.** Coordenada arredondada a quatro casas, cerca de onze metros, que é
 *   mais fino que a precisão dos fixes que a produziram. O cache vive no `localStorage`, então
 *   uma sessão é resolvida uma vez por navegador e nunca mais.
 * - **Falhar em silêncio.** Sem rede, sem resposta ou fora do ar, quem chama recebe nulo e a tela
 *   volta para a coordenada. Um nome de rua é conveniência; nenhuma decisão depende dele.
 *
 * O lugar definitivo disto é a API, resolvendo uma vez por sessão e guardando na linha. Aqui
 * resolve uma vez por navegador, o que é barato para um punhado de sessões e caro se um dia
 * forem centenas.
 */
const ENDPOINT = 'https://nominatim.openstreetmap.org/reverse'
const CACHE_PREFIX = 'greenv_place_'
const MINIMUM_INTERVAL_MS = 1100

let queue = Promise.resolve()

function cacheKey(latitude, longitude) {
  return `${CACHE_PREFIX}${latitude.toFixed(4)},${longitude.toFixed(4)}`
}

function readCache(key) {
  try {
    const saved = localStorage.getItem(key)
    return saved ? JSON.parse(saved) : undefined
  } catch {
    return undefined
  }
}

function writeCache(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // Modo privado, cota cheia: o nome simplesmente será perguntado de novo.
  }
}

/**
 * Monta o rótulo a partir do endereço.
 *
 * A rua é o que se quer, mas nem todo ponto tem uma: uma das capturas caiu dentro de uma quadra e
 * o OpenStreetMap não conhece via nenhuma ali. Nesse caso o bairro é a resposta honesta, e dizer
 * "Jardim dos Estados" é infinitamente mais útil que dizer 23,6365° S.
 */
function labelFrom(payload) {
  const address = payload?.address ?? {}
  const road = address.road || address.pedestrian || address.footway || address.residential
  const area = address.suburb || address.neighbourhood || address.city_district
  const city = address.city || address.town || address.municipality || address.village
  const label = road || area || city
  if (!label) return null
  const detail = [road ? area : null, city].filter(Boolean).join(' · ')
  return { label, detail: detail || null, source: 'openstreetmap' }
}

async function lookup(latitude, longitude) {
  const url = `${ENDPOINT}?format=jsonv2&zoom=17&addressdetails=1&accept-language=pt-BR`
    + `&lat=${latitude}&lon=${longitude}`
  const response = await fetch(url, { headers: { accept: 'application/json' } })
  if (!response.ok) return null
  return labelFrom(await response.json())
}

/** Resolve um ponto, respeitando o cache e a fila. */
export function resolvePlaceName(point) {
  if (!point || point.latitude == null || point.longitude == null) return Promise.resolve(null)
  const key = cacheKey(point.latitude, point.longitude)
  const cached = readCache(key)
  if (cached !== undefined) return Promise.resolve(cached)

  const next = queue.then(async () => {
    const again = readCache(key)
    if (again !== undefined) return again
    let resolved = null
    try {
      resolved = await lookup(point.latitude, point.longitude)
    } catch {
      // Sem rede ou serviço fora: a tela fica com a coordenada, que continua correta.
      return null
    }
    writeCache(key, resolved)
    await new Promise(done => setTimeout(done, MINIMUM_INTERVAL_MS))
    return resolved
  })
  // A fila não pode morrer por causa de uma falha; o próximo da fila tem de continuar.
  queue = next.catch(() => null)
  return next
}

/**
 * Resolve vários pontos e devolve o que já chegou.
 *
 * @param pointsById objeto de identificador para `{ latitude, longitude }`
 * @returns objeto de identificador para `{ label, detail }`, preenchido conforme as respostas vêm
 */
export function usePlaceNames(pointsById) {
  const [names, setNames] = useState({})
  // Uma chave estável: sem isto o efeito roda de novo a cada renderização, porque o objeto é
  // recriado, e a fila cresce sem fim.
  const signature = Object.entries(pointsById ?? {})
    .map(([id, point]) => `${id}:${point?.latitude?.toFixed(4)},${point?.longitude?.toFixed(4)}`)
    .sort()
    .join('|')

  useEffect(() => {
    let live = true
    for (const [id, point] of Object.entries(pointsById ?? {})) {
      resolvePlaceName(point).then(resolved => {
        if (live && resolved) setNames(previous => ({ ...previous, [id]: resolved }))
      })
    }
    return () => { live = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signature])

  return names
}
