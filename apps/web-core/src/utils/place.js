import { ROAD_ANCHORS } from './classification'
import { haversineKm } from './routePlanner'

/**
 * Onde uma captura foi feita, dito a partir de onde ela esteve.
 *
 * O nome da via não serve para isto. Ele era um campo de texto que o operador preenchia à mão,
 * então ou vem vazio, ou vem "TESTE", ou vem uma grafia que não casa com nenhuma outra. As
 * coordenadas, ao contrário, são medidas: cada trecho medido carrega o centro da própria trilha.
 *
 * A referência embarcada é uma só, a SP-021, interpolada a partir dos sete âncoras de
 * `classification.js`. Dentro de {@link ON_ROAD_KM} a captura está na via e o rótulo diz o marco
 * quilométrico; fora disso diz a coordenada e a que distância a via ficou. Nenhuma consulta sai
 * daqui: geocodificação reversa mandaria a localização do usuário para um terceiro, e o que ela
 * compraria é um nome bonito, não uma resposta mais verdadeira.
 */
const ROAD_NAME = 'SP-021'

/** Metade da largura de uma pista dupla mais o acostamento, arredondada para cima. */
export const ON_ROAD_KM = 0.5

/** Passo da amostragem ao longo da via, em quilômetros de marco. */
const SAMPLE_STEP_KM = 0.1

function interpolate(km) {
  for (let i = 0; i < ROAD_ANCHORS.length - 1; i++) {
    const from = ROAD_ANCHORS[i]
    const to = ROAD_ANCHORS[i + 1]
    if (km >= from.km && km <= to.km) {
      const t = (km - from.km) / (to.km - from.km)
      return { lat: from.lat + t * (to.lat - from.lat), lon: from.lon + t * (to.lon - from.lon) }
    }
  }
  return null
}

/** O ponto da via mais próximo, e a que distância ele está. */
function nearestOnRoad(point) {
  const last = ROAD_ANCHORS[ROAD_ANCHORS.length - 1].km
  let best = null
  for (let km = 0; km <= last; km += SAMPLE_STEP_KM) {
    const on = interpolate(km)
    if (!on) continue
    const distanceKm = haversineKm(point, on)
    if (!best || distanceKm < best.distanceKm) best = { km, distanceKm }
  }
  return best
}

function formatCoordinate(latitude, longitude) {
  const lat = `${Math.abs(latitude).toFixed(4).replace('.', ',')}° ${latitude < 0 ? 'S' : 'N'}`
  const lon = `${Math.abs(longitude).toFixed(4).replace('.', ',')}° ${longitude < 0 ? 'O' : 'L'}`
  return `${lat} · ${lon}`
}

/**
 * @param point `{ latitude, longitude }`, ou nulo
 * @returns `{ label, detail, onRoad, km, distanceKm }`, ou nulo quando não há posição
 */
export function describePlace(point) {
  if (!point || point.latitude == null || point.longitude == null) return null
  const here = { lat: point.latitude, lon: point.longitude }
  const nearest = nearestOnRoad(here)
  if (nearest && nearest.distanceKm <= ON_ROAD_KM) {
    return {
      label: `${ROAD_NAME} · KM ${nearest.km.toFixed(1).replace('.', ',')}`,
      detail: null,
      onRoad: true,
      km: nearest.km,
      distanceKm: nearest.distanceKm,
    }
  }
  return {
    label: formatCoordinate(point.latitude, point.longitude),
    detail: nearest
      ? `a ${nearest.distanceKm.toFixed(1).replace('.', ',')} km da ${ROAD_NAME}`
      : null,
    onRoad: false,
    km: null,
    distanceKm: nearest?.distanceKm ?? null,
  }
}

/**
 * O centro de um punhado de pontos.
 *
 * Média simples, não centróide de polígono: são poucas centenas de metros de trilha, e a
 * diferença entre as duas coisas nessa escala é menor que a precisão do GPS que as produziu.
 */
export function centreOf(points) {
  let latitude = 0
  let longitude = 0
  let counted = 0
  for (const point of points ?? []) {
    if (point?.latitude == null || point?.longitude == null) continue
    latitude += point.latitude
    longitude += point.longitude
    counted += 1
  }
  return counted === 0 ? null : { latitude: latitude / counted, longitude: longitude / counted }
}
