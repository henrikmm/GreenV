import { useEffect, useMemo, useRef, useCallback } from 'react'
import { MapContainer, TileLayer, GeoJSON, CircleMarker, Marker, Polyline, Tooltip, useMap } from 'react-leaflet'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { getPolygonStyle, EQUIPMENT_TYPES, LEVELS, vegetationLevel, formatArea, getFeatureId } from '../utils/classification'
import FitBounds from './FitBounds'
import BasemapTone from './BasemapTone'

function routeIcon(n, { isStart, isEnd } = {}) {
  const cls = `route-pin-wrap${isStart ? ' is-start' : ''}${isEnd ? ' is-end' : ''}`
  return L.divIcon({
    className: '',
    html: `<div class="${cls}"><div class="route-pin"></div><div class="route-pin-label">${n}</div></div>`,
    iconSize: [30, 30],
    iconAnchor: [15, 15],
  })
}

// Desenha a polyline "se traçando" (stroke-dashoffset) em vez de aparecer pronta.
function AnimatedRoutePolyline({ positions }) {
  const layerRef = useRef(null)

  useEffect(() => {
    const layer = layerRef.current
    const path = layer?.getElement?.()
    if (!path) return
    const length = path.getTotalLength()
    path.style.transition = 'none'
    path.style.strokeDasharray = `${length}`
    path.style.strokeDashoffset = `${length}`
    path.getBoundingClientRect() // força reflow antes de animar
    path.style.transition = 'stroke-dashoffset 1.1s cubic-bezier(0.16,1,0.3,1)'
    path.style.strokeDashoffset = '0'
  }, [positions])

  return (
    <Polyline
      ref={layerRef}
      positions={positions}
      pathOptions={{ color: '#5e22f3', weight: 3.5, opacity: 0.9, className: 'route-line' }}
    />
  )
}

// Roçadeira que percorre a rota calculada em loop — puramente decorativo.
function MowerMarker({ path }) {
  const map = useMap()

  useEffect(() => {
    if (!path || path.length < 2) return undefined

    const icon = L.divIcon({
      className: '',
      html: `<div class="mower-marker">🚜</div>`,
      iconSize: [30, 30],
      iconAnchor: [15, 15],
    })
    const marker = L.marker(path[0], { icon, interactive: false, zIndexOffset: 1000 }).addTo(map)

    const segLens = []
    let total = 0
    for (let i = 0; i < path.length - 1; i++) {
      const d = Math.hypot(path[i + 1][0] - path[i][0], path[i + 1][1] - path[i][1])
      segLens.push(d)
      total += d
    }

    const DURATION_MS = 7000
    const start = performance.now()
    let raf

    const tick = (t) => {
      const target = ((t - start) % DURATION_MS) / DURATION_MS * total
      let acc = 0, idx = 0
      while (idx < segLens.length - 1 && acc + segLens[idx] < target) { acc += segLens[idx]; idx++ }
      const [lat1, lng1] = path[idx]
      const [lat2, lng2] = path[idx + 1] || path[idx]
      const p = segLens[idx] ? (target - acc) / segLens[idx] : 0
      marker.setLatLng([lat1 + (lat2 - lat1) * p, lng1 + (lng2 - lng1) * p])
      raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)

    return () => { cancelAnimationFrame(raf); map.removeLayer(marker) }
  }, [map, path])

  return null
}

