import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { MapPin, ClipboardList, CheckCircle2, Ruler, ShieldCheck, ArrowUpRight } from 'lucide-react'
import NavBar from '../components/NavBar'
import Card from '../components/ui/Card'
import AnimatedNumber from '../components/ui/AnimatedNumber'
import TimeScrubber from '../components/TimeScrubber'
import ProgressChart from '../components/charts/ProgressChart'
import LevelDonut from '../components/charts/LevelDonut'
import TeamBarChart from '../components/charts/TeamBarChart'
import MiniCorridorMap from '../components/charts/MiniCorridorMap'
import { EQUIPMENT_TYPES, formatArea } from '../utils/classification'
import { TEAMS } from '../data/mockTeams'
import { generateWeeklyProgress } from '../data/mockTrends'
import { estimateHistoricalCounts } from '../utils/vegetationHistory'
import { useAuth } from '../context/AuthContext'

const s = {
  page: { display: 'flex', flexDirection: 'column', height: '100vh', background: 'var(--bg-secondary)', overflow: 'hidden' },
  content: { flex: 1, overflowY: 'auto', padding: '28px 32px 40px' },
  header: { marginBottom: 22 },
  greeting: { fontSize: 22, fontWeight: 700 },
  subtitle: { fontSize: 13, color: 'var(--text-secondary)', marginTop: 3 },
  kpiGrid: { display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: 12, marginBottom: 20 },
  kpiIcon: (bg, c) => ({
    width: 32, height: 32, borderRadius: 9, background: bg, color: c,
    display: 'flex', alignItems: 'center', justifyContent: 'center', marginBottom: 12,
  }),
  kpiValue: { fontSize: 25, fontWeight: 700, fontFamily: 'var(--font-mono)' },
  kpiLabel: { fontSize: 11.5, color: 'var(--text-muted)', marginTop: 3 },
  chartsGrid: { display: 'grid', gridTemplateColumns: '1.6fr 1fr', gap: 12, marginBottom: 12 },
  cardTitle: { fontSize: 13, fontWeight: 700, marginBottom: 2 },
  cardHint: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 14 },
  criticalRow: {
    display: 'flex', alignItems: 'center', gap: 10, padding: '9px 4px',
    borderBottom: '1px solid var(--border)',
  },
  criticalDot: { width: 8, height: 8, borderRadius: '50%', background: 'var(--level-3)', flexShrink: 0 },
  criticalName: { fontSize: 12.5, fontWeight: 600, flex: 1 },
  criticalArea: { fontSize: 11.5, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' },
  linkBtn: {
    display: 'flex', alignItems: 'center', gap: 4, fontSize: 12, fontWeight: 600,
    color: 'var(--motiva)', background: 'none', border: 'none', cursor: 'pointer',
    fontFamily: 'inherit', marginTop: 10,
  },
  timelineCard: { display: 'grid', gridTemplateColumns: '1.4fr 1fr', gap: 20, marginBottom: 12 },
  timelineStats: { display: 'flex', flexDirection: 'column', gap: 10, justifyContent: 'center' },
  timelineStatRow: {
    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    padding: '10px 12px', background: 'var(--bg-secondary)', borderRadius: 'var(--radius-sm)',
  },
  timelineDot: (c) => ({ width: 9, height: 9, borderRadius: '50%', background: c, flexShrink: 0 }),
  timelineLabel: { display: 'flex', alignItems: 'center', gap: 8, fontSize: 12.5, color: 'var(--text-secondary)' },
  timelineValue: { fontSize: 15, fontWeight: 700, fontFamily: 'var(--font-mono)' },
}

const KPI_ACCENTS = ['#5e22f3', '#16a34a', '#dc2626', '#0ea5a0', '#3b82f6']

