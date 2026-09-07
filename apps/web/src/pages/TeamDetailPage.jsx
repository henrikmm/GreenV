import { useMemo } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { ArrowLeft, ClipboardList, CheckCircle2, Clock, Ruler } from 'lucide-react'
import NavBar from '../components/NavBar'
import Card from '../components/ui/Card'
import Badge from '../components/ui/Badge'
import AnimatedNumber from '../components/ui/AnimatedNumber'
import WeeklyBarChart from '../components/charts/WeeklyBarChart'
import { getTeam, TEAM_STATUS } from '../data/mockTeams'
import { STATUS_MAP, PRIORITY_MAP } from '../data/orderMeta'
import { bucketByWeek } from '../utils/date'
import { formatArea } from '../utils/classification'

const s = {
  page: { display: 'flex', flexDirection: 'column', height: '100vh', background: 'var(--bg-secondary)', overflow: 'hidden' },
  content: { flex: 1, overflowY: 'auto', padding: '28px 32px 40px' },
  backBtn: {
    display: 'flex', alignItems: 'center', gap: 6, fontSize: 12.5, fontWeight: 600,
    color: 'var(--text-secondary)', background: 'none', border: 'none', cursor: 'pointer',
    fontFamily: 'inherit', marginBottom: 16, padding: 0,
  },
  header: { display: 'flex', alignItems: 'center', gap: 16, marginBottom: 24 },
  avatar: (c) => ({
    width: 56, height: 56, borderRadius: '50%', background: c, color: 'white',
    display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 18, fontWeight: 700, flexShrink: 0,
  }),
  name: { fontSize: 21, fontWeight: 700 },
  region: { fontSize: 13, color: 'var(--text-secondary)', marginTop: 2 },
  kpiGrid: { display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginBottom: 20 },
  kpiIcon: (bg, c) => ({
    width: 32, height: 32, borderRadius: 9, background: bg, color: c,
    display: 'flex', alignItems: 'center', justifyContent: 'center', marginBottom: 12,
  }),
  kpiValue: { fontSize: 23, fontWeight: 700, fontFamily: 'var(--font-mono)' },
  kpiLabel: { fontSize: 11.5, color: 'var(--text-muted)', marginTop: 3 },
  cardTitle: { fontSize: 13, fontWeight: 700, marginBottom: 2 },
  cardHint: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 14 },
  grid2: { display: 'grid', gridTemplateColumns: '1fr 1.4fr', gap: 12 },
  table: {
    width: '100%', borderCollapse: 'separate', borderSpacing: 0,
  },
  th: {
    padding: '8px 10px', textAlign: 'left', fontSize: 9.5, fontWeight: 700,
    color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em',
    borderBottom: '1px solid var(--border)',
  },
  td: { padding: '10px 10px', fontSize: 12.5, borderBottom: '1px solid var(--border)' },
  empty: { padding: '30px 10px', textAlign: 'center', color: 'var(--text-muted)', fontSize: 13 },
  notFound: { padding: 60, textAlign: 'center', color: 'var(--text-muted)' },
}

const KPI_ACCENTS = ['#ca8a04', '#3b82f6', '#16a34a', '#0ea5a0']

export default function TeamDetailPage({ orders }) {
  const { teamId } = useParams()
  const navigate = useNavigate()
  const team = getTeam(teamId)

  const teamOrders = useMemo(
    () => orders.filter(o => o.team === team?.name).sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt)),
    [orders, team]
  )

  const weekly = useMemo(() => {
    const concludedDates = teamOrders.filter(o => o.status === 'concluida').map(o => o.createdAt)
    return bucketByWeek(concludedDates)
  }, [teamOrders])

  if (!team) {
    return (
      <div style={s.page}>
        <NavBar currentPage="teams" />
        <div style={s.notFound}>Equipe não encontrada. <button style={s.backBtn} onClick={() => navigate('/equipes')}>Voltar</button></div>
      </div>
    )
  }

  const pendentes = teamOrders.filter(o => o.status === 'pendente').length
  const andamento = teamOrders.filter(o => o.status === 'em_andamento').length
  const concluidas = teamOrders.filter(o => o.status === 'concluida')
  const areaTratadaM2 = concluidas.reduce((sum, o) => sum + (o.areaM2 || 0), 0)
  const st = TEAM_STATUS[team.status]

  const kpis = [
    { icon: ClipboardList, label: 'OS pendentes', value: pendentes },
    { icon: Clock, label: 'Em andamento', value: andamento },
    { icon: CheckCircle2, label: 'Concluídas', value: concluidas.length },
    { icon: Ruler, label: 'Área tratada (total)', value: areaTratadaM2 / 10000, decimals: 2, suffix: ' ha' },
  ]

  return (
    <div style={s.page}>
      <NavBar currentPage="teams" />
      <div style={s.content}>
        <button style={s.backBtn} onClick={() => navigate('/equipes')}>
          <ArrowLeft size={14} /> Equipes
        </button>

        <div style={s.header}>
          <div style={s.avatar(team.color)}>{team.initials}</div>
          <div>
            <div style={s.name}>{team.name}</div>
            <div style={s.region}>{team.region}</div>
          </div>
          <div style={{ marginLeft: 'auto' }}>
            <Badge color={st.color}>{st.label}</Badge>
          </div>
        </div>

        <div style={s.kpiGrid}>
          {kpis.map((k, i) => {
            const Icon = k.icon
            const accent = KPI_ACCENTS[i]
            return (
              <Card key={k.label} delay={i * 0.05}>
                <div style={s.kpiIcon(`${accent}18`, accent)}><Icon size={16} /></div>
                <div style={s.kpiValue}>
                  <AnimatedNumber value={k.value} decimals={k.decimals || 0} suffix={k.suffix || ''} />
                </div>
                <div style={s.kpiLabel}>{k.label}</div>
              </Card>
            )
          })}
        </div>

        <div style={s.grid2}>
          <Card delay={0.2}>
            <div style={s.cardTitle}>OS concluídas por semana</div>
            <div style={s.cardHint}>Últimas 10 semanas</div>
            <WeeklyBarChart data={weekly} color={team.color} />
          </Card>

          <Card delay={0.25}>
            <div style={s.cardTitle}>Ordens de serviço</div>
            <div style={s.cardHint}>Histórico desta equipe, mais recentes primeiro</div>
            {teamOrders.length === 0 ? (
              <div style={s.empty}>Nenhuma OS atribuída a esta equipe ainda.</div>
            ) : (
              <div style={{ maxHeight: 280, overflowY: 'auto' }}>
                <table style={s.table}>
                  <thead>
                    <tr>
                      <th style={s.th}>ID</th>
                      <th style={s.th}>Prioridade</th>
                      <th style={s.th}>Status</th>
                      <th style={s.th}>Área</th>
                    </tr>
                  </thead>
                  <tbody>
                    {teamOrders.slice(0, 12).map(o => {
                      const stO = STATUS_MAP[o.status] || STATUS_MAP.pendente
                      const pr = PRIORITY_MAP[o.priority] || PRIORITY_MAP.media
                      return (
                        <tr key={o.id}>
                          <td style={{ ...s.td, fontFamily: 'var(--font-mono)', fontSize: 11 }}>{o.id}</td>
                          <td style={s.td}><Badge color={pr.color}>{pr.label}</Badge></td>
                          <td style={s.td}><Badge color={stO.color}>{stO.label}</Badge></td>
                          <td style={{ ...s.td, fontFamily: 'var(--font-mono)' }}>{o.area}</td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
        </div>
      </div>
    </div>
  )
}
