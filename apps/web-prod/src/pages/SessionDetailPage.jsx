import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { AnimatePresence } from 'framer-motion'
import { ArrowLeft, ClipboardList } from 'lucide-react'
import { LEVELS, vegetationLevel, Card, describePlace, centreOf } from '@greenv/web-core'
import { sessions, teams as teamsApi } from '../api/greenv'
import { usePlaceNames } from '../api/places'
import NewOrderModal from '../components/NewOrderModal'
import SessionMap from '../components/SessionMap'
import PageShell from '../components/PageShell'

/**
 * Uma sessão: por onde passou, o que mediu, e a foto de cada ponto.
 *
 * O mapa ocupa a largura toda e a lista de trechos fica ao lado do quadro selecionado, que é o
 * mesmo arranjo que a tela de mapa da demonstração usa.
 */
const s = {
  back: {
    display: 'flex', alignItems: 'center', gap: 5, fontSize: 12, color: 'var(--text-muted)',
    cursor: 'pointer', background: 'none', border: 'none', padding: 0, fontFamily: 'inherit',
    marginBottom: 8,
  },
  header: { marginBottom: 18 },
  title: { fontSize: 22, fontWeight: 700, color: 'var(--text-primary)' },
  subtitle: { fontSize: 13, color: 'var(--text-secondary)', marginTop: 3 },
  grid: {
    display: 'grid', gridTemplateColumns: 'minmax(0, 2fr) minmax(280px, 1fr)',
    gap: 12, marginTop: 12, alignItems: 'start',
  },
  cardTitle: { fontSize: 13, fontWeight: 700, marginBottom: 2 },
  cardHint: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 14 },
  table: { width: '100%', borderCollapse: 'separate', borderSpacing: 0 },
  th: {
    padding: '9px 10px', textAlign: 'left', fontSize: 10, fontWeight: 700,
    color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em',
    borderBottom: '1px solid var(--border)',
  },
  td: { padding: '12px 10px', fontSize: 12.5, borderBottom: '1px solid var(--border)' },
  mono: { fontFamily: 'var(--font-mono)' },
  pill: (level) => ({
    display: 'inline-flex', padding: '3px 10px', borderRadius: 20, fontSize: 11, fontWeight: 600,
    color: LEVELS[level].color, background: LEVELS[level].bg,
  }),
  hint: { fontSize: 12.5, color: 'var(--text-muted)', lineHeight: 1.6 },
  frameMeta: { fontSize: 11.5, color: 'var(--text-muted)', lineHeight: 1.8, marginTop: 10 },
  cardHead: { display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12 },
  orderBtn: (enabled) => ({
    display: 'flex', alignItems: 'center', gap: 6, padding: '8px 13px', fontSize: 12.5,
    fontWeight: 700, borderRadius: 'var(--radius-sm)', fontFamily: 'inherit', flexShrink: 0,
    cursor: enabled ? 'pointer' : 'not-allowed',
    border: `1.5px solid ${enabled ? 'var(--motiva)' : 'var(--border)'}`,
    background: enabled ? 'var(--motiva)' : 'white',
    color: enabled ? 'white' : 'var(--text-muted)',
  }),
}

