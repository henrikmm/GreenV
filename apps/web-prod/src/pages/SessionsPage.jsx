import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Route, Ruler, TriangleAlert, Camera } from 'lucide-react'
import { LEVELS, vegetationLevel, AnimatedNumber, Card, useAuth } from '@greenv/web-core'
import { sessions as sessionsApi, measurements } from '../api/greenv'
import PageShell from '../components/PageShell'

/**
 * Por onde o painel começa.
 *
 * Esta tela não existe na demonstração e não poderia existir antes: até a API ganhar uma rota de
 * listagem, não havia como descobrir o que tinha sido capturado sem já ter o identificador. O
 * arranjo é o da visão geral da demo — indicadores no topo, o resto abaixo — porque é o mesmo
 * produto.
 */
const KPI_ACCENTS = ['#5e22f3', '#0ea5a0', '#dc2626', '#ca8a04']

const s = {
  header: { marginBottom: 22 },
  greeting: { fontSize: 22, fontWeight: 700 },
  subtitle: { fontSize: 13, color: 'var(--text-secondary)', marginTop: 3 },
  kpiGrid: { display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginBottom: 20 },
  kpiIcon: (bg, c) => ({
    width: 32, height: 32, borderRadius: 9, background: bg, color: c,
    display: 'flex', alignItems: 'center', justifyContent: 'center', marginBottom: 12,
  }),
  kpiValue: { fontSize: 25, fontWeight: 700, fontFamily: 'var(--font-mono)' },
  kpiLabel: { fontSize: 11.5, color: 'var(--text-muted)', marginTop: 3 },
  cardTitle: { fontSize: 13, fontWeight: 700, marginBottom: 2 },
  cardHint: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 14 },
  toolbar: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 },
  toggle: (on) => ({
    padding: '7px 13px', borderRadius: 'var(--radius-sm)', fontSize: 12.5, fontWeight: 600,
    cursor: 'pointer', fontFamily: 'inherit',
    border: `1px solid ${on ? 'var(--motiva)' : 'var(--border)'}`,
    background: on ? 'var(--motiva-subtle)' : 'white',
    color: on ? 'var(--motiva)' : 'var(--text-secondary)',
  }),
  table: { width: '100%', borderCollapse: 'separate', borderSpacing: 0 },
  th: {
    padding: '10px 12px', textAlign: 'left', fontSize: 10, fontWeight: 700,
    color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em',
    borderBottom: '1px solid var(--border)',
  },
  td: { padding: '13px 12px', fontSize: 12.5, borderBottom: '1px solid var(--border)' },
  row: { cursor: 'pointer' },
  mono: { fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--text-muted)' },
  pill: (color, bg) => ({
    display: 'inline-flex', alignItems: 'center', gap: 6, padding: '3px 10px',
    borderRadius: 20, fontSize: 11, fontWeight: 600, color, background: bg,
  }),
  empty: { padding: 34, textAlign: 'center', color: 'var(--text-muted)', fontSize: 12.5 },
}

export default function SessionsPage() {
  const navigate = useNavigate()
  const { user } = useAuth()
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
  const measuredItems = measured?.items ?? []
  const overdue = measuredItems.filter(m => vegetationLevel(m.measurementLevel) === 3).length
  const tallest = measuredItems.reduce(
    (highest, m) => Math.max(highest, m.measurementExtent95P95M ?? 0), 0)

  const kpis = [
    { icon: Route, label: 'Sessões capturadas', value: page?.total ?? 0 },
    { icon: Ruler, label: 'Trechos medidos', value: measured?.total ?? 0 },
    { icon: TriangleAlert, label: 'Acima de 30 cm', value: overdue },
    { icon: Camera, label: 'Maior altura p95', value: tallest * 100, decimals: 0, suffix: ' cm' },
  ]

  return (
    <PageShell currentPage="sessions">
      <div style={s.header}>
        <div style={s.greeting}>Olá, {user?.name?.split(' ')[0] || 'operador'} 👋</div>
        <div style={s.subtitle}>Cada rota gravada em campo, com o que já foi medido nela.</div>
      </div>

      <div style={s.kpiGrid}>
        {kpis.map((k, i) => {
          const Icon = k.icon
          const accent = KPI_ACCENTS[i]
          return (
            <Card key={k.label} delay={i * 0.05}>
              <div style={s.kpiIcon(`${accent}18`, accent)}><Icon size={16} /></div>
              <div style={s.kpiValue}>
                <AnimatedNumber value={k.value} decimals={k.decimals || 0} suffix={k.suffix ?? ''} />
              </div>
              <div style={s.kpiLabel}>{k.label}</div>
            </Card>
          )
        })}
      </div>

      <Card delay={0.2} style={{ padding: '18px 18px 4px' }}>
        <div style={s.cardTitle}>Sessões de captura</div>
        <div style={s.cardHint}>Clique numa linha para ver a trilha e os quadros daquela sessão.</div>

        <div style={s.toolbar}>
          <button style={s.toggle(measuredOnly)} onClick={() => setMeasuredOnly(v => !v)}>
            Somente com medição
          </button>
        </div>

        {loading && <div style={s.empty}>Carregando…</div>}
        {error && <div style={{ ...s.empty, color: LEVELS[3].color }}>{error.message}</div>}

        {page && !loading && (
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
                    {/* Cinza quando nada foi medido: um zero em verde leria como conformidade. */}
                    <span style={session.measuredSegmentCount > 0
                      ? s.pill('var(--motiva)', 'var(--motiva-subtle)')
                      : s.pill(LEVELS[0].color, LEVELS[0].bg)}>
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
      </Card>
    </PageShell>
  )
}
