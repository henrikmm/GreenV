import { getFeatureId } from './classification'

// Distância aproximada em km entre dois pontos lat/lon (fórmula de haversine).
export function haversineKm(a, b) {
  const R = 6371
  const dLat = ((b.lat - a.lat) * Math.PI) / 180
  const dLon = ((b.lon - a.lon) * Math.PI) / 180
  const lat1 = (a.lat * Math.PI) / 180
  const lat2 = (b.lat * Math.PI) / 180
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(h))
}

function centroid(feature) {
  return { lat: feature.properties.centroid_lat, lon: feature.properties.centroid_lon }
}

// KM aproximado de um trecho: marco quilométrico mais próximo do centróide.
export function nearestKm(feature, marcoKm) {
  if (!marcoKm || !marcoKm.features.length) return null
  const p = centroid(feature)
  let best = null
  let bestDist = Infinity
  marcoKm.features.forEach(m => {
    const [lon, lat] = m.geometry.coordinates
    const d = haversineKm(p, { lat, lon })
    if (d < bestDist) { bestDist = d; best = m.properties.km }
  })
  return best
}

const GROUP_DISTANCE_KM = 1.5

// Agrupa, de forma gulosa, trechos vizinhos (nível >= 2, ainda sem OS) em sugestões de
// "uma saída, dois (ou três) trechos" — prioriza pares próximos com equipamento compatível.
export function suggestCombinedRoutes(geojson, orders, marcoKm) {
  if (!geojson) return []

  const usedIds = new Set()
  orders.forEach(o => (o.featureIds || []).forEach(id => usedIds.add(id)))

  const candidates = geojson.features
    .filter(f => (f.properties.vegetation_level || 1) >= 2)
    .filter(f => !usedIds.has(getFeatureId(f)))
    .map(f => ({ feature: f, km: nearestKm(f, marcoKm) ?? 0 }))
    .sort((a, b) => a.km - b.km)

  const grouped = new Set()
  const suggestions = []

  for (let i = 0; i < candidates.length; i++) {
    const a = candidates[i]
    if (grouped.has(i)) continue

    const partners = []
    for (let j = i + 1; j < candidates.length && partners.length < 2; j++) {
      if (grouped.has(j)) continue
      const b = candidates[j]
      const dist = haversineKm(centroid(a.feature), centroid(b.feature))
      if (dist <= GROUP_DISTANCE_KM) {
        partners.push({ ...b, dist, idx: j })
      } else if (b.km - a.km > GROUP_DISTANCE_KM * 3) {
        break // candidatos ordenados por km — não vale continuar procurando
      }
    }

    if (partners.length === 0) continue

    grouped.add(i)
    partners.forEach(p => grouped.add(p.idx))

    const members = [a, ...partners]
    const features = members.map(m => m.feature)
    const sameEquipment = new Set(features.map(f => f.properties.name)).size === 1
    const maxLevel = Math.max(...features.map(f => f.properties.vegetation_level || 1))
    const totalArea = features.reduce((s, f) => s + (f.properties.area_m2 || 0), 0)
    const maxDist = Math.max(...partners.map(p => p.dist))

    suggestions.push({
      id: `sug-${getFeatureId(a.feature)}`,
      features,
      totalArea,
      maxLevel,
      distanceBetweenKm: maxDist,
      approxKm: a.km,
      reason: sameEquipment
        ? `Mesmo equipamento, a ${formatDistance(maxDist)} um do outro`
        : `Trechos vizinhos, a ${formatDistance(maxDist)} um do outro`,
    })
  }

  return suggestions.sort((x, y) => y.maxLevel - x.maxLevel || y.totalArea - x.totalArea)
}

function formatDistance(km) {
  if (km < 1) return `${Math.round(km * 1000)} m`
  return `${km.toFixed(1).replace('.', ',')} km`
}

// Ordena uma seleção manual de trechos pela quilometragem da via, num sentido só (KM
// crescente) — não pelo vizinho geograficamente mais próximo. Numa via dividida como o
// Rodoanel, o trecho "mais perto em linha reta" pode estar do outro lado da pista, e só dá
// pra chegar lá contornando num retorno. Seguir o km evita sugerir esse tipo de travessia.
export function planRoute(features, marcoKm) {
  if (!features.length) return { ordered: [], totalDistanceKm: 0 }

  const withKm = features
    .map(f => ({ feature: f, km: nearestKm(f, marcoKm) ?? 0 }))
    .sort((a, b) => a.km - b.km)

  const ordered = withKm.map(w => w.feature)
  let totalDistanceKm = 0
  for (let i = 0; i < ordered.length - 1; i++) {
    totalDistanceKm += haversineKm(centroid(ordered[i]), centroid(ordered[i + 1]))
  }

  return { ordered, totalDistanceKm }
}