export default function SessionDetailPage() {
  const { sessionId } = useParams()
  const navigate = useNavigate()
  const [state, setState] = useState({ loading: true })
  const [selected, setSelected] = useState(null)
  const [chosen, setChosen] = useState([])
  const [ordering, setOrdering] = useState(false)

  useEffect(() => {
    let live = true
    setState({ loading: true })
    setSelected(null)
    setChosen([])
    Promise.all([
      sessions.get(sessionId), sessions.segments(sessionId), sessions.track(sessionId),
      // Uma ordem pode ser aberta sem equipe, então a lista falhar não impede o resto.
      teamsApi.list().catch(() => []),
    ])
      .then(async ([session, segments, track, teams]) => {
        const lists = await Promise.all(
          segments.map(segment =>
            sessions.frames(sessionId, segment.segmentIndex)
              .then(list => list.map(frame => ({ ...frame, segmentIndex: segment.segmentIndex })))
              // Um trecho sem manifesto responde 409, o que é uma resposta e não uma falha.
              .catch(() => [])),
        )
        if (live) setState({ loading: false, session, segments, track, teams, frames: lists.flat() })
      })
      .catch(error => { if (live) setState({ loading: false, error }) })
    return () => { live = false }
  }, [sessionId])

  // Antes dos retornos antecipados de propósito: um hook chamado depois de um `return` roda
  // numa renderização e não na outra, e o React quebra na transição de "carregando" para pronto.
  //
  // O lugar vem do centro dos trechos medidos, não do campo de via: aquele era texto livre
  // digitado em campo e não identifica lugar nenhum. O nome da rua chega depois, do
  // OpenStreetMap; até lá a coordenada segura o lugar.
  const centre = centreOf((state.segments ?? [])
    .filter(segment => segment.measurementState != null)
    .map(segment => ({ latitude: segment.trackCenterLat, longitude: segment.trackCenterLon })))
  const streetNames = usePlaceNames(centre ? { session: centre } : {})
  const place = streetNames.session ?? describePlace(centre)

  if (state.loading) {
    return <PageShell currentPage="sessions"><div style={s.hint}>Carregando sessão…</div></PageShell>
  }
  if (state.error) {
    return (
      <PageShell currentPage="sessions">
        <div style={{ ...s.hint, color: LEVELS[3].color }}>{state.error.message}</div>
      </PageShell>
    )
  }

  const { session, segments, track, frames, teams } = state
  const measured = segments.filter(segment => segment.measurementState != null)
  const chosenSegments = measured.filter(segment => chosen.includes(segment.segmentIndex))
  const road = place ? place.label.toUpperCase() : 'SEM POSIÇÃO REGISTRADA'

  return (
    <PageShell currentPage="sessions" roadTag={road}>
      <div style={s.header}>
        <button style={s.back} onClick={() => navigate('/sessoes')}>
          <ArrowLeft size={13} /> Sessões
        </button>
        <div style={s.title}>{place?.label ?? 'Sem posição registrada'}</div>
        <div style={s.subtitle}>
          {place?.detail && <>{place.detail} · </>}
          {new Date(session.startedAt).toLocaleString('pt-BR')} · {session.segmentCount} trechos,
          {' '}{session.measuredSegmentCount} medidos · {frames.length} quadros publicados
        </div>
      </div>

      <Card style={{ padding: 12 }}>
        <SessionMap track={track} frames={frames} onFrameClick={setSelected} height={440} />
      </Card>

      <div style={s.grid}>
        <Card delay={0.05} style={{ padding: '18px 18px 4px' }}>
          <div style={s.cardHead}>
            <div>
              <div style={s.cardTitle}>Trechos</div>
              <div style={s.cardHint}>
                A altura é o percentil 95 das células medidas, a partir do solo local de cada uma.
                Marque um ou mais para abrir uma ordem.
              </div>
            </div>
            <button style={s.orderBtn(chosenSegments.length > 0)}
              disabled={chosenSegments.length === 0}
              onClick={() => setOrdering(true)}>
              <ClipboardList size={13} />
              {chosenSegments.length > 1 ? `Criar OS (${chosenSegments.length})` : 'Criar OS'}
            </button>
          </div>
          <table style={s.table}>
            <thead>
              <tr>
                <th style={s.th} /><th style={s.th}>#</th><th style={s.th}>Nível</th>
                <th style={s.th}>Altura p95</th><th style={s.th}>Células</th><th style={s.th}>GPS</th>
              </tr>
            </thead>
            <tbody>
              {segments.map(segment => {
                const level = vegetationLevel(segment.measurementLevel)
                return (
                  <tr key={segment.segmentIndex}>
                    <td style={s.td}>
                      {/* Só um trecho medido pode justificar uma ordem, e a API recusa o resto
                          com 409. Desabilitar aqui diz isso antes de alguém tentar. */}
                      <input type="checkbox" disabled={segment.measurementState == null}
                        checked={chosen.includes(segment.segmentIndex)}
                        onChange={() => setChosen(previous => previous.includes(segment.segmentIndex)
                          ? previous.filter(index => index !== segment.segmentIndex)
                          : [...previous, segment.segmentIndex])} />
                    </td>
                    <td style={{ ...s.td, ...s.mono }}>{segment.segmentIndex}</td>
                    <td style={s.td}><span style={s.pill(level)}>{LEVELS[level].label}</span></td>
                    <td style={{ ...s.td, ...s.mono }}>
                      {segment.measurementExtent95P95M != null
                        ? `${(segment.measurementExtent95P95M * 100).toFixed(0)} cm`
                        : '—'}
                    </td>
                    <td style={s.td}>
                      {segment.measurementCellsMeasured != null
                        ? `${segment.measurementCellsMeasured} medidas · ${segment.measurementCellsAbstained} sem evidência`
                        : '—'}
                    </td>
                    <td style={s.td}>{segment.trackLocationQuality ?? '—'}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </Card>

        <Card delay={0.1}>
          <div style={s.cardTitle}>Quadro</div>
          <div style={s.cardHint}>A foto tirada no ponto que você clicar no mapa.</div>
          {selected ? (
            <div>
              <img src={selected.imageUrl} alt={selected.fileName}
                style={{ width: '100%', borderRadius: 'var(--radius-sm)', display: 'block' }} />
              <div style={s.frameMeta}>
                <strong style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-mono)' }}>
                  {selected.fileName}
                </strong><br />
                trecho {selected.segmentIndex}<br />
                {selected.capturedAtUtc && new Date(selected.capturedAtUtc).toLocaleString('pt-BR')}<br />
                precisão {selected.horizontalAccuracyMeters?.toFixed(1) ?? '?'} m · {selected.locationQuality}
              </div>
            </div>
          ) : (
            <div style={s.hint}>Nenhum ponto selecionado ainda.</div>
          )}
        </Card>
      </div>

      <AnimatePresence>
        {ordering && chosenSegments.length > 0 && (
          <NewOrderModal
            segments={chosenSegments.map(segment => ({ ...segment, sessionId }))}
            teams={teams ?? []}
            onCreated={() => setChosen([])}
            onClose={() => setOrdering(false)}
          />
        )}
      </AnimatePresence>
    </PageShell>
  )
}