export default function MapView({
  geojson, marcoKm, activeLayers, filterLevel, satellite,
  onPolygonClick, onCreateOrder, selectedFeature,
  routeMode, routeSelection = [], routeOrder = null, onToggleRouteFeature,
}) {
  const center = [-23.518, -46.779]
  const geoJsonRef = useRef(null)

  // Estado usado pelos handlers do GeoJSON, que são presos no momento do onEachFeature (o layer
  // não é recriado a cada render) — uma ref evita closures desatualizadas nos clicks/hover.
  const liveRef = useRef({})

  const filteredGeojson = useMemo(() => {
    if (!geojson) return null
    if (filterLevel === null) return geojson
    return {
      ...geojson,
      features: geojson.features.filter(f => vegetationLevel(f.properties.vegetation_level) === filterLevel),
    }
  }, [geojson, filterLevel])

  const routeIds = useMemo(() => new Set(routeSelection.map(getFeatureId)), [routeSelection])
  const selectedId = selectedFeature ? getFeatureId(selectedFeature) : null

  const styleFn = useCallback(
    (feature) => getPolygonStyle(feature, { selectedId, routeIds }),
    [selectedId, routeIds]
  )

  useEffect(() => {
    liveRef.current = { routeMode, onToggleRouteFeature, onPolygonClick, onCreateOrder, styleFn }
  })

  // Reaplica o estilo (seleção / rota) na camada existente sem remontar os 642 polígonos.
  useEffect(() => {
    geoJsonRef.current?.setStyle(styleFn)
  }, [styleFn])

  const onEachFeature = useCallback((feature, layer) => {
    const eq = EQUIPMENT_TYPES[feature.properties.name] || { short: feature.properties.name }
    const area = feature.properties.area_m2
    const level = vegetationLevel(feature.properties.vegetation_level)
    const lvl = LEVELS[level]

    layer.on({
      click: () => {
        const live = liveRef.current
        if (live.routeMode) {
          live.onToggleRouteFeature(feature)
          return
        }
        window.__createOrder__ = () => live.onCreateOrder(feature)
        live.onPolygonClick(feature)
      },
      mouseover: (e) => e.target.setStyle({ fillOpacity: 0.55, weight: 3 }),
      mouseout: (e) => e.target.setStyle(liveRef.current.styleFn(feature)),
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
        <button onclick="window.__createOrder__ && window.__createOrder__()" style="
          width:100%;padding:8px 12px;background:#5e22f3;color:white;
          border:none;border-radius:6px;font-size:12px;font-weight:600;
          cursor:pointer;font-family:inherit;
        ">
          Abrir Ordem de Serviço
        </button>
      </div>
    `, { closeButton: false })
  }, [])

  return (
    <MapContainer
      center={center} zoom={13} style={{ height: '100%', width: '100%' }} zoomControl={true}
    >
      <BasemapTone mono={!satellite} />
      {satellite ? (
        <TileLayer
          attribution="Tiles &copy; Esri — Source: Esri, Maxar, Earthstar Geographics"
          url="https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        />
      ) : (
        <TileLayer
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
          url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
      )}

      {filteredGeojson && activeLayers.polygons && (
        <>
          <FitBounds geojson={geojson} />
          <GeoJSON
            key={`polygons-${filterLevel}`}
            ref={geoJsonRef}
            data={filteredGeojson}
            style={styleFn}
            onEachFeature={onEachFeature}
          />
        </>
      )}

      {marcoKm && activeLayers.marcoKm && marcoKm.features.map(f => {
        const [lng, lat] = f.geometry.coordinates
        const km = f.properties.km
        return (
          <CircleMarker
            key={`km-${km}`}
            center={[lat, lng]}
            radius={6}
            pathOptions={{ color: '#5e22f3', fillColor: 'white', fillOpacity: 1, weight: 2.5 }}
          >
            <Tooltip direction="right" offset={[10, 0]} permanent={false} className="km-tooltip">
              <span style={{ fontFamily: "'DM Sans',sans-serif", fontWeight: 600, fontSize: 12 }}>
                KM {km}
              </span>
            </Tooltip>
          </CircleMarker>
        )
      })}

      {routeMode && routeSelection.map((f, i) => (
        <Marker
          key={getFeatureId(f)}
          position={[f.properties.centroid_lat, f.properties.centroid_lon]}
          icon={routeIcon(i + 1, { isStart: i === 0, isEnd: i === routeSelection.length - 1 && routeSelection.length > 1 })}
          interactive={false}
        />
      ))}

      {routeOrder && routeOrder.length > 1 && (
        <>
          <AnimatedRoutePolyline positions={routeOrder.map(f => [f.properties.centroid_lat, f.properties.centroid_lon])} />
          <MowerMarker path={routeOrder.map(f => [f.properties.centroid_lat, f.properties.centroid_lon])} />
        </>
      )}
    </MapContainer>
  )
}