export default function DashboardPage({ geojson, orders }) {
  const { user } = useAuth()
  const navigate = useNavigate()
  const weekly = useMemo(() => generateWeeklyProgress(), [])
  const [weekIndex, setWeekIndex] = useState(weekly.length - 1)
  const weeksAgo = weekly.length - 1 - weekIndex

  const historicalCounts = useMemo(
    () => estimateHistoricalCounts(geojson, weeksAgo),
    [geojson, weeksAgo]
  )

  const stats = useMemo(() => {
    const counts = { 1: 0, 2: 0, 3: 0 }
    geojson?.features.forEach(f => { counts[f.properties.vegetation_level || 1]++ })
    const total = geojson?.features.length || 0
    const conformidade = total ? (counts[1] / total) * 100 : 0

    const cutoff = new Date()
    cutoff.setDate(cutoff.getDate() - 30)
    const recent = orders.filter(o => new Date(o.createdAt) >= cutoff)
    const pendentes = orders.filter(o => o.status === 'pendente').length
    const concluidasRecentes = recent.filter(o => o.status === 'concluida')
    const areaTratadaM2 = concluidasRecentes.reduce((sum, o) => sum + (o.areaM2 || 0), 0)

    const teamData = TEAMS.map(t => ({
      id: t.id, short: t.short, color: t.color,
      completed: orders.filter(o => o.status === 'concluida' && o.team === t.name).length,
    }))

    const criticalPoints = (geojson?.features || [])
      .filter(f => (f.properties.vegetation_level || 1) === 3)
      .sort((a, b) => b.properties.area_m2 - a.properties.area_m2)
      .slice(0, 5)

    return { counts, total, conformidade, pendentes, concluidasRecentes: concluidasRecentes.length, areaTratadaM2, teamData, criticalPoints }
  }, [geojson, orders])

  const kpis = [
    { icon: MapPin, label: 'Trechos mapeados', value: stats.total, suffix: '' },
    { icon: ShieldCheck, label: 'Conformidade (nível 1)', value: stats.conformidade, decimals: 1, suffix: '%' },
    { icon: ClipboardList, label: 'OS pendentes', value: stats.pendentes, suffix: '' },
    { icon: CheckCircle2, label: 'OS concluídas (30 dias)', value: stats.concluidasRecentes, suffix: '' },
    { icon: Ruler, label: 'Área tratada (30 dias)', value: stats.areaTratadaM2 / 10000, decimals: 2, suffix: ' ha' },
  ]

  return (
    <div style={s.page}>
      <NavBar currentPage="dashboard" />
      <div style={s.content}>
        <div style={s.header}>
          <div style={s.greeting}>Olá, {user?.name?.split(' ')[0] || 'operador'} 👋</div>
          <div style={s.subtitle}>Visão geral da operação — Rodoanel Oeste (SP-021)</div>
        </div>

        <div style={s.kpiGrid}>
          {kpis.map((k, i) => {
            const Icon = k.icon
            const accent = KPI_ACCENTS[i]
            return (
              <Card key={k.label} delay={i * 0.05}>
                <div style={s.kpiIcon(`${accent}18`, accent)}><Icon size={16} /></div>
                <div style={s.kpiValue}>
                  <AnimatedNumber value={k.value} decimals={k.decimals || 0} suffix={k.suffix} />
                </div>
                <div style={s.kpiLabel}>{k.label}</div>
              </Card>
            )
          })}
        </div>

        <Card delay={0.1} style={{ marginBottom: 12 }}>
          <div style={s.cardTitle}>Linha do tempo da via</div>
          <div style={s.cardHint}>Arraste para ver como a rodovia estava em semanas anteriores</div>
          <TimeScrubber weeks={weekly} value={weekIndex} onChange={setWeekIndex} />
          <div style={{ ...s.timelineCard, marginTop: 14, marginBottom: 0 }}>
            <MiniCorridorMap geojson={geojson} weeksAgo={weeksAgo} height={220} />
            <div style={s.timelineStats}>
              <div style={s.timelineStatRow}>
                <span style={s.timelineLabel}><span style={s.timelineDot('#16a34a')} /> Nível 1 — conforme</span>
                <span style={s.timelineValue}>{historicalCounts[1]}</span>
              </div>
              <div style={s.timelineStatRow}>
                <span style={s.timelineLabel}><span style={s.timelineDot('#ca8a04')} /> Nível 2 — atenção</span>
                <span style={s.timelineValue}>{historicalCounts[2]}</span>
              </div>
              <div style={s.timelineStatRow}>
                <span style={s.timelineLabel}><span style={s.timelineDot('#dc2626')} /> Nível 3 — crítico</span>
                <span style={s.timelineValue}>{historicalCounts[3]}</span>
              </div>
              <div style={{ ...s.timelineStatRow, background: 'var(--motiva-subtle)' }}>
                <span style={{ ...s.timelineLabel, color: 'var(--motiva)', fontWeight: 700 }}>Conformidade</span>
                <span style={{ ...s.timelineValue, color: 'var(--motiva)' }}>
                  {stats.total ? ((historicalCounts[1] / stats.total) * 100).toFixed(1).replace('.', ',') : '0,0'}%
                </span>
              </div>
            </div>
          </div>
        </Card>

        <div style={s.chartsGrid}>
          <Card delay={0.15}>
            <div style={s.cardTitle}>Progresso de roçada</div>
            <div style={s.cardHint}>Área tratada por semana, últimas 10 semanas</div>
            <ProgressChart data={weekly} />
          </Card>
          <Card delay={0.2}>
            <div style={s.cardTitle}>Trechos por nível</div>
            <div style={s.cardHint}>Classificação atual de altura da vegetação</div>
            <LevelDonut counts={stats.counts} total={stats.total} />
          </Card>
        </div>

        <div style={s.chartsGrid}>
          <Card delay={0.25}>
            <div style={s.cardTitle}>Pontos críticos</div>
            <div style={s.cardHint}>Maiores áreas em nível 3 — prioridade máxima</div>
            {stats.criticalPoints.map((f, i) => {
              const eq = EQUIPMENT_TYPES[f.properties.name] || { short: f.properties.name }
              return (
                <div key={i} style={s.criticalRow}>
                  <span style={s.criticalDot} />
                  <span style={s.criticalName}>{eq.short}</span>
                  <span style={s.criticalArea}>{formatArea(f.properties.area_m2)}</span>
                </div>
              )
            })}
            <button style={s.linkBtn} onClick={() => navigate('/mapa')}>
              Ver no mapa <ArrowUpRight size={13} />
            </button>
          </Card>
          <Card delay={0.3}>
            <div style={s.cardTitle}>Produtividade por equipe</div>
            <div style={s.cardHint}>OS concluídas, total acumulado — clique numa equipe para abrir o perfil</div>
            <TeamBarChart data={stats.teamData} onBarClick={(id) => navigate(`/equipes/${id}`)} />
          </Card>
        </div>
      </div>
    </div>
  )
}
