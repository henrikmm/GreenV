import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { LEVELS, vegetationLevel, Legend } from '@greenv/web-core'
import { sessions as sessionsApi } from '../api/greenv'
import PageShell from '../components/PageShell'
import SessionsMap from '../components/SessionsMap'

/**
 * Onde tudo foi capturado, de uma vez só.
 *
 * A demonstração abre num corredor fixo porque os 642 polígonos do KMZ são sempre o mesmo trecho
 * de Rodoanel. Aqui a lista manda no mapa: cada sessão traz a própria geometria e o mapa se
 * ajusta ao que existe.
 */
const s = {
  panel: {
    width: 300, flexShrink: 0, background: 'white', borderRight: '1px solid var(--border)',
    display: 'flex', flexDirection: 'column', overflow: 'hidden',
  },
  panelHead: { padding: '16px 18px 12px', borderBottom: '1px solid var(--border)' },
  panelTitle: { fontSize: 13, fontWeight: 700, color: 'var(--text-primary)' },
  panelHint: { fontSize: 11.5, color: 'var(--text-muted)', marginTop: 4, lineHeight: 1.5 },
  list: { flex: 1, overflowY: 'auto', padding: 10 },
  card: (active) => ({
    padding: '12px 14px', borderRadius: 'var(--radius-sm)', marginBottom: 8, cursor: 'pointer',
    border: `1px solid ${active ? 'var(--motiva)' : 'var(--border)'}`,
    background: active ? 'var(--motiva-subtle)' : 'white',
  }),
  cardRoad: { fontSize: 13, fontWeight: 600, color: 'var(--text-primary)' },
  cardWhen: { fontSize: 11, color: 'var(--text-muted)', marginTop: 2 },
  cardRow: { display: 'flex', alignItems: 'center', gap: 8, marginTop: 8 },
  pill: (level) => ({
    display: 'inline-flex', padding: '2px 9px', borderRadius: 20, fontSize: 10.5, fontWeight: 600,
    color: LEVELS[level].color, background: LEVELS[level].bg,
  }),
  open: {
    marginLeft: 'auto', fontSize: 11, fontWeight: 600, color: 'var(--motiva)',
    background: 'none', border: 'none', cursor: 'pointer', fontFamily: 'inherit', padding: 0,
  },
  stage: { flex: 1, position: 'relative', display: 'flex', minWidth: 0 },
  state: { padding: 18, fontSize: 12.5, color: 'var(--text-muted)', lineHeight: 1.6 },
}

export default function MapPage() {
  const navigate = useNavigate()
  const [state, setState] = useState({ loading: true, tracks: [] })
  const [selectedId, setSelectedId] = useState(null)

  useEffect(() => {
    let live = true
    sessionsApi.list({ limit: 50 })
      .then(async (page) => {
        const tracks = await Promise.all(page.items.map(session =>
          sessionsApi.track(session.sessionId)
            .then(track => ({ session, track }))
            // Uma sessão sem segmento medido não tem trilha, e isso é resposta, não falha.
            .catch(() => ({ session, track: null }))))
        if (live) setState({ loading: false, tracks })
      })
      .catch(error => { if (live) setState({ loading: false, tracks: [], error }) })
    return () => { live = false }
  }, [])

  const { tracks, loading, error } = state

  return (
    <PageShell currentPage="map" variant="fill">
      <div style={s.panel}>
        <div style={s.panelHead}>
          <div style={s.panelTitle}>Sessões no mapa</div>
          <div style={s.panelHint}>
            Clique numa sessão para enquadrá-la. Clique de novo em “abrir” para ver os quadros.
          </div>
        </div>
        <div style={s.list}>
          {loading && <div style={s.state}>Carregando sessões…</div>}
          {error && <div style={{ ...s.state, color: LEVELS[3].color }}>{error.message}</div>}
          {!loading && tracks.length === 0 && <div style={s.state}>Nenhuma sessão capturada ainda.</div>}

          {tracks.map(({ session, track }) => {
            const drawable = Boolean(track?.features?.length)
            const worst = Math.max(0, ...(track?.features ?? [])
              .map(feature => vegetationLevel(feature.properties?.level)))
            return (
              <div key={session.sessionId}
                style={s.card(selectedId === session.sessionId)}
                onClick={() => setSelectedId(drawable ? session.sessionId : null)}>
                <div style={s.cardRoad}>{session.rodovia ?? 'Via não identificada'}</div>
                <div style={s.cardWhen}>{new Date(session.startedAt).toLocaleString('pt-BR')}</div>
                <div style={s.cardRow}>
                  <span style={s.pill(worst)}>{LEVELS[worst].label}</span>
                  <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                    {session.measuredSegmentCount} de {session.segmentCount}
                  </span>
                  <button style={s.open}
                    onClick={(event) => {
                      event.stopPropagation()
                      navigate(`/sessoes/${session.sessionId}`)
                    }}>
                    abrir →
                  </button>
                </div>
                {!drawable && (
                  <div style={{ ...s.cardWhen, marginTop: 6 }}>sem trilha desenhável</div>
                )}
              </div>
            )
          })}
        </div>
      </div>

      <div style={s.stage}>
        <SessionsMap
          tracks={tracks}
          selectedId={selectedId}
          onSelect={setSelectedId}
          onOpen={(sessionId) => navigate(`/sessoes/${sessionId}`)}
        />
        {tracks.some(entry => entry.track?.features?.length) && <Legend />}
      </div>
    </PageShell>
  )
}
