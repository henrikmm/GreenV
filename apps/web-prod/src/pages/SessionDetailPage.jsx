import { useEffect, useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { LEVELS, vegetationLevel, Card, Badge } from '@greenv/web-core'
import { sessions } from '../api/greenv'
import SessionMap from '../components/SessionMap'
import NotReadyNotice from '../components/NotReadyNotice'

/**
 * Uma sessão: por onde passou, o que mediu, e as fotos de cada ponto.
 *
 * Os quadros são carregados por segmento e só dos que publicaram manifesto. Um segmento que não
 * publicou nada não é um erro a esconder: é um trecho em que o telefone não se moveu o bastante
 * para duas vistas, e o mapa fica honesto ao mostrá-lo vazio.
 */
export default function SessionDetailPage() {
  const { sessionId } = useParams()
  const [state, setState] = useState({ loading: true })
  const [selected, setSelected] = useState(null)

  useEffect(() => {
    let live = true
    setState({ loading: true })
    Promise.all([sessions.get(sessionId), sessions.segments(sessionId), sessions.track(sessionId)])
      .then(async ([session, segments, track]) => {
        const frameLists = await Promise.all(
          segments.map(segment =>
            sessions.frames(sessionId, segment.segmentIndex)
              .then(list => list.map(frame => ({ ...frame, segmentIndex: segment.segmentIndex })))
              // Um segmento sem manifesto responde 409, o que é uma resposta e não uma falha.
              .catch(() => [])),
        )
        if (live) setState({ loading: false, session, segments, track, frames: frameLists.flat() })
      })
      .catch(error => { if (live) setState({ loading: false, error }) })
    return () => { live = false }
  }, [sessionId])

  if (state.loading) return <div style={{ padding: 24 }}>Carregando sessão…</div>
  if (state.error) return <div style={{ padding: 24, color: '#dc2626' }}>{state.error.message}</div>

  const { session, segments, track, frames } = state
  const measured = segments.filter(segment => segment.measurementState === 'measured')

  return (
    <div style={{ padding: 24, display: 'grid', gap: 16 }}>
      <div>
        <Link to="/sessoes" style={{ fontSize: 13, color: 'var(--text-muted)' }}>← Sessões</Link>
        <h1 style={{ margin: '4px 0 0', fontSize: 22 }}>
          {session.rodovia || 'Via não identificada'}
          {session.sentido ? ` · sentido ${session.sentido}` : ''}
        </h1>
        <p style={{ margin: '4px 0 0', color: 'var(--text-muted)', fontSize: 13 }}>
          {new Date(session.startedAt).toLocaleString('pt-BR')} · {session.deviceId}
          {' · '}{session.segmentCount} segmentos, {session.measuredSegmentCount} medidos
        </p>
      </div>

      <NotReadyNotice />

      <Card>
        <SessionMap
          track={track}
          frames={frames}
          onFrameClick={setSelected}
          height={520}
        />
      </Card>

      <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 16, alignItems: 'start' }}>
        <Card>
          <h2 style={{ fontSize: 15, margin: '0 0 12px' }}>Segmentos</h2>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ textAlign: 'left', color: 'var(--text-muted)' }}>
                <th style={th}>#</th><th style={th}>Estado</th><th style={th}>Nível</th>
                <th style={th}>Altura p95</th><th style={th}>Células</th><th style={th}>GPS</th>
              </tr>
            </thead>
            <tbody>
              {segments.map(segment => {
                const level = LEVELS[vegetationLevel(segment.measurementLevel)]
                return (
                  <tr key={segment.segmentIndex} style={{ borderTop: '1px solid var(--border)' }}>
                    <td style={td}>{segment.segmentIndex}</td>
                    <td style={td}>{segment.measurementState ?? segment.state}</td>
                    <td style={td}>
                      <Badge color={level.color} bg={level.bg}>{level.label}</Badge>
                    </td>
                    <td style={td}>
                      {segment.measurementExtent95P95M != null
                        ? `${(segment.measurementExtent95P95M * 100).toFixed(0)} cm`
                        : '—'}
                    </td>
                    <td style={td}>
                      {segment.measurementCellsMeasured != null
                        ? `${segment.measurementCellsMeasured} medidas, ${segment.measurementCellsAbstained} sem evidência`
                        : '—'}
                    </td>
                    <td style={td}>{segment.trackLocationQuality ?? '—'}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
          {measured.length === 0 && (
            <p style={{ color: 'var(--text-muted)', fontSize: 13, marginTop: 12 }}>
              Nenhum segmento desta sessão foi medido ainda.
            </p>
          )}
        </Card>

        <Card>
          <h2 style={{ fontSize: 15, margin: '0 0 12px' }}>Quadro</h2>
          {selected ? (
            <div>
              <img src={selected.imageUrl} alt={selected.fileName}
                style={{ width: '100%', borderRadius: 'var(--radius-md)' }} />
              <p style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 8, lineHeight: 1.6 }}>
                <strong>{selected.fileName}</strong><br />
                segmento {selected.segmentIndex}<br />
                {selected.capturedAtUtc && new Date(selected.capturedAtUtc).toLocaleString('pt-BR')}<br />
                precisão {selected.horizontalAccuracyMeters?.toFixed(1) ?? '?'} m · {selected.locationQuality}
              </p>
            </div>
          ) : (
            <p style={{ color: 'var(--text-muted)', fontSize: 13 }}>
              Clique num ponto do mapa para ver a foto tirada ali. {frames.length} quadros publicados.
            </p>
          )}
        </Card>
      </div>
    </div>
  )
}

const th = { padding: '6px 8px', fontWeight: 500 }
const td = { padding: '8px' }
