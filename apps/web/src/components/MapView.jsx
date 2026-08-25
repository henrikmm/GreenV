import { useEffect, useMemo } from 'react'
import { MapContainer, TileLayer, GeoJSON, CircleMarker, Tooltip, useMap } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'
import { getPolygonStyle, EQUIPMENT_TYPES, LEVELS, formatArea } from '../utils/classification'

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

export default function MapView({ geojson, marcoKm, activeLayers, filterLevel, onPolygonClick, onCreateOrder }) {
  const center = [-23.518, -46.779]

  useEffect(() => {
    window.__createOrder__ = () => {}
    return () => delete window.__createOrder__
  }, [])

  // Filter polygons by vegetation level
  const filteredGeojson = useMemo(() => {
    if (!geojson) return null
    if (filterLevel === null) return geojson
    return {
      ...geojson,
      features: geojson.features.filter(f => f.properties.vegetation_level === filterLevel)
    }
  }, [geojson, filterLevel])

  return (
    <MapContainer center={center} zoom={13} style={{ height: '100%', width: '100%' }} zoomControl={true}>
      {/* Mapa claro com leve overlay */}
      <TileLayer
        attribution='&copy; OSM &copy; CARTO'
        url="https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png"
      />
      <TileLayer
        url="https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png"
        opacity={0.1}
      />

      {/* Polígonos de vegetação — key forces re-render on filter change */}
      {filteredGeojson && activeLayers.polygons && (
        <>
          <FitBounds geojson={geojson} />
          <GeoJSON
            key={`polygons-${filterLevel}`}
            data={filteredGeojson}
            style={getPolygonStyle}
            onEachFeature={(feature, layer) => {
              const eq = EQUIPMENT_TYPES[feature.properties.name] || { short: feature.properties.name }
              const area = feature.properties.area_m2
              const level = feature.properties.vegetation_level || 1
              const lvl = LEVELS[level]

              layer.on({
                click: () => {
                  window.__createOrder__ = () => onCreateOrder(feature)
                  onPolygonClick(feature)
                },
                mouseover: (e) => e.target.setStyle({ fillOpacity: 0.55, weight: 3 }),
                mouseout: (e) => e.target.setStyle({ fillOpacity: 0.30, weight: 2 }),
              })

              layer.bindPopup(`
                <div style="font-family:'DM Sans',sans-serif; min-width:220px;">
                  <div style="display:flex; align-items:center; gap:6px; margin-bottom:8px;">
                    <span style="width:8px;height:8px;border-radius:50%;background:${lvl.color};"></span>
                    <span style="font-weight:600;font-size:13px;">${lvl.label}</span>
                    <span style="font-size:11px;color:#6b6b80;">— ${lvl.desc}</span>
                  </div>
                  <div style="font-size:12px;color:#6b6b80;margin-bottom:3px;">
                    Equipamento: <strong style="color:#1a1a2e;">${eq.short}</strong>
                  </div>
                  <div style="font-size:12px;color:#6b6b80;margin-bottom:10px;">
                    Área: <strong style="color:#1a1a2e;">${formatArea(area)}</strong>
                  </div>
                  <button onclick="window.__createOrder__()" style="
                    width:100%;padding:8px 12px;background:#5e22f3;color:white;
                    border:none;border-radius:6px;font-size:12px;font-weight:600;
                    cursor:pointer;font-family:inherit;
                  ">
                    Abrir Ordem de Serviço
                  </button>
                </div>
              `, { closeButton: false })
            }}
          />
        </>
      )}

      {/* Marcos quilométricos */}
      {marcoKm && activeLayers.marcoKm && marcoKm.features.map(f => {
        const [lng, lat] = f.geometry.coordinates
        const km = f.properties.km
        return (
          <CircleMarker
            key={`km-${km}`}
            center={[lat, lng]}
            radius={6}
            pathOptions={{
              color: '#5e22f3',
              fillColor: 'white',
              fillOpacity: 1,
              weight: 2.5,
            }}
          >
            <Tooltip
              direction="right"
              offset={[10, 0]}
              permanent={false}
              className="km-tooltip"
            >
              <span style={{ fontFamily: "'DM Sans',sans-serif", fontWeight: 600, fontSize: 12 }}>
                KM {km}
              </span>
            </Tooltip>
          </CircleMarker>
        )
      })}
    </MapContainer>
  )
}
