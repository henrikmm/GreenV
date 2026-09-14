import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Route, Ruler, TriangleAlert, Camera, ArrowUpRight } from 'lucide-react'
import {
  LEVELS, vegetationLevel, AnimatedNumber, Card, useAuth,
  LevelDonut, WeeklyBarChart, bucketByWeek, dayKey, dayLabel,
} from '@greenv/web-core'
import { sessions as sessionsApi, measurements } from '../api/greenv'
import { placeOfSegment, placeOfSession } from '../api/place'
import PageShell from '../components/PageShell'

/**
 * Por onde o painel começa.
 *
 * Esta tela não existe na demonstração e não poderia existir antes: até a API ganhar uma rota de
 * listagem, não havia como descobrir o que tinha sido capturado sem já ter o identificador. O
 * arranjo é o da visão geral da demo — indicadores, dois gráficos, dois cartões, a tabela —
 * porque é o mesmo produto. O que muda é a origem: aqui cada número sai de uma medição que
 * existe, e nenhum é semeado.
 */
const KPI_ACCENTS = ['#5e22f3', '#0ea5a0', '#dc2626', '#ca8a04']

const s = {
  header: { marginBottom: 22 },
  greeting: { fontSize: 22, fontWeight: 700 },
  subtitle: { fontSize: 13, color: 'var(--text-secondary)', marginTop: 3 },
  kpiGrid: { display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginBottom: 12 },
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
  criticalDot: (c) => ({ width: 8, height: 8, borderRadius: '50%', background: c, flexShrink: 0 }),
  criticalName: { fontSize: 12.5, fontWeight: 600, flex: 1 },
  criticalValue: { fontSize: 11.5, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' },
  linkBtn: {
    display: 'flex', alignItems: 'center', gap: 4, fontSize: 12, fontWeight: 600,
    color: 'var(--motiva)', background: 'none', border: 'none', cursor: 'pointer',
    fontFamily: 'inherit', marginTop: 10, padding: 0,
  },
  statRow: {
    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    padding: '10px 12px', background: 'var(--bg-secondary)', borderRadius: 'var(--radius-sm)',
    marginBottom: 8,
  },
  statLabel: { display: 'flex', alignItems: 'center', gap: 8, fontSize: 12.5, color: 'var(--text-secondary)' },
  statValue: { fontSize: 15, fontWeight: 700, fontFamily: 'var(--font-mono)' },
  toolbar: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14, flexWrap: 'wrap' },
  daySelect: {
    padding: '7px 11px', background: 'white', border: '1px solid var(--border)',
    borderRadius: 'var(--radius-sm)', fontSize: 12.5, fontFamily: 'inherit',
    color: 'var(--text-primary)', outline: 'none', cursor: 'pointer',
  },
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
  const [filterDay, setFilterDay] = useState('')

  useEffect(() => {
    let live = true
    setState(previous => ({ ...previous, loading: true }))
    Promise.all([sessionsApi.list({ measuredOnly, limit: 50 }), measurements.list({ limit: 200 })])
      .then(([page, measured]) => { if (live) setState({ loading: false, page, measured }) })
      .catch(error => { if (live) setState({ loading: false, error }) })
    return () => { live = false }
  }, [measuredOnly])

  const { page, measured, loading, error } = state
  const measuredItems = useMemo(() => measured?.items ?? [], [measured])

  const counts = useMemo(() => {
    const byLevel = { 1: 0, 2: 0, 3: 0 }
    for (const segment of measuredItems) {
      const level = vegetationLevel(segment.measurementLevel)
      if (level > 0) byLevel[level] += 1
    }
    return byLevel
  }, [measuredItems])

  const tallest = useMemo(() => [...measuredItems]
    .filter(segment => segment.measurementExtent95P95M != null)
    .sort((a, b) => b.measurementExtent95P95M - a.measurementExtent95P95M)
    .slice(0, 5), [measuredItems])

  // A precisão do GPS é parte da leitura, não um detalhe de infraestrutura: um trecho medido a
  // partir de fixes de dezoito metros nao vale o mesmo que um medido a partir de cinco.
  const byQuality = useMemo(() => {
    const tally = {}
    for (const segment of measuredItems) {
      const quality = segment.trackLocationQuality ?? 'sem posição'
      tally[quality] = (tally[quality] ?? 0) + 1
    }
    return tally
  }, [measuredItems])

  // O lugar de cada sessão sai do centro dos trechos que ela mediu. O campo de via era texto
  // livre digitado em campo, então não identifica nada — vem vazio ou vem "TESTE".
  // Os trechos de cada sessão, para o rótulo de lugar. A API já resolveu a rua de cada um;
  // aqui só se decide o que dizer quando uma sessão cruza mais de uma.
  const segmentsOfSession = useMemo(() => {
    const bySession = {}
    for (const segment of measuredItems) {
      bySession[segment.sessionId] = [...(bySession[segment.sessionId] ?? []), segment]
    }
    return bySession
  }, [measuredItems])

  const placeOf = (sessionId) => placeOfSession(segmentsOfSession[sessionId])

  // Os dias em que se saiu a campo. Uma volta inteira é de um dia só, então o dia é o recorte
  // natural de "o que foi gravado nessa saída".
  const days = useMemo(() => {
    const tally = new Map()
    for (const session of page?.items ?? []) {
      const key = dayKey(session.startedAt)
      if (!key) continue
      const seen = tally.get(key)
      tally.set(key, { key, label: dayLabel(session.startedAt), count: (seen?.count ?? 0) + 1 })
    }
    return [...tally.values()].sort((a, b) => b.key.localeCompare(a.key))
  }, [page])

  const visibleSessions = useMemo(() => {
    const all = page?.items ?? []
    return filterDay ? all.filter(session => dayKey(session.startedAt) === filterDay) : all
  }, [page, filterDay])

  const weekly = useMemo(
    () => bucketByWeek((page?.items ?? []).map(session => session.startedAt), 10),
    [page])

  const overdue = counts[3]
  const highest = tallest[0]?.measurementExtent95P95M ?? 0
  const measuredTotal = counts[1] + counts[2] + counts[3]

  const kpis = [
    { icon: Route, label: 'Sessões capturadas', value: page?.total ?? 0 },
    { icon: Ruler, label: 'Trechos medidos', value: measured?.total ?? 0 },
    { icon: TriangleAlert, label: 'Acima de 30 cm', value: overdue },
    { icon: Camera, label: 'Maior altura p90', value: highest * 100, decimals: 0, suffix: ' cm' },
  ]

  return (
    <PageShell currentPage="sessions">
      <div style={s.header}>
        <div style={s.greeting}>Olá, {user?.name?.split(' ')[0] || 'operador'} 👋</div>
        <div style={s.subtitle}>Visão geral das capturas — o que foi gravado e o que já foi medido.</div>
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

      <div style={s.chartsGrid}>
        <Card delay={0.15}>
          <div style={s.cardTitle}>Capturas por semana</div>
          <div style={s.cardHint}>Sessões gravadas em campo, últimas 10 semanas</div>
          <WeeklyBarChart data={weekly} label="sessões" />
        </Card>
        <Card delay={0.2}>
          <div style={s.cardTitle}>Trechos por nível</div>
          <div style={s.cardHint}>Classificação da altura medida em cada trecho</div>
          <LevelDonut counts={counts} total={measuredTotal} />
        </Card>
      </div>

      <Card delay={0.25} style={{ marginBottom: 12 }}>
        <div style={s.cardTitle}>Trechos mais altos</div>
        <div style={s.cardHint}>Maior altura p90 medida — prioridade máxima</div>
        {tallest.map(segment => (
          <div key={`${segment.sessionId}:${segment.segmentIndex}`} style={s.criticalRow}>
            <span style={s.criticalDot(LEVELS[vegetationLevel(segment.measurementLevel)].color)} />
            <span style={s.criticalName}>
              {placeOfSegment(segment)?.label ?? `Trecho ${segment.segmentIndex}`}
            </span>
            <span style={s.criticalValue}>
              {(segment.measurementExtent95P95M * 100).toFixed(0)} cm
            </span>
          </div>
        ))}
        {tallest.length === 0 && <div style={s.empty}>Nenhum trecho medido ainda.</div>}
        <button style={s.linkBtn} onClick={() => navigate('/trechos')}>
          Ver todos os trechos <ArrowUpRight size={13} />
        </button>
      </Card>

      <Card delay={0.35} style={{ padding: '18px 18px 4px' }}>
        <div style={s.cardTitle}>Sessões de captura</div>
        <div style={s.cardHint}>Clique numa linha para ver a trilha e os quadros daquela sessão.</div>

        <div style={s.toolbar}>
          <button style={s.toggle(measuredOnly)} onClick={() => setMeasuredOnly(v => !v)}>
            Somente com medição
          </button>
          {days.length > 1 && (
            <select style={s.daySelect} value={filterDay}
              onChange={event => setFilterDay(event.target.value)}>
              <option value="">Todos os dias</option>
              {days.map(day => (
                <option key={day.key} value={day.key}>{day.label} ({day.count})</option>
              ))}
            </select>
          )}
        </div>

        {loading && <div style={s.empty}>Carregando…</div>}
        {error && <div style={{ ...s.empty, color: LEVELS[3].color }}>{error.message}</div>}

        {page && !loading && (
          <table style={s.table}>
            <thead>
              <tr>
                <th style={s.th}>Início</th>
                <th style={s.th}>Onde</th>
                <th style={s.th}>Dispositivo</th>
                <th style={s.th}>Trechos</th>
                <th style={s.th}>Medidos</th>
              </tr>
            </thead>
            <tbody>
              {visibleSessions.map(session => (
                <tr key={session.sessionId} style={s.row}
                  onClick={() => navigate(`/sessoes/${session.sessionId}`)}>
                  <td style={s.td}>{new Date(session.startedAt).toLocaleString('pt-BR')}</td>
                  <td style={s.td}>{(() => {
                    const place = placeOf(session.sessionId)
                    if (!place) {
                      return <span style={{ color: 'var(--text-muted)' }}>sem posição</span>
                    }
                    return (
                      <>
                        {place.label}
                        {place.detail && (
                          <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{place.detail}</div>
                        )}
                      </>
                    )
                  })()}</td>
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
              {visibleSessions.length === 0 && (
                <tr><td style={{ ...s.td, ...s.empty }} colSpan={5}>
                  Nenhuma sessão {measuredOnly ? 'com medição ' : ''}encontrada
                  {filterDay ? ` em ${days.find(d => d.key === filterDay)?.label ?? ''}` : ''}.
                </td></tr>
              )}
            </tbody>
          </table>
        )}
      </Card>
    </PageShell>
  )
}
