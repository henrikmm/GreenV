import { useEffect, useState } from 'react'
import { LEVELS, vegetationLevel } from '@greenv/web-core'
import { sessions } from '../api/greenv'
import SessionMap from './SessionMap'

/**
 * Um trecho aberto dentro da lista: só a trilha dele, e as fotos dele.
 *
 * Antes o único caminho para ver um quadro era abrir a sessão inteira, o que custa a posição na
 * lista e devolve oito trechos quando a pergunta era sobre um. Aqui a trilha vem filtrada pelo
 * `segmentIndex` e os quadros vêm da rota do próprio trecho, então o que aparece é o que a
 * linha prometeu.
 *
 * A trilha da sessão é buscada uma vez e reaproveitada: abrir três trechos da mesma volta não
 * pede três vezes o mesmo GeoJSON.
 */
const s = {
  panel: { display: 'grid', gridTemplateColumns: 'minmax(0, 3fr) minmax(220px, 2fr)', gap: 14, padding: '4px 0 14px' },
  card: {
    background: 'white', border: '1px solid var(--border)', borderRadius: 'var(--radius-md)',
    overflow: 'hidden',
  },
  frameBox: { padding: 12, display: 'flex', flexDirection: 'column', gap: 10 },
  frameImage: { width: '100%', borderRadius: 'var(--radius-sm)', display: 'block', background: 'var(--bg-secondary)' },
  frameMeta: { fontSize: 11.5, color: 'var(--text-muted)', lineHeight: 1.7 },
  strip: { display: 'flex', gap: 6, overflowX: 'auto', paddingBottom: 4 },
  thumb: (active) => ({
    width: 74, height: 50, flexShrink: 0, borderRadius: 6, objectFit: 'cover', cursor: 'pointer',
    border: `2px solid ${active ? 'var(--motiva)' : 'transparent'}`, background: 'var(--bg-secondary)',
  }),
  state: { padding: 24, fontSize: 12.5, color: 'var(--text-muted)', textAlign: 'center' },
  summary: {
    display: 'flex', gap: 16, padding: '10px 12px', background: 'var(--bg-secondary)',
    borderRadius: 'var(--radius-sm)', fontSize: 11.5, color: 'var(--text-secondary)',
    marginBottom: 10, flexWrap: 'wrap',
  },
  summaryValue: { fontFamily: 'var(--font-mono)', fontWeight: 700, color: 'var(--text-primary)' },
}

const trackCache = new Map()

function loadTrack(sessionId) {
  if (!trackCache.has(sessionId)) {
    trackCache.set(sessionId, sessions.track(sessionId).catch(() => null))
  }
  return trackCache.get(sessionId)
}

export default function SegmentDetail({ segment, place }) {
  const [state, setState] = useState({ loading: true })
  const [selected, setSelected] = useState(null)

  useEffect(() => {
    let live = true
    setState({ loading: true })
    setSelected(null)
    Promise.all([
      loadTrack(segment.sessionId),
      // Um trecho sem manifesto responde 409, o que é uma resposta e não uma falha.
      sessions.frames(segment.sessionId, segment.segmentIndex).catch(() => []),
    ])
      .then(([track, frames]) => {
        if (!live) return
        const onlyThis = track
          ? { ...track, features: track.features.filter(f => f.properties?.segmentIndex === segment.segmentIndex) }
          : null
        setState({ loading: false, track: onlyThis, frames })
        setSelected(frames.find(frame => frame.latitude != null) ?? frames[0] ?? null)
      })
      .catch(error => { if (live) setState({ loading: false, error }) })
    return () => { live = false }
  }, [segment.sessionId, segment.segmentIndex])

  const { track, frames, loading, error } = state
  const level = vegetationLevel(segment.measurementLevel)

  if (loading) return <div style={s.state}>Carregando trecho…</div>
  if (error) return <div style={{ ...s.state, color: LEVELS[3].color }}>{error.message}</div>

  return (
    <div style={s.panel}>
      <div>
        <div style={s.summary}>
          <span>altura p95 <span style={{ ...s.summaryValue, color: LEVELS[level].color }}>
            {segment.measurementExtent95P95M != null
              ? `${(segment.measurementExtent95P95M * 100).toFixed(0)} cm`
              : '—'}
          </span></span>
          <span>máxima <span style={s.summaryValue}>
            {segment.measurementExtent95MaxM != null
              ? `${(segment.measurementExtent95MaxM * 100).toFixed(0)} cm`
              : '—'}
          </span></span>
          <span>cobertura <span style={s.summaryValue}>
            {segment.measurementCoverage != null
              ? `${(segment.measurementCoverage * 100).toFixed(0)}%`
              : '—'}
          </span></span>
          <span>quadros <span style={s.summaryValue}>{frames?.length ?? 0}</span></span>
        </div>
        <div style={s.card}>
          <SessionMap track={track} frames={frames} onFrameClick={setSelected} height={260} />
        </div>
      </div>

      <div style={{ ...s.card, ...s.frameBox }}>
        {selected ? (
          <>
            <img src={selected.imageUrl} alt={selected.fileName} style={s.frameImage} />
            <div style={s.frameMeta}>
              <strong style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-mono)' }}>
                {selected.fileName}
              </strong><br />
              {selected.capturedAtUtc && new Date(selected.capturedAtUtc).toLocaleString('pt-BR')}<br />
              precisão {selected.horizontalAccuracyMeters?.toFixed(1) ?? '?'} m · {selected.locationQuality ?? 'sem posição'}
            </div>
          </>
        ) : (
          <div style={s.state}>Este trecho não publicou quadros.</div>
        )}

        {frames?.length > 1 && (
          <div style={s.strip}>
            {frames.map(frame => (
              <img
                key={frame.fileName}
                src={frame.imageUrl}
                alt={frame.fileName}
                loading="lazy"
                title={frame.fileName}
                style={s.thumb(selected?.fileName === frame.fileName)}
                onClick={() => setSelected(frame)}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
