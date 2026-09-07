import { getFeatureId } from './classification'

// Não temos histórico real por trecho (nenhum worker grava isso ainda) — esta é uma estimativa
// mockada, determinística por trecho, só para alimentar o scrubber de tempo do dashboard.
// Quanto mais semanas no passado, maior a chance de o trecho estar num nível pior (mato mais
// alto), guiado por um hash do id do trecho, e nunca "melhor" que o nível atual observado.
function hashString(str) {
  let h = 0
  for (let i = 0; i < str.length; i++) h = (h * 31 + str.charCodeAt(i)) >>> 0
  return h
}

export function estimateHistoricalLevel(feature, weeksAgo) {
  const currentLevel = feature.properties.vegetation_level || 1
  if (weeksAgo <= 0 || currentLevel >= 3) return currentLevel

  const r = (hashString(getFeatureId(feature)) % 1000) / 1000
  const worsenChance = Math.min(0.85, weeksAgo * 0.085)
  if (r >= worsenChance) return currentLevel

  if (currentLevel === 1) return r < worsenChance * 0.45 ? 3 : 2
  return 3
}

export function estimateHistoricalCounts(geojson, weeksAgo) {
  const counts = { 1: 0, 2: 0, 3: 0 }
  geojson?.features.forEach(f => { counts[estimateHistoricalLevel(f, weeksAgo)]++ })
  return counts
}
