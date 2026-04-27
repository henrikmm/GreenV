export const LEVELS = {
  1: { label: 'Nível 1', desc: 'h < 10 cm', color: '#16a34a', bg: 'rgba(22,163,74,0.20)', priority: 'Baixa' },
  2: { label: 'Nível 2', desc: '10 ≤ h ≤ 30 cm', color: '#ca8a04', bg: 'rgba(202,138,4,0.20)', priority: 'Média' },
  3: { label: 'Nível 3', desc: 'h > 30 cm', color: '#dc2626', bg: 'rgba(220,38,38,0.20)', priority: 'Alta' },
}

export const EQUIPMENT_TYPES = {
  'Apenas manual': { short: 'Manual' },
  'Spider, Giro-Zero ou Trator com trincheira': { short: 'Spider / Giro-Zero / Trator Trincheira' },
  'Trator com braço articulado': { short: 'Trator Braço Articulado' },
  'Spider, com ancoragem': { short: 'Spider com Ancoragem' },
}

export function getPolygonStyle(feature) {
  const level = feature.properties.vegetation_level || 1
  const lvl = LEVELS[level]
  return {
    color: lvl.color,
    weight: 2,
    opacity: 0.9,
    fillColor: lvl.color,
    fillOpacity: 0.30,
  }
}

export function getLevel(val) {
  return LEVELS[val] || LEVELS[1]
}

export function formatArea(m2) {
  if (m2 >= 10000) return `${(m2 / 10000).toFixed(2)} ha`
  return `${Math.round(m2).toLocaleString('pt-BR')} m²`
}

export function formatKm(meters) {
  const km = meters / 1000
  return `KM ${km.toFixed(1).replace('.', ',')}`
}

export function generateOrderId() {
  const now = new Date()
  const y = now.getFullYear()
  const m = String(now.getMonth() + 1).padStart(2, '0')
  const seq = String(Math.floor(Math.random() * 9000) + 1000)
  return `OS-ROÇ-${y}${m}-${seq}`
}

const ROAD_ANCHORS = [
  { km: 0,    lat: -23.4080, lon: -46.7290 },
  { km: 5,    lat: -23.4310, lon: -46.7520 },
  { km: 10,   lat: -23.4600, lon: -46.7700 },
  { km: 15,   lat: -23.4950, lon: -46.7850 },
  { km: 20,   lat: -23.5400, lon: -46.8000 },
  { km: 25,   lat: -23.5850, lon: -46.8150 },
  { km: 29.3, lat: -23.6290, lon: -46.8300 },
]

export function kmToLatLng(kmMeters) {
  const km = kmMeters / 1000
  for (let i = 0; i < ROAD_ANCHORS.length - 1; i++) {
    const a = ROAD_ANCHORS[i]
    const b = ROAD_ANCHORS[i + 1]
    if (km >= a.km && km <= b.km) {
      const t = (km - a.km) / (b.km - a.km)
      return { lat: a.lat + t * (b.lat - a.lat), lng: a.lon + t * (b.lon - a.lon) }
    }
  }
  if (km < 0) return { lat: ROAD_ANCHORS[0].lat, lng: ROAD_ANCHORS[0].lon }
  const last = ROAD_ANCHORS[ROAD_ANCHORS.length - 1]
  return { lat: last.lat, lng: last.lon }
}
