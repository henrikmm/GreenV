import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Sun, Satellite } from 'lucide-react'
import { vegetationLevel, Legend } from '@greenv/web-core'
import { sessions as sessionsApi, measurements } from '../api/greenv'
import { placeOfSegment, placeOfSession } from '../api/place'
import PageShell from '../components/PageShell'
import SessionsMap from '../components/SessionsMap'
import SessionsSidebar from '../components/SessionsSidebar'

/**
 * Onde tudo foi capturado, de uma vez só.
 *
 * A demonstração abre num corredor fixo porque os 642 polígonos do KMZ são sempre o mesmo trecho
 * de Rodoanel. Aqui a lista manda no mapa: cada sessão traz a própria geometria e o mapa se
 * ajusta ao que existe. O arranjo é o mesmo — barra lateral, mapa, alternador de base e legenda.
 */
const basemapToggleStyle = {
  position: 'absolute', top: 16, right: 16, zIndex: 800,
  display: 'flex', background: 'white', border: '1px solid var(--border)',
  borderRadius: 'var(--radius-sm)', boxShadow: 'var(--shadow-card-lg)', overflow: 'hidden',
}

const basemapBtn = (active) => ({
  display: 'flex', alignItems: 'center', gap: 6, padding: '8px 12px', border: 'none',
  background: active ? 'var(--motiva)' : 'white', color: active ? 'white' : 'var(--text-secondary)',
  fontSize: 12, fontWeight: 600, cursor: 'pointer', fontFamily: 'inherit',
})

export default function MapPage() {
  const navigate = useNavigate()
  const [state, setState] = useState({ loading: true, tracks: [] })
  const [selectedId, setSelectedId] = useState(null)
  const [layers, setLayers] = useState({ track: true, band: true })
  const [filterLevel, setFilterLevel] = useState(null)
  const [satellite, setSatellite] = useState(false)

  useEffect(() => {
    let live = true
    // As medições vêm junto só pelo centro de cada trecho. É a mesma fonte que a visão geral e
    // a tela da sessão usam para dizer onde a captura foi feita, e ler a mesma coisa nas três é o
    // que impede o mapa de rotular um lugar e a tabela de rotular outro, noventa metros ao lado.
    // Mil e não duzentos: uma linha passou a ser uma janela de cerca de 25 m, e um dia de captura
    // que rendia 43 leituras rende umas 320. Com duzentas, as sessões do fim da lista ficariam sem
    // nenhuma linha e sem rótulo de lugar, que é justamente o que esta tela veio buscar.
    Promise.all([sessionsApi.list({ limit: 50 }), measurements.list({ limit: 1000 })])
      .then(async ([page, measured]) => {
        const tracks = await Promise.all(page.items.map(session =>
          sessionsApi.track(session.sessionId)
            .then(track => ({ session, track }))
            // Uma sessão sem segmento medido não tem trilha, e isso é resposta, não falha.
            .catch(() => ({ session, track: null }))))
        if (live) setState({ loading: false, tracks, measured: measured.items })
      })
      .catch(error => { if (live) setState({ loading: false, tracks: [], error }) })
    return () => { live = false }
  }, [])

  const { tracks, measured, loading, error } = state

  const entries = useMemo(() => tracks.map(({ session, track }) => {
    const drawable = Boolean(track?.features?.length)
    const worst = Math.max(0, ...(track?.features ?? [])
      .map(feature => vegetationLevel(feature.properties?.level)))
    // Os trechos medidos desta sessão, cada um com o lugar que a API resolveu. Um trecho é uma
    // janela de cerca de 25 m do segmento enviado, então uma sessão tem muitos — a barra lateral
    // mostra os mais altos e conta o resto. Ela mostra os dois níveis: a sessão e o que ela contém.
    const segments = (measured ?? [])
      .filter(segment => segment.sessionId === session.sessionId)
      .sort((a, b) => (b.measurementExtent95P95M ?? -1) - (a.measurementExtent95P95M ?? -1))
    return {
      session, track, drawable, worst, segments,
      place: placeOfSession(segments),
      stretches: segments.map(segment => ({
        segment, place: placeOfSegment(segment),
      })),
    }
  }), [tracks, measured])

  // Uma linha por trecho medido — uma feição por janela, como a API as manda. O polígono é a
  // mesma medição desenhada de outro jeito, e contá-lo dobraria todo número.
  const counts = useMemo(() => {
    const byLevel = { 0: 0, 1: 0, 2: 0, 3: 0 }
    for (const { track } of tracks) {
      for (const feature of track?.features ?? []) {
        if (feature.properties?.kind !== 'track') continue
        byLevel[vegetationLevel(feature.properties?.level)] += 1
      }
    }
    return byLevel
  }, [tracks])

  return (
    <PageShell currentPage="map" variant="fill">
      <SessionsSidebar
        entries={entries}
        counts={counts}
        selectedId={selectedId}
        onSelect={setSelectedId}
        onOpen={(sessionId) => navigate(`/sessoes/${sessionId}`)}
        onOpenSegment={(sessionId) => navigate(`/sessoes/${sessionId}`)}
        layers={layers}
        onToggleLayer={(layer) => setLayers(previous => ({ ...previous, [layer]: !previous[layer] }))}
        filterLevel={filterLevel}
        onFilterLevel={setFilterLevel}
        loading={loading}
        error={error}
      />

      <div style={{ flex: 1, position: 'relative', display: 'flex', minWidth: 0 }}>
        <SessionsMap
          tracks={tracks}
          selectedId={selectedId}
          onSelect={setSelectedId}
          onOpen={(sessionId) => navigate(`/sessoes/${sessionId}`)}
          layers={layers}
          filterLevel={filterLevel}
          satellite={satellite}
        />

        <div style={basemapToggleStyle}>
          <button style={basemapBtn(!satellite)} onClick={() => setSatellite(false)}>
            <Sun size={13} /> Claro
          </button>
          <button style={basemapBtn(satellite)} onClick={() => setSatellite(true)}>
            <Satellite size={13} /> Satélite
          </button>
        </div>

        {entries.some(entry => entry.drawable) && <Legend />}
      </div>
    </PageShell>
  )
}
