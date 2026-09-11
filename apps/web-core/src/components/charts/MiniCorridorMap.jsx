import { useEffect, useMemo, useRef } from 'react'
import { MapContainer, TileLayer, GeoJSON } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'
import FitBounds from '../FitBounds'
import { LEVELS } from '../../utils/classification'

/**
 * O corredor inteiro em miniatura, colorido por nível.
 *
 * `levelOf` decide o nível de cada trecho e vem de fora, porque quem responde isso muda: a demo
 * simula um histórico a partir de um hash, e a versão real lê a medição do trecho. Sem a função,
 * tudo fica em "não avaliado" em vez de cair no nível mais baixo — uma altura que ninguém mediu
 * não é uma altura baixa.
 */
export default function MiniCorridorMap({ geojson, levelOf, height = 220 }) {
  const geoJsonRef = useRef(null)

  const styleFn = useMemo(() => (feature) => {
    const lvl = LEVELS[levelOf?.(feature) ?? 0] ?? LEVELS[0]
    return { color: lvl.color, weight: 3, opacity: 0.95, fillColor: lvl.color, fillOpacity: 0.55 }
  }, [levelOf])

  useEffect(() => {
    geoJsonRef.current?.setStyle(styleFn)
  }, [styleFn])

  if (!geojson) {
    return (
      <div className="skeleton" style={{ height, borderRadius: 'var(--radius-md)' }} />
    )
  }

  return (
    <MapContainer
      center={[-23.518, -46.779]} zoom={11} style={{ height, width: '100%', borderRadius: 'var(--radius-md)' }}
      zoomControl={false} scrollWheelZoom={false} dragging={true} attributionControl={false}
      className="map-mono"
    >
      <TileLayer url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png" />
      <FitBounds geojson={geojson} padding={[16, 16]} />
      <GeoJSON key="mini-corridor" data={geojson} style={styleFn} ref={geoJsonRef} />
    </MapContainer>
  )
}
