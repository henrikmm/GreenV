import { useNavigate } from 'react-router-dom'
import { ChevronRight } from 'lucide-react'
import { NavBar } from '@greenv/web-core'
import { Card } from '@greenv/web-core'
import { Badge } from '@greenv/web-core'
import { TEAMS, TEAM_STATUS } from '../data/mockTeams'

const s = {
  page: { display: 'flex', flexDirection: 'column', height: '100vh', background: 'var(--bg-secondary)', overflow: 'hidden' },
  content: { flex: 1, overflowY: 'auto', padding: '28px 32px 40px' },
  header: { marginBottom: 22 },
  title: { fontSize: 22, fontWeight: 700 },
  subtitle: { fontSize: 13, color: 'var(--text-secondary)', marginTop: 3 },
  grid: { display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: 14 },
  card: { cursor: 'pointer' },
  cardHead: { display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 },
  avatar: (c) => ({
    width: 42, height: 42, borderRadius: '50%', background: c, color: 'white',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    fontSize: 14, fontWeight: 700, flexShrink: 0,
  }),
  name: { fontSize: 14.5, fontWeight: 700 },
  region: { fontSize: 12, color: 'var(--text-muted)', marginTop: 1 },
  chevron: { marginLeft: 'auto', color: 'var(--text-muted)' },
  statsRow: { display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 8, marginBottom: 12 },
  statBox: {
    padding: '8px 6px', background: 'var(--bg-secondary)', borderRadius: 'var(--radius-sm)', textAlign: 'center',
  },
  statValue: { fontSize: 17, fontWeight: 700, fontFamily: 'var(--font-mono)' },
  statLabel: { fontSize: 9.5, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.04em', marginTop: 2 },
}

export default function TeamsPage({ orders }) {
  const navigate = useNavigate()

  return (
    <div style={s.page}>
      <NavBar currentPage="teams" />
      <div style={s.content}>
        <div style={s.header}>
          <div style={s.title}>Equipes</div>
          <div style={s.subtitle}>Times de campo — Rodoanel Oeste (SP-021)</div>
        </div>

        <div style={s.grid}>
          {TEAMS.map((t, i) => {
            const teamOrders = orders.filter(o => o.team === t.name)
            const pendentes = teamOrders.filter(o => o.status === 'pendente').length
            const andamento = teamOrders.filter(o => o.status === 'em_andamento').length
            const concluidas = teamOrders.filter(o => o.status === 'concluida').length
            const st = TEAM_STATUS[t.status]

            return (
              <Card
                key={t.id} delay={i * 0.05} style={s.card}
                onClick={() => navigate(`/equipes/${t.id}`)}
                whileHover={{ y: -3, boxShadow: 'var(--shadow-float)' }}
              >
                <div style={s.cardHead}>
                  <div style={s.avatar(t.color)}>{t.initials}</div>
                  <div>
                    <div style={s.name}>{t.name}</div>
                    <div style={s.region}>{t.region}</div>
                  </div>
                  <ChevronRight size={16} style={s.chevron} />
                </div>

                <div style={s.statsRow}>
                  <div style={s.statBox}>
                    <div style={{ ...s.statValue, color: '#ca8a04' }}>{pendentes}</div>
                    <div style={s.statLabel}>Pendentes</div>
                  </div>
                  <div style={s.statBox}>
                    <div style={{ ...s.statValue, color: '#3b82f6' }}>{andamento}</div>
                    <div style={s.statLabel}>Em curso</div>
                  </div>
                  <div style={s.statBox}>
                    <div style={{ ...s.statValue, color: '#16a34a' }}>{concluidas}</div>
                    <div style={s.statLabel}>Concluídas</div>
                  </div>
                </div>

                <Badge color={st.color}>{st.label}</Badge>
              </Card>
            )
          })}
        </div>
      </div>
    </div>
  )
}
