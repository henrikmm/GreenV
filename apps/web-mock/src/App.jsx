import { useState, useEffect, useRef } from 'react'
import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { AuthProvider } from '@greenv/web-core'
import { signIn, signOut, restore } from './auth/mockAuth'
import { ToastProvider } from '@greenv/web-core'
import { ToastContainer } from '@greenv/web-core'
import { ProtectedRoute } from '@greenv/web-core'
import LoginPage from './pages/LoginPage'
import DashboardPage from './pages/DashboardPage'
import MapPage from './pages/MapPage'
import OrdersPage from './pages/OrdersPage'
import TeamsPage from './pages/TeamsPage'
import TeamDetailPage from './pages/TeamDetailPage'
import { generateMockOrders } from './data/mockOrders'

const ORDERS_KEY = 'motiva_orders'

export default function App() {
  const [geojson, setGeojson] = useState(null)
  const [marcoKm, setMarcoKm] = useState(null)
  const [orders, setOrders] = useState([])
  const seeded = useRef(false)

  useEffect(() => {
    fetch('/rocada_polygons.geojson').then(r => r.json()).then(setGeojson).catch(console.error)
    fetch('/marco_km.geojson').then(r => r.json()).then(setMarcoKm).catch(console.error)

    const saved = localStorage.getItem(ORDERS_KEY)
    if (saved) setOrders(JSON.parse(saved))
  }, [])

  // Semeia ordens mockadas na primeira carga (sem OS salvas ainda) assim que o geojson chega.
  useEffect(() => {
    if (seeded.current) return
    if (localStorage.getItem(ORDERS_KEY)) { seeded.current = true; return }
    if (!geojson || !marcoKm) return
    seeded.current = true
    const mock = generateMockOrders(geojson, marcoKm)
    setOrders(mock)
    localStorage.setItem(ORDERS_KEY, JSON.stringify(mock))
  }, [geojson, marcoKm])

  const addOrder = (order) => {
    const updated = [order, ...orders]
    setOrders(updated)
    localStorage.setItem(ORDERS_KEY, JSON.stringify(updated))
  }

  const updateOrder = (id, changes) => {
    const updated = orders.map(o => o.id === id ? { ...o, ...changes } : o)
    setOrders(updated)
    localStorage.setItem(ORDERS_KEY, JSON.stringify(updated))
  }

  const deleteOrder = (id) => {
    const updated = orders.filter(o => o.id !== id)
    setOrders(updated)
    localStorage.setItem(ORDERS_KEY, JSON.stringify(updated))
  }

  return (
    <ToastProvider>
      <AuthProvider signIn={signIn} signOut={signOut} restore={restore}>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/" element={
              <ProtectedRoute>
                <DashboardPage geojson={geojson} orders={orders} />
              </ProtectedRoute>
            } />
            <Route path="/mapa" element={
              <ProtectedRoute>
                <MapPage geojson={geojson} marcoKm={marcoKm} orders={orders} onCreateOrder={addOrder} />
              </ProtectedRoute>
            } />
            <Route path="/ordens" element={
              <ProtectedRoute>
                <OrdersPage orders={orders} onUpdateOrder={updateOrder} onDeleteOrder={deleteOrder} onRestoreOrder={addOrder} />
              </ProtectedRoute>
            } />
            <Route path="/equipes" element={
              <ProtectedRoute>
                <TeamsPage orders={orders} />
              </ProtectedRoute>
            } />
            <Route path="/equipes/:teamId" element={
              <ProtectedRoute>
                <TeamDetailPage orders={orders} />
              </ProtectedRoute>
            } />
          </Routes>
        </BrowserRouter>
      </AuthProvider>
      <ToastContainer />
    </ToastProvider>
  )
}
