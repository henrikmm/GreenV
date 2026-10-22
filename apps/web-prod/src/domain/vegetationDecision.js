// Project rules in metres; 10 cm and 30 cm belong to the attention band.
export const VEGETATION_RULES = [
  { level: 1, maxM: 0.10, exclusive: true, condition: 'Monitorado',
    priority: 'Baixa', orderPriority: 'baixa', range: 'Abaixo de 10 cm',
    action: 'Manter monitoramento periódico.' },
  { level: 2, maxM: 0.30, condition: 'Atenção',
    priority: 'Média', orderPriority: 'media', range: 'De 10 a 30 cm, inclusive',
    action: 'Programar vistoria e avaliar manutenção.' },
  { level: 3, maxM: Infinity, condition: 'Intervenção prioritária',
    priority: 'Alta', orderPriority: 'alta', range: 'Acima de 30 cm',
    action: 'Priorizar vistoria para planejar a roçada.' },
]

const REVIEW = {
  level: 0, condition: 'Revisão necessária', priority: 'A revisar', orderPriority: null,
  action: 'Revisar a evidência ou realizar nova coleta.',
}

export function classifyVegetation(point) {
  const heightM = point.measurementExtent95P95M
  // Preserve the API's evidence gate: unknown never means low priority.
  if (!Number.isFinite(heightM) || heightM < 0 || ![1, 2, 3].includes(point.measurementLevel)
    || (point.measurementCellsMeasured != null && point.measurementCellsMeasured < 20)) {
    return REVIEW
  }
  return VEGETATION_RULES.find(rule => rule.exclusive ? heightM < rule.maxM : heightM <= rule.maxM)
}

export function processVegetation(points) {
  return points.map(point => ({ ...point, decision: classifyVegetation(point) }))
}

export function recommendedOrderPriority(points) {
  const decisions = points.map(classifyVegetation)
  // An unclassified target requires a human choice, rather than a guessed priority.
  if (decisions.some(decision => decision.level === 0)) return ''
  return decisions.reduce((highest, decision) => decision.level > highest.level ? decision : highest,
    { level: 0, orderPriority: '' }).orderPriority
}
