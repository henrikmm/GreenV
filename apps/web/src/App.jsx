import { useState, useEffect } from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import MapPage from './pages/MapPage'
import OrdersPage from './pages/OrdersPage'
import LoginPage from './pages/LoginPage'
import { AuthProvider } from './auth/AuthContext'
import RequireAuth from './auth/RequireAuth'

export default function App() {
  const [geojson, setGeojson] = useState(null)
  const [marcoKm, setMarcoKm] = useState(null)
  const [orders, setOrders] = useState([])

  useEffect(() => {
    fetch('/rocada_polygons.geojson').then(r => r.json()).then(setGeojson).catch(console.error)
    fetch('/marco_km.geojson').then(r => r.json()).then(setMarcoKm).catch(console.error)

    // Load saved orders from localStorage
    const saved = localStorage.getItem('motiva_orders')
    if (saved) setOrders(JSON.parse(saved))
  }, [])

  const addOrder = (order) => {
    const updated = [order, ...orders]
    setOrders(updated)
    localStorage.setItem('motiva_orders', JSON.stringify(updated))
  }

  const updateOrder = (id, changes) => {
    const updated = orders.map(o => o.id === id ? { ...o, ...changes } : o)
    setOrders(updated)
    localStorage.setItem('motiva_orders', JSON.stringify(updated))
  }

  const deleteOrder = (id) => {
    const updated = orders.filter(o => o.id !== id)
    setOrders(updated)
    localStorage.setItem('motiva_orders', JSON.stringify(updated))
  }

  return (
    <BrowserRouter>
      {/* Inside the router, because the provider navigates when a session ends. */}
      <AuthProvider>
        <Routes>
          <Route path="/entrar" element={<LoginPage />} />
          <Route path="/" element={
            <RequireAuth>
              <MapPage geojson={geojson} marcoKm={marcoKm} onCreateOrder={addOrder} />
            </RequireAuth>
          } />
          <Route path="/ordens" element={
            <RequireAuth>
              <OrdersPage orders={orders} onUpdateOrder={updateOrder} onDeleteOrder={deleteOrder} />
            </RequireAuth>
          } />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  )
}
