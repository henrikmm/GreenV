import { useEffect, useMemo, useRef } from 'react'
import { MapContainer, TileLayer, GeoJSON, useMap } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'
import { LEVELS, vegetationLevel } from '@greenv/web-core'

/**
 * Todas as sessões no mesmo mapa.
 *
 * O mapa da demonstração parte de um corredor conhecido, o Rodoanel, e desenha os polígonos do
 * KMZ sobre ele. Aqui não há corredor: cada sessão está onde foi capturada, e o enquadramento
 * tem de ser calculado a partir do que existe. Duas sessões a treze quilômetros da SP-021
 * aparecem no lugar onde realmente foram gravadas, que é a resposta honesta.
 */
export default function SessionsMap({ tracks = [], selectedId, onSelect, onOpen }) {
  const drawn = useMemo(() => tracks.filter(entry => entry.track?.features?.length), [tracks])

  const styleFor = (sessionId) => (feature) => {
    const level = LEVELS[vegetationLevel(feature.properties?.level)] ?? LEVELS[0]
    const band = feature.properties?.kind === 'band'
    const dimmed = selectedId != null && selectedId !== sessionId
    return {
      color: level.color,
      weight: band ? 1 : selectedId === sessionId ? 6 : 4,
      opacity: dimmed ? 0.25 : band ? 0.6 : 0.95,
      fillColor: level.color,
      fillOpacity: band ? (dimmed ? 0.06 : 0.18) : 0,
    }
  }

  if (drawn.length === 0) {
    return (
      <div style={{
        flex: 1, display: 'grid', placeItems: 'center',
        color: 'var(--text-muted)', fontSize: 14, background: 'var(--bg-secondary)',
      }}>
        Nenhuma sessão tem trilha desenhável ainda.
      </div>
    )
  }

  return (
    <MapContainer center={[-23.55, -46.7]} zoom={12} style={{ flex: 1, height: '100%' }} scrollWheelZoom>
      <TileLayer
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        attribution="&copy; OpenStreetMap"
      />
      <FitToTracks tracks={drawn} selectedId={selectedId} />
      {drawn.map(({ session, track }) => (
        <GeoJSON
          key={`${session.sessionId}:${selectedId === session.sessionId}`}
          data={track}
          style={styleFor(session.sessionId)}
          onEachFeature={(feature, layer) => {
            layer.on('click', () => onSelect?.(session.sessionId))
            layer.on('dblclick', () => onOpen?.(session.sessionId))
            layer.bindTooltip(tooltipOf(session, feature), { sticky: true })
          }}
        />
      ))}
    </MapContainer>
  )
}

function tooltipOf(session, feature) {
  const when = new Date(session.startedAt).toLocaleString('pt-BR')
  const height = feature.properties?.extent95P95M
  const road = session.rodovia ?? 'via não identificada'
  return `<strong>${road}</strong><br/>${when}<br/>trecho ${feature.properties?.segmentIndex ?? '—'}`
    + (height != null ? `<br/>${(height * 100).toFixed(0)} cm` : '')
}

/**
 * O enquadramento segue a seleção, e só ela.
 *
 * Sem a chave de comparação o mapa voltaria ao enquadramento geral a cada nova renderização e
 * desfaria o zoom que a pessoa acabou de dar.
 */
function FitToTracks({ tracks, selectedId }) {
  const map = useMap()
  const fitted = useRef(null)

  useEffect(() => {
    const wanted = selectedId
      ? tracks.filter(entry => entry.session.sessionId === selectedId)
      : tracks
    const points = []
    for (const entry of wanted) {
      for (const feature of entry.track.features ?? []) {
        const coordinates = feature.geometry?.coordinates ?? []
        const flat = feature.geometry?.type === 'Polygon' ? coordinates.flat() : coordinates
        for (const [lon, lat] of flat) points.push([lat, lon])
      }
    }
    if (points.length === 0) return
    const key = `${selectedId ?? 'all'}:${points.length}`
    if (fitted.current === key) return
    fitted.current = key
    map.fitBounds(points, { padding: [40, 40], maxZoom: 17 })
  }, [tracks, selectedId, map])

  return null
}
