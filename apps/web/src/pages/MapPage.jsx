import { useState, useMemo } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { Sun, Satellite } from 'lucide-react'
import MapView from '../components/MapView'
import Sidebar from '../components/Sidebar'
import OrderModal from '../components/OrderModal'
import Legend from '../components/Legend'
import NavBar from '../components/NavBar'
import RoutePlannerPanel from '../components/RoutePlannerPanel'
import { suggestCombinedRoutes, planRoute } from '../utils/routePlanner'
import { getFeatureId } from '../utils/classification'

const basemapToggleStyle = (offsetRight) => ({
  position: 'absolute', top: 16, right: offsetRight, zIndex: 800,
  display: 'flex', background: 'white', border: '1px solid var(--border)',
  borderRadius: 'var(--radius-sm)', boxShadow: 'var(--shadow-card-lg)', overflow: 'hidden',
})

const basemapBtn = (active) => ({
  display: 'flex', alignItems: 'center', gap: 6, padding: '8px 12px', border: 'none',
  background: active ? 'var(--motiva)' : 'white', color: active ? 'white' : 'var(--text-secondary)',
  fontSize: 12, fontWeight: 600, cursor: 'pointer', fontFamily: 'inherit',
})

export default function MapPage({ geojson, marcoKm, orders, onCreateOrder }) {
  const [selectedFeature, setSelectedFeature] = useState(null)
  const [orderModalFeatures, setOrderModalFeatures] = useState(null)
  const [activeLayers, setActiveLayers] = useState({ polygons: true, marcoKm: true })
  const [filterLevel, setFilterLevel] = useState(null)
  const [satellite, setSatellite] = useState(false)

  const [routeMode, setRouteMode] = useState(false)
  const [routeSelection, setRouteSelection] = useState([])
  const [routeResult, setRouteResult] = useState(null)

  const suggestions = useMemo(
    () => suggestCombinedRoutes(geojson, orders, marcoKm),
    [geojson, orders, marcoKm]
  )

  const toggleRouteMode = () => {
    setRouteMode(v => !v)
    setRouteSelection([])
    setRouteResult(null)
    setSelectedFeature(null)
  }

  const toggleRouteFeature = (feature) => {
    const id = getFeatureId(feature)
    setRouteSelection(prev => prev.some(f => getFeatureId(f) === id)
      ? prev.filter(f => getFeatureId(f) !== id)
      : [...prev, feature])
    setRouteResult(null)
  }

  const addSuggestionToRoute = (features) => {
    setRouteSelection(prev => {
      const ids = new Set(prev.map(getFeatureId))
      return [...prev, ...features.filter(f => !ids.has(getFeatureId(f)))]
    })
    setRouteResult(null)
  }

  const removeFromRoute = (feature) => {
    const id = getFeatureId(feature)
    setRouteSelection(prev => prev.filter(f => getFeatureId(f) !== id))
    setRouteResult(null)
  }

  const calculateRoute = () => setRouteResult(planRoute(routeSelection, marcoKm))

  const handleOrderCreated = (order) => {
    onCreateOrder(order)
    setOrderModalFeatures(null)
    setRouteSelection([])
    setRouteResult(null)
  }

  const panelOpen = routeMode
  const rightOffset = panelOpen ? 356 : 16

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', width: '100vw' }}>
      <NavBar currentPage="map" />

      <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>
        <Sidebar
          activeLayers={activeLayers}
          onToggleLayer={(layer) => setActiveLayers(prev => ({ ...prev, [layer]: !prev[layer] }))}
          filterLevel={filterLevel}
          onFilterLevel={setFilterLevel}
          selectedFeature={selectedFeature}
          onCreateOrder={() => selectedFeature && setOrderModalFeatures([selectedFeature])}
          geojson={geojson}
          routeMode={routeMode}
          onToggleRouteMode={toggleRouteMode}
          suggestionCount={suggestions.length}
        />

        <div style={{ flex: 1, position: 'relative' }}>
          {!geojson && (
            <div style={{
              position: 'absolute', inset: 0, zIndex: 500, background: 'var(--bg-secondary)',
              display: 'flex', alignItems: 'center', justifyContent: 'center', flexDirection: 'column', gap: 12,
            }}>
              <div className="skeleton" style={{ width: 240, height: 14, borderRadius: 7 }} />
              <div className="skeleton" style={{ width: 160, height: 14, borderRadius: 7 }} />
              <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 4 }}>Carregando trechos da via…</div>
            </div>
          )}

          <MapView
            geojson={geojson}
            marcoKm={marcoKm}
            activeLayers={activeLayers}
            filterLevel={filterLevel}
            satellite={satellite}
            selectedFeature={selectedFeature}
            onPolygonClick={setSelectedFeature}
            onCreateOrder={(feature) => setOrderModalFeatures([feature])}
            routeMode={routeMode}
            routeSelection={routeSelection}
            routeOrder={routeResult?.ordered}
            onToggleRouteFeature={toggleRouteFeature}
          />

          <div style={basemapToggleStyle(rightOffset)}>
            <button style={basemapBtn(!satellite)} onClick={() => setSatellite(false)}>
              <Sun size={13} /> Claro
            </button>
            <button style={basemapBtn(satellite)} onClick={() => setSatellite(true)}>
              <Satellite size={13} /> Satélite
            </button>
          </div>

          <Legend offsetRight={rightOffset} />

          <AnimatePresence>
            {routeMode && (
              <RoutePlannerPanel
                suggestions={suggestions}
                selection={routeSelection}
                routeResult={routeResult}
                onAddSuggestion={addSuggestionToRoute}
                onRemove={removeFromRoute}
                onCalculate={calculateRoute}
                onCreateCombined={(features) => setOrderModalFeatures(features)}
                onClose={toggleRouteMode}
              />
            )}
          </AnimatePresence>
        </div>
      </div>

      <AnimatePresence>
        {orderModalFeatures && (
          <OrderModal
            features={orderModalFeatures}
            onSubmit={handleOrderCreated}
            onClose={() => setOrderModalFeatures(null)}
          />
        )}
      </AnimatePresence>
    </div>
  )
}
