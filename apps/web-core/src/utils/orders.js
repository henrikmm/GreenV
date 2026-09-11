import { EQUIPMENT_TYPES, formatArea, generateOrderId, getFeatureId } from './classification'

// Monta uma ordem de serviço a partir de 1+ trechos — usado tanto pelo formulário real
// (OrderModal) quanto pela geração de dados mockados, para as duas fontes nunca divergirem.
export function buildOrder(features, { priority, team, scheduledDate, notes, status = 'pendente', createdAt, id, by }) {
  const areaM2 = features.reduce((sum, f) => sum + (f.properties.area_m2 || 0), 0)
  const vegetationLevel = Math.max(...features.map(f => f.properties.vegetation_level || 1))
  const equipment = [...new Set(
    features.map(f => (EQUIPMENT_TYPES[f.properties.name] || { short: f.properties.name }).short)
  )]
  const createdAtFinal = createdAt || new Date().toISOString()

  return {
    id: id || generateOrderId(),
    createdAt: createdAtFinal,
    status,
    priority,
    team: team || '',
    scheduledDate: scheduledDate || '',
    notes: notes || '',
    equipment: equipment.join(' + '),
    area: formatArea(areaM2),
    areaM2,
    vegetationLevel,
    featureIds: features.map(getFeatureId),
    trechoCount: features.length,
    points: features.map(f => ({ lat: f.properties.centroid_lat, lon: f.properties.centroid_lon })),
    lat: features[0].properties.centroid_lat,
    lon: features[0].properties.centroid_lon,
    history: [{ status, at: createdAtFinal, by: by || null }],
  }
}
