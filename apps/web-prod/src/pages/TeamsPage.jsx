import { useEffect, useState } from 'react'
import { Card, Badge, LEVELS } from '@greenv/web-core'
import { teams as teamsApi } from '../api/greenv'
import PageShell from '../components/PageShell'

/**
 * As equipes de campo e o que cada uma está carregando.
 *
 * Os três contadores vêm com a linha, de uma consulta só na API. A demonstração os calcula no
 * navegador porque tem todas as ordens em memória; aqui isso seria uma ida e volta por contador
 * por equipe.
 */
const TEAM_STATUS = {
  em_campo: { label: 'Em campo', color: '#16a34a' },
  disponivel: { label: 'Disponível', color: '#3b82f6' },
  fora_servico: { label: 'Fora de serviço', color: '#9d9db0' },
}

const s = {
  header: { marginBottom: 22 },
  title: { fontSize: 22, fontWeight: 700 },
  subtitle: { fontSize: 13, color: 'var(--text-secondary)', marginTop: 3 },
  grid: { display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: 14 },
  cardHead: { display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 },
  avatar: (colour) => ({
    width: 42, height: 42, borderRadius: '50%', background: colour, color: 'white',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    fontSize: 14, fontWeight: 700, flexShrink: 0,
  }),
  name: { fontSize: 14.5, fontWeight: 700 },
  region: { fontSize: 12, color: 'var(--text-muted)', marginTop: 1 },
  statsRow: { display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 8, marginBottom: 12 },
  statBox: {
    padding: '8px 6px', background: 'var(--bg-secondary)',
    borderRadius: 'var(--radius-sm)', textAlign: 'center',
  },
  statValue: { fontSize: 17, fontWeight: 700, fontFamily: 'var(--font-mono)' },
  statLabel: {
    fontSize: 9.5, color: 'var(--text-muted)', textTransform: 'uppercase',
    letterSpacing: '0.04em', marginTop: 2,
  },
  state: { padding: 40, textAlign: 'center', color: 'var(--text-muted)', fontSize: 13 },
}

export default function TeamsPage() {
  const [state, setState] = useState({ loading: true, teams: [] })

  useEffect(() => {
    let live = true
    teamsApi.list()
      .then(teams => { if (live) setState({ loading: false, teams }) })
      .catch(error => { if (live) setState({ loading: false, teams: [], error }) })
    return () => { live = false }
  }, [])

  const { teams, loading, error } = state

  return (
    <PageShell currentPage="teams">
      <div style={s.header}>
        <div style={s.title}>Equipes</div>
        <div style={s.subtitle}>Times de campo e as ordens que cada um carrega.</div>
      </div>

      {loading && <div style={s.state}>Carregando equipes…</div>}
      {error && <div style={{ ...s.state, color: LEVELS[3].color }}>{error.message}</div>}

      <div style={s.grid}>
        {teams.map((team, index) => {
          const status = TEAM_STATUS[team.status] ?? { label: team.status, color: '#9d9db0' }
          return (
            <Card key={team.teamId} delay={index * 0.05}>
              <div style={s.cardHead}>
                <div style={s.avatar(team.colour)}>{team.initials}</div>
                <div>
                  <div style={s.name}>{team.name}</div>
                  <div style={s.region}>{team.region ?? 'sem região definida'}</div>
                </div>
              </div>

              <div style={s.statsRow}>
                <div style={s.statBox}>
                  <div style={{ ...s.statValue, color: '#ca8a04' }}>{team.pendingOrders}</div>
                  <div style={s.statLabel}>Pendentes</div>
                </div>
                <div style={s.statBox}>
                  <div style={{ ...s.statValue, color: '#3b82f6' }}>{team.inProgressOrders}</div>
                  <div style={s.statLabel}>Em curso</div>
                </div>
                <div style={s.statBox}>
                  <div style={{ ...s.statValue, color: '#16a34a' }}>{team.completedOrders}</div>
                  <div style={s.statLabel}>Concluídas</div>
                </div>
              </div>

              <Badge color={status.color}>{status.label}</Badge>
            </Card>
          )
        })}
      </div>
    </PageShell>
  )
}
