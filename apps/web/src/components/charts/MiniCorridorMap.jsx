import { useEffect, useMemo, useRef } from 'react'
import { MapContainer, TileLayer, GeoJSON } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'
import FitBounds from '../FitBounds'
import { LEVELS } from '../../utils/classification'
import { estimateHistoricalLevel } from '../../utils/vegetationHistory'

export default function MiniCorridorMap({ geojson, weeksAgo, height = 220 }) {
  const geoJsonRef = useRef(null)

  const styleFn = useMemo(() => (feature) => {
    const lvl = LEVELS[estimateHistoricalLevel(feature, weeksAgo)]
    return { color: lvl.color, weight: 3, opacity: 0.95, fillColor: lvl.color, fillOpacity: 0.55 }
  }, [weeksAgo])

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
