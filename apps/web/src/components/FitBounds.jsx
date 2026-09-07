import { useEffect } from 'react'
import { useMap } from 'react-leaflet'

// Enquadra o mapa nos limites do geojson — usado pelo mapa principal e pelo mini-mapa do dashboard.
export default function FitBounds({ geojson, padding = [40, 40] }) {
  const map = useMap()
  useEffect(() => {
    if (!geojson || !geojson.features.length) return
    let minLat = 90, maxLat = -90, minLng = 180, maxLng = -180
    geojson.features.forEach(f => {
      f.geometry.coordinates[0].forEach(([lng, lat]) => {
        if (lat < minLat) minLat = lat
        if (lat > maxLat) maxLat = lat
        if (lng < minLng) minLng = lng
        if (lng > maxLng) maxLng = lng
      })
    })
    map.fitBounds([[minLat, minLng], [maxLat, maxLng]], { padding })
  }, [geojson, map, padding])
  return null
}
