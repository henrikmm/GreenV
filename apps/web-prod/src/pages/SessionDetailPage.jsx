import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { LEVELS, vegetationLevel } from '@greenv/web-core'
import { sessions } from '../api/greenv'
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
    fontSize: 12, color: 'var(--text-muted)', cursor: 'pointer', background: 'none',
    border: 'none', padding: 0, fontFamily: 'inherit', marginBottom: 6,
  },
  title: { fontSize: 22, fontWeight: 700, color: 'var(--text-primary)' },
  subtitle: { fontSize: 13, color: 'var(--text-secondary)', marginTop: 2, marginBottom: 20 },
  card: {
    background: 'white', borderRadius: 'var(--radius-md)', border: '1px solid var(--border)',
    boxShadow: 'var(--shadow-card)', overflow: 'hidden',
  },
  cardPad: { padding: 18 },
  cardTitle: { fontSize: 13, fontWeight: 700, marginBottom: 12, color: 'var(--text-primary)' },
  grid: { display: 'grid', gridTemplateColumns: 'minmax(0, 2fr) minmax(280px, 1fr)', gap: 16, marginTop: 16, alignItems: 'start' },
  table: { width: '100%', borderCollapse: 'separate', borderSpacing: 0 },
  th: {
    padding: '10px 14px', textAlign: 'left', fontSize: 10, fontWeight: 700,
    color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em',
    background: 'var(--bg-secondary)', borderBottom: '1px solid var(--border)',
  },
  td: { padding: '12px 14px', fontSize: 13, borderBottom: '1px solid var(--border)' },
  pill: (level) => ({
    display: 'inline-flex', padding: '3px 10px', borderRadius: 20, fontSize: 11, fontWeight: 600,
    color: LEVELS[level].color, background: LEVELS[level].bg,
  }),
  hint: { fontSize: 12.5, color: 'var(--text-muted)', lineHeight: 1.6 },
  frameMeta: { fontSize: 11.5, color: 'var(--text-muted)', lineHeight: 1.7, marginTop: 8 },
}

export default function SessionDetailPage() {
  const { sessionId } = useParams()
  const navigate = useNavigate()
  const [state, setState] = useState({ loading: true })
  const [selected, setSelected] = useState(null)

  useEffect(() => {
    let live = true
    setState({ loading: true })
    Promise.all([sessions.get(sessionId), sessions.segments(sessionId), sessions.track(sessionId)])
      .then(async ([session, segments, track]) => {
        const lists = await Promise.all(
          segments.map(segment =>
            sessions.frames(sessionId, segment.segmentIndex)
              .then(list => list.map(frame => ({ ...frame, segmentIndex: segment.segmentIndex })))
              // Um trecho sem manifesto responde 409, o que é uma resposta e não uma falha.
              .catch(() => [])),
        )
        if (live) setState({ loading: false, session, segments, track, frames: lists.flat() })
      })
      .catch(error => { if (live) setState({ loading: false, error }) })
    return () => { live = false }
  }, [sessionId])

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

  const { session, segments, track, frames } = state
  const road = session.rodovia
    ? `${session.rodovia}${session.sentido ? ' · SENTIDO ' + session.sentido.toUpperCase() : ''}`
    : 'VIA NÃO IDENTIFICADA'

  return (
    <PageShell currentPage="sessions" roadTag={road}>
      <button style={s.back} onClick={() => navigate('/sessoes')}>← Sessões</button>
      <div style={s.title}>
        {session.rodovia || 'Via não identificada'}
        {session.sentido ? ` · sentido ${session.sentido}` : ''}
      </div>
      <div style={s.subtitle}>
        {new Date(session.startedAt).toLocaleString('pt-BR')} · {session.segmentCount} trechos,
        {' '}{session.measuredSegmentCount} medidos · {frames.length} quadros publicados
      </div>

      <div style={s.card}>
        <SessionMap track={track} frames={frames} onFrameClick={setSelected} height={460} />
      </div>

      <div style={s.grid}>
        <div style={s.card}>
          <div style={{ ...s.cardPad, paddingBottom: 0 }}>
            <div style={s.cardTitle}>Trechos</div>
          </div>
          <table style={s.table}>
            <thead>
              <tr>
                <th style={s.th}>#</th><th style={s.th}>Nível</th><th style={s.th}>Altura p95</th>
                <th style={s.th}>Células</th><th style={s.th}>GPS</th>
              </tr>
            </thead>
            <tbody>
              {segments.map(segment => {
                const level = vegetationLevel(segment.measurementLevel)
                return (
                  <tr key={segment.segmentIndex}>
                    <td style={s.td}>{segment.segmentIndex}</td>
                    <td style={s.td}><span style={s.pill(level)}>{LEVELS[level].label}</span></td>
                    <td style={s.td}>
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
        </div>

        <div style={{ ...s.card, ...s.cardPad }}>
          <div style={s.cardTitle}>Quadro</div>
          {selected ? (
            <div>
              <img src={selected.imageUrl} alt={selected.fileName}
                style={{ width: '100%', borderRadius: 'var(--radius-sm)', display: 'block' }} />
              <div style={s.frameMeta}>
                <strong style={{ color: 'var(--text-primary)' }}>{selected.fileName}</strong><br />
                trecho {selected.segmentIndex}<br />
                {selected.capturedAtUtc && new Date(selected.capturedAtUtc).toLocaleString('pt-BR')}<br />
                precisão {selected.horizontalAccuracyMeters?.toFixed(1) ?? '?'} m · {selected.locationQuality}
              </div>
            </div>
          ) : (
            <div style={s.hint}>
              Clique num ponto do mapa para ver a foto tirada ali.
            </div>
          )}
        </div>
      </div>
    </PageShell>
  )
}
