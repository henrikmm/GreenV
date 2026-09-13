import { describePlace, centreOf } from '@greenv/web-core'

/**
 * O lugar de uma leitura, como a API já o resolveu.
 *
 * Antes isto era uma consulta do navegador ao Nominatim, uma por sessão, com cache no
 * `localStorage`. Saiu: a API resolve uma vez por trecho e guarda na linha, então quem abre o
 * painel não pergunta nada a ninguém e todo mundo vê o mesmo nome. O que sobrou aqui é a
 * composição — juntar rua, número e marco quilométrico numa frase — e a volta para a coordenada
 * enquanto o resolvedor não passou, o que leva no máximo um minuto depois de uma medição nova.
 *
 * Duas regras de precedência que valem explicar:
 *
 * - **Na rodovia, o marco vem primeiro.** "SP-021 · KM 12" é o que se fala no rádio; o nome da
 *   via de acesso mais próxima é detalhe. Fora dela não há marco e a rua é tudo que existe.
 * - **O número é sempre "aprox."** e nunca entra no rótulo principal. A geocodificação reversa
 *   responde com o ponto endereçável mais próximo, que numa margem de estrada é o prédio do
 *   outro lado da pista. Um número cravado ali seria uma precisão que ninguém mediu.
 */
function compose(segment) {
  const { placeLabel, placeDetail, placeHouseNumber, placeRoad, placeKm } = segment

  const parts = []
  if (placeHouseNumber) parts.push(`nº aprox. ${placeHouseNumber}`)
  if (placeDetail) parts.push(placeDetail)

  if (placeRoad && placeKm != null) {
    return {
      label: `${placeRoad} · KM ${placeKm}`,
      detail: [placeLabel, ...parts].filter(Boolean).join(' · ') || null,
    }
  }
  if (!placeLabel) return null
  return { label: placeLabel, detail: parts.join(' · ') || null }
}

/** O lugar de um trecho: o que a API resolveu, ou a coordenada enquanto ela não resolveu. */
export function placeOfSegment(segment) {
  if (!segment) return null
  return compose(segment) ?? describePlace({
    latitude: segment.trackCenterLat, longitude: segment.trackCenterLon,
  })
}

/**
 * O lugar de uma sessão inteira, que é uma pergunta mais difícil do que parece.
 *
 * Uma volta cruza ruas, então não existe "a rua da sessão". Quando todos os trechos caem no mesmo
 * nome, esse nome serve; quando não, o rótulo diz o primeiro e conta os outros, em vez de
 * escolher um e calar os demais. É a mesma distorção que a tela de trechos existe para evitar,
 * só que menor.
 */
export function placeOfSession(segments) {
  const places = (segments ?? []).map(placeOfSegment).filter(Boolean)
  if (places.length === 0) {
    return describePlace(centreOf((segments ?? []).map(segment => ({
      latitude: segment.trackCenterLat, longitude: segment.trackCenterLon,
    }))))
  }
  const distinct = [...new Set(places.map(place => place.label))]
  if (distinct.length === 1) return places[0]
  return {
    label: distinct[0],
    detail: `e mais ${distinct.length - 1} ${distinct.length === 2 ? 'via' : 'vias'}`,
  }
}
