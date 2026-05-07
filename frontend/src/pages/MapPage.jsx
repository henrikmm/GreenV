import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import MapView from '../components/MapView'
import Sidebar from '../components/Sidebar'
import OrderModal from '../components/OrderModal'
import Legend from '../components/Legend'
import NavBar from '../components/NavBar'

export default function MapPage({ geojson, marcoKm, onCreateOrder }) {
  const [selectedFeature, setSelectedFeature] = useState(null)
  const [orderModal, setOrderModal] = useState(null)
  const [activeLayers, setActiveLayers] = useState({ polygons: true, marcoKm: true })
  const [filterLevel, setFilterLevel] = useState(null)
  const navigate = useNavigate()

  const handleOrderCreated = (order) => {
    onCreateOrder(order)
    setOrderModal(null)
  }

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
      </div>

      {orderModal && (
        <OrderModal
          feature={orderModal.feature}
          onSubmit={handleOrderCreated}
          onClose={() => setOrderModal(null)}
        />
      )}
    </div>
  )
}
