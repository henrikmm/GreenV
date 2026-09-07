import { suggestCombinedRoutes } from '../utils/routePlanner'
import { buildOrder } from '../utils/orders'
import { TEAMS } from './mockTeams'

const STATUS_PATTERN = [
  'pendente', 'em_andamento', 'concluida', 'pendente', 'concluida',
  'em_andamento', 'concluida', 'pendente', 'cancelada', 'concluida',
  'pendente', 'em_andamento', 'concluida', 'pendente', 'concluida',
  'em_andamento', 'pendente', 'concluida',
]

const NOTES_POOL = [
  'Acesso pela faixa de domínio, sinalização já providenciada.',
  'Equipamento pesado — necessário apoio de trânsito no local.',
  'Reincidência: mesmo trecho tratado há 3 meses.',
  'Trecho próximo a dispositivo de drenagem, atenção redobrada.',
  'Vegetação alta reduzindo visibilidade de placas.',
  '',
  '',
  'Solicitado pela fiscalização de pista.',
]

function priorityForLevel(level) {
  if (level >= 3) return 'alta'
  if (level === 2) return 'media'
  return 'baixa'
}

function daysAgoIso(days) {
  const d = new Date()
  d.setDate(d.getDate() - days)
  return d.toISOString()
}

function pick(arr, i) { return arr[i % arr.length] }

const FLOW = ['pendente', 'em_andamento', 'concluida']

// Sintetiza um histórico plausível de mudanças de status entre a criação e o status atual —
// as ordens mockadas não nascem já "Concluídas", elas passam pelos passos intermediários.
function seedHistory(order) {
  if (order.status === 'cancelada') {
    return [
      { status: 'pendente', at: order.createdAt, by: null },
      { status: 'cancelada', at: order.createdAt, by: 'Sistema' },
    ]
  }
  const targetIdx = FLOW.indexOf(order.status)
  if (targetIdx <= 0) return order.history

  const start = new Date(order.createdAt).getTime()
  const span = Math.max(Date.now() - start, 86400000)
  const hist = []
  for (let i = 0; i <= targetIdx; i++) {
    hist.push({
      status: FLOW[i],
      at: new Date(start + (span * i) / (targetIdx + 1)).toISOString(),
      by: i === 0 ? null : (order.team || 'Equipe'),
    })
  }
  return hist
}

// Gera um conjunto plausível de ordens de serviço a partir dos trechos reais do geojson —
// só é usado para semear o localStorage na primeira carga (ver App.jsx).
export function generateMockOrders(geojson, marcoKm) {
  if (!geojson) return []

  const combos = suggestCombinedRoutes(geojson, [], marcoKm).slice(0, 3)
  const usedIds = new Set(combos.flatMap(c => c.features.map(f => `${f.properties.centroid_lat}-${f.properties.centroid_lon}`)))

  const singleCandidates = geojson.features.filter(
    f => !usedIds.has(`${f.properties.centroid_lat}-${f.properties.centroid_lon}`)
  )
  const step = Math.floor(singleCandidates.length / 15) || 1
  const singles = []
  for (let i = 0; singles.length < 15 && i < singleCandidates.length; i += step) {
    singles.push(singleCandidates[i])
  }

  const groups = [...combos.map(c => c.features), ...singles.map(f => [f])]

  const orders = groups.map((features, i) => {
    const status = pick(STATUS_PATTERN, i)
    const level = Math.max(...features.map(f => f.properties.vegetation_level || 1))
    const team = status === 'pendente' && i % 3 === 0 ? '' : TEAMS[i % TEAMS.length].name
    const createdAt = daysAgoIso(2 + i * 3)
    const scheduled = new Date(createdAt)
    scheduled.setDate(scheduled.getDate() + (status === 'concluida' ? 4 : 9))

    const order = buildOrder(features, {
      priority: level >= 3 && i % 4 === 0 ? 'urgente' : priorityForLevel(level),
      team,
      scheduledDate: scheduled.toISOString().slice(0, 10),
      notes: pick(NOTES_POOL, i),
      status,
      createdAt,
      id: `OS-ROÇ-${createdAt.slice(0, 4)}${createdAt.slice(5, 7)}-${1000 + i * 37}`,
    })
    order.history = seedHistory(order)
    return order
  })

  return orders.sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
}
