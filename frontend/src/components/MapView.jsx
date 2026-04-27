import { useEffect } from 'react'
import { MapContainer, TileLayer, GeoJSON, useMap } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'
import { getPolygonStyle, EQUIPMENT_TYPES, formatArea } from '../utils/classification'

function FitBounds({ geojson }) {
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

    map.fitBounds([[minLat, minLng], [maxLat, maxLng]], { padding: [40, 40] })
  }, [geojson, map])

  return null
}

export default function MapView({ geojson, activeLayers, onPolygonClick, onCreateOrder }) {
  const center = [-23.518, -46.779]

  useEffect(() => {
    window.__createOrder__ = () => {}
    return () => delete window.__createOrder__
  }, [])

  return (
    <MapContainer
      center={center}
      zoom={13}
      style={{ height: '100%', width: '100%' }}
      zoomControl={true}
    >
      {/* 🌞 Mapa claro */}
      <TileLayer
        attribution='&copy; OSM &copy; CARTO'
        url="https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png"
      />

      {/* 🌑 Overlay escuro com opacity 0.5 */}
      <TileLayer
        url="https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png"
        opacity={0.1}
      />

      {geojson && activeLayers.polygons && (
        <>
          <FitBounds geojson={geojson} />

          <GeoJSON
            data={geojson}
            style={getPolygonStyle}
            onEachFeature={(feature, layer) => {
              const eq =
                EQUIPMENT_TYPES[feature.properties.name] || {
                  short: feature.properties.name,
                }

              const area = feature.properties.area_m2

              layer.on({
                click: () => {
                  window.__createOrder__ = () => onCreateOrder(feature, null)
                  onPolygonClick(feature)
                },
                mouseover: (e) =>
                  e.target.setStyle({ fillOpacity: 0.55, weight: 3 }),
                mouseout: (e) =>
                  e.target.setStyle({ fillOpacity: 0.30, weight: 2 }),
              })

              layer.bindPopup(
                `
                <div style="font-family: 'DM Sans', sans-serif; min-width: 220px;">
                  <div style="font-weight: 600; font-size: 13px; margin-bottom: 4px;">
                    ${eq.short}
                  </div>
                  <div style="font-size: 12px; color: #6b6b80; margin-bottom: 10px;">
                    Área: ${formatArea(area)}
                  </div>
                  <button onclick="window.__createOrder__()" style="
                    width:100%; padding:8px 12px; background:#5e22f3; color:white;
                    border:none; border-radius:6px; font-size:12px; font-weight:600;
                    cursor:pointer; font-family:inherit;
                  ">
                    Abrir Ordem de Serviço
                  </button>
                </div>
              `,
                { closeButton: false }
              )
            }}
          />
        </>
      )}
    </MapContainer>
  )
}