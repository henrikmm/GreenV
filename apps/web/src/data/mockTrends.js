import { startOfWeek, formatWeekLabel } from '../utils/date'

// Série semanal de área tratada — usada só no gráfico de progresso do dashboard.
// As últimas 10 semanas são calculadas a partir de hoje, para a demo nunca parecer "velha".
const WEEKLY_AREA_M2 = [9200, 10400, 8600, 12100, 14300, 13200, 16800, 15100, 18400, 19600]
const WEEKLY_OS = [4, 5, 3, 6, 7, 6, 8, 7, 9, 10]

export function generateWeeklyProgress() {
  const thisWeek = startOfWeek(new Date())
  const weeks = []
  for (let i = WEEKLY_AREA_M2.length - 1; i >= 0; i--) {
    const d = new Date(thisWeek)
    d.setDate(d.getDate() - i * 7)
    const idx = WEEKLY_AREA_M2.length - 1 - i
    weeks.push({
      week: formatWeekLabel(d),
      areaM2: WEEKLY_AREA_M2[idx],
      areaHa: +(WEEKLY_AREA_M2[idx] / 10000).toFixed(2),
      osCompleted: WEEKLY_OS[idx],
    })
  }
  return weeks
}
