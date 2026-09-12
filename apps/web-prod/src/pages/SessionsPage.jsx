import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { LEVELS, vegetationLevel, AnimatedNumber } from '@greenv/web-core'
import { sessions as sessionsApi, measurements } from '../api/greenv'
import PageShell from '../components/PageShell'

/**
 * Por onde o painel começa.
 *
 * Esta tela não existe na demonstração e não poderia existir antes: até a API ganhar uma rota de
 * listagem, não havia como descobrir o que tinha sido capturado sem já ter o identificador.
 */
const s = {
  header: { display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', marginBottom: 20 },
  title: { fontSize: 22, fontWeight: 700, color: 'var(--text-primary)' },
  subtitle: { fontSize: 13, color: 'var(--text-secondary)', marginTop: 2 },
  statsRow: { display: 'flex', gap: 12, marginBottom: 24 },
  statCard: (accent) => ({
    flex: 1, padding: '16px 18px', background: 'white',
    borderRadius: 'var(--radius-md)', border: '1px solid var(--border)',
    borderTop: `3px solid ${accent}`, boxShadow: 'var(--shadow-card)',
  }),
  statNumber: { fontSize: 28, fontWeight: 700, fontFamily: 'var(--font-mono)' },
  statLabel: { fontSize: 11, color: 'var(--text-muted)', marginTop: 2, textTransform: 'uppercase' },
  filtersRow: { display: 'flex', gap: 10, marginBottom: 20, alignItems: 'center' },
  toggle: (on) => ({
    padding: '8px 14px', borderRadius: 'var(--radius-sm)', fontSize: 13, fontWeight: 600,
    cursor: 'pointer', fontFamily: 'inherit',
    border: `1px solid ${on ? 'var(--motiva)' : 'var(--border)'}`,
    background: on ? 'var(--motiva-subtle)' : 'white',
    color: on ? 'var(--motiva)' : 'var(--text-secondary)',
  }),
  table: {
    width: '100%', background: 'white', borderRadius: 'var(--radius-md)',
    border: '1px solid var(--border)', borderCollapse: 'separate', borderSpacing: 0,
    overflow: 'hidden', boxShadow: 'var(--shadow-card)',
  },
  th: {
    padding: '12px 16px', textAlign: 'left', fontSize: 10, fontWeight: 700,
    color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em',
    background: 'var(--bg-secondary)', borderBottom: '1px solid var(--border)',
  },
  td: { padding: '14px 16px', fontSize: 13, borderBottom: '1px solid var(--border)' },
  row: { cursor: 'pointer' },
  mono: { fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--text-muted)' },
  pill: (level) => ({
    display: 'inline-flex', alignItems: 'center', gap: 6, padding: '3px 10px',
    borderRadius: 20, fontSize: 11, fontWeight: 600,
    color: LEVELS[level].color, background: LEVELS[level].bg,
  }),
  empty: { padding: 40, textAlign: 'center', color: 'var(--text-muted)', fontSize: 13 },
}

export default function SessionsPage() {
  const navigate = useNavigate()
  const [state, setState] = useState({ loading: true })
  const [measuredOnly, setMeasuredOnly] = useState(false)

  useEffect(() => {
    let live = true
    setState(previous => ({ ...previous, loading: true }))
    Promise.all([sessionsApi.list({ measuredOnly, limit: 50 }), measurements.list({ limit: 200 })])
      .then(([page, measured]) => { if (live) setState({ loading: false, page, measured }) })
      .catch(error => { if (live) setState({ loading: false, error }) })
    return () => { live = false }
  }, [measuredOnly])

  const { page, measured, loading, error } = state
  const overdue = (measured?.items ?? []).filter(m => vegetationLevel(m.measurementLevel) === 3).length

  return (
    <PageShell currentPage="sessions">
      <div style={s.header}>
        <div>
          <div style={s.title}>Sessões de captura</div>
          <div style={s.subtitle}>Cada rota gravada em campo, com o que já foi medido nela.</div>
        </div>
      </div>

      <div style={s.statsRow}>
        <div style={s.statCard('var(--motiva)')}>
          <div style={s.statNumber}><AnimatedNumber value={page?.total ?? 0} /></div>
          <div style={s.statLabel}>Sessões</div>
        </div>
        <div style={s.statCard('var(--accent)')}>
          <div style={s.statNumber}><AnimatedNumber value={measured?.total ?? 0} /></div>
          <div style={s.statLabel}>Trechos medidos</div>
        </div>
        <div style={s.statCard(LEVELS[3].color)}>
          <div style={s.statNumber}><AnimatedNumber value={overdue} /></div>
          <div style={s.statLabel}>Acima de 30 cm</div>
        </div>
      </div>

      <div style={s.filtersRow}>
        <button style={s.toggle(measuredOnly)} onClick={() => setMeasuredOnly(v => !v)}>
          Somente com medição
        </button>
      </div>

      {loading && <div style={s.empty}>Carregando…</div>}
      {error && <div style={{ ...s.empty, color: LEVELS[3].color }}>{error.message}</div>}

      {page && (
        <table style={s.table}>
          <thead>
            <tr>
              <th style={s.th}>Início</th>
              <th style={s.th}>Via</th>
              <th style={s.th}>Dispositivo</th>
              <th style={s.th}>Trechos</th>
              <th style={s.th}>Medidos</th>
            </tr>
          </thead>
          <tbody>
            {page.items.map(session => (
              <tr key={session.sessionId} style={s.row}
                onClick={() => navigate(`/sessoes/${session.sessionId}`)}>
                <td style={s.td}>{new Date(session.startedAt).toLocaleString('pt-BR')}</td>
                <td style={s.td}>
                  {session.rodovia
                    ? `${session.rodovia}${session.sentido ? ' · ' + session.sentido : ''}`
                    : <span style={{ color: 'var(--text-muted)' }}>não identificada</span>}
                </td>
                <td style={{ ...s.td, ...s.mono }}>{session.deviceId}</td>
                <td style={s.td}>{session.segmentCount}</td>
                <td style={s.td}>
                  <span style={s.pill(session.measuredSegmentCount > 0 ? 1 : 0)}>
                    {session.measuredSegmentCount} de {session.segmentCount}
                  </span>
                </td>
              </tr>
            ))}
            {page.items.length === 0 && (
              <tr><td style={{ ...s.td, ...s.empty }} colSpan={5}>
                Nenhuma sessão {measuredOnly ? 'com medição ' : ''}encontrada.
              </td></tr>
            )}
          </tbody>
        </table>
      )}
    </PageShell>
  )
}
