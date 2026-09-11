import { useEffect, useMemo, useRef, useState } from 'react'
import { MapContainer, TileLayer, GeoJSON, CircleMarker, Popup, useMap } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'
import { LEVELS, vegetationLevel } from '@greenv/web-core'

/**
 * O caminho de uma sessão, onde quer que ela tenha sido feita.
 *
 * Este mapa não parte de um corredor conhecido. A geometria vem da própria captura, então uma
 * caminhada num quintal e uma volta de carro na rodovia aparecem do mesmo jeito — o que importa,
 * porque as quatro medições existentes estão a treze quilômetros da SP-021.
 *
 * Duas camadas por segmento, ambas vindas da API já em GeoJSON: a linha é onde a câmera passou,
 * e o polígono é essa linha alargada nos cinco metros que a grade realmente mediu. O polígono é
 * desenhado dos dois lados porque o pacote dobra os lados e nunca registrou de qual deles a
 * célula veio; escolher um seria inventar.
 */
export default function SessionMap({ track, frames = [], onFrameClick, height = 520 }) {
  const [active, setActive] = useState(null)

  const styleFor = useMemo(() => (feature) => {
    const level = LEVELS[vegetationLevel(feature.properties?.level)] ?? LEVELS[0]
    const isBand = feature.properties?.kind === 'band'
    return {
      color: level.color,
      weight: isBand ? 1 : 4,
      opacity: isBand ? 0.6 : 0.95,
      fillColor: level.color,
      fillOpacity: isBand ? 0.18 : 0,
    }
  }, [])

  if (!track || !track.features?.length) {
    return (
      <div style={{
        height, display: 'grid', placeItems: 'center', borderRadius: 'var(--radius-md)',
        border: '1px dashed var(--border)', color: 'var(--text-muted)', fontSize: 14,
      }}>
        Esta sessão ainda não tem trilha desenhável.
      </div>
    )
  }

  return (
    <MapContainer
      center={[-23.55, -46.7]} zoom={13}
      style={{ height, width: '100%', borderRadius: 'var(--radius-md)' }}
      scrollWheelZoom
    >
      <TileLayer
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        attribution="&copy; OpenStreetMap"
      />
      <FitToTrack track={track} />
      <GeoJSON key={JSON.stringify(track).length} data={track} style={styleFor} />

      {frames.filter(frame => frame.latitude != null).map(frame => (
        <CircleMarker
          key={frame.fileName}
          center={[frame.latitude, frame.longitude]}
          radius={active === frame.fileName ? 7 : 4}
          pathOptions={{
            color: 'white', weight: 2,
            // A precisão do GPS é parte do dado, não um detalhe: um ponto de um fix de 18 m não
            // merece a mesma confiança visual que um de 5 m.
            fillColor: frame.locationQuality === 'good' ? '#5e22f3' : '#9d9db0',
            fillOpacity: 0.9,
          }}
          eventHandlers={{
            click: () => { setActive(frame.fileName); onFrameClick?.(frame) },
          }}
        >
          <Popup>
            <div style={{ fontSize: 12, lineHeight: 1.6 }}>
              <strong>{frame.fileName}</strong>
              <br />
              {frame.capturedAtUtc ? new Date(frame.capturedAtUtc).toLocaleString('pt-BR') : 'sem horário'}
              <br />
              precisão {frame.horizontalAccuracyMeters?.toFixed(1) ?? '?'} m · {frame.locationQuality}
              <br />
              <img src={frame.imageUrl} alt={frame.fileName} style={{ width: 220, marginTop: 6, borderRadius: 4 }} />
            </div>
          </Popup>
        </CircleMarker>
      ))}
    </MapContainer>
  )
}

/** Cada sessão está num lugar diferente, então o enquadramento não pode ser fixo. */
function FitToTrack({ track }) {
  const map = useMap()
  const fitted = useRef(null)

  useEffect(() => {
    const points = []
    for (const feature of track.features ?? []) {
      const coordinates = feature.geometry?.coordinates ?? []
      const flat = feature.geometry?.type === 'Polygon' ? coordinates.flat() : coordinates
      for (const [lon, lat] of flat) points.push([lat, lon])
    }
    if (points.length === 0) return
    const key = points.length + ':' + points[0].join()
    if (fitted.current === key) return
    fitted.current = key
    map.fitBounds(points, { padding: [32, 32], maxZoom: 18 })
  }, [track, map])

  return null
}
