import { useState, useEffect } from 'react'
import MapView from './components/MapView'
import Sidebar from './components/Sidebar'
import OrderModal from './components/OrderModal'
import Legend from './components/Legend'

export default function App() {
  const [geojson, setGeojson] = useState(null)
  const [marcoKm, setMarcoKm] = useState(null)
  const [selectedFeature, setSelectedFeature] = useState(null)
  const [orderModal, setOrderModal] = useState(null)
  const [activeLayers, setActiveLayers] = useState({ polygons: true, marcoKm: true })
  const [filterLevel, setFilterLevel] = useState(null)

  useEffect(() => {
    fetch('/rocada_polygons.geojson').then(r => r.json()).then(setGeojson).catch(console.error)
    fetch('/marco_km.geojson').then(r => r.json()).then(setMarcoKm).catch(console.error)
  }, [])

  return (
    <div style={{ display: 'flex', height: '100vh', width: '100vw' }}>
      <Sidebar
        activeLayers={activeLayers}
        onToggleLayer={(layer) => setActiveLayers(prev => ({ ...prev, [layer]: !prev[layer] }))}
        filterLevel={filterLevel}
        onFilterLevel={setFilterLevel}
        selectedFeature={selectedFeature}
        onCreateOrder={() => selectedFeature && setOrderModal({ feature: selectedFeature })}
        geojson={geojson}
      />

      <div style={{ flex: 1, position: 'relative' }}>
        <MapView
          geojson={geojson}
          marcoKm={marcoKm}
          activeLayers={activeLayers}
          filterLevel={filterLevel}
          onPolygonClick={setSelectedFeature}
          onCreateOrder={(feature) => setOrderModal({ feature })}
        />
        <Legend />
      </div>

      {orderModal && (
        <OrderModal feature={orderModal.feature} onClose={() => setOrderModal(null)} />
      )}
    </div>
  )
}
