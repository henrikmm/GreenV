export function startOfWeek(date) {
  const d = new Date(date)
  const day = d.getDay()
  const diff = (day === 0 ? -6 : 1) - day
  d.setDate(d.getDate() + diff)
  d.setHours(0, 0, 0, 0)
  return d
}

export function formatWeekLabel(date) {
  return date.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' })
}

// Agrupa datas ISO em N semanas terminando na semana atual — usado para gráficos de
// produtividade construídos a partir de ordens reais (não mockadas).
export function bucketByWeek(isoDates, weekCount = 10) {
  const thisWeek = startOfWeek(new Date())
  const weeks = []
  for (let i = weekCount - 1; i >= 0; i--) {
    const start = new Date(thisWeek)
    start.setDate(start.getDate() - i * 7)
    const end = new Date(start)
    end.setDate(end.getDate() + 7)
    const count = isoDates.filter(d => { const t = new Date(d); return t >= start && t < end }).length
    weeks.push({ week: formatWeekLabel(start), count })
  }
  return weeks
}
