import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider, ToastProvider, ToastContainer, ProtectedRoute, useAuth } from '@greenv/web-core'
import { auth } from './api/greenv'
import WakingScreen from './components/WakingScreen'
import LoginPage from './pages/LoginPage'
import SessionsPage from './pages/SessionsPage'
import SessionDetailPage from './pages/SessionDetailPage'
import MapPage from './pages/MapPage'
import SegmentsPage from './pages/SegmentsPage'
import OrdersPage from './pages/OrdersPage'
import TeamsPage from './pages/TeamsPage'

/**
 * A versão que fala com a API.
 *
 * A diferença estrutural em relação à demo é que aqui nada é semeado: as telas abrem vazias e se
 * preenchem com o que a API tem. Se não houver captura nenhuma, a lista fica vazia, e isso é a
 * resposta certa.
 */
function Shell() {
  const { restoring } = useAuth()
  // Sem isto, a recarga da página manda o usuário ao login antes de a resposta do /me chegar.
  // E essa espera pode durar vinte segundos, porque a API escala para zero — daí a tela contar
  // o que está fazendo em vez de ficar parada, que foi reportado como erro.
  if (restoring) return <WakingScreen />
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<ProtectedRoute />}>
        <Route path="/" element={<Navigate to="/sessoes" replace />} />
        <Route path="/sessoes" element={<SessionsPage />} />
        <Route path="/sessoes/:sessionId" element={<SessionDetailPage />} />
        <Route path="/trechos" element={<SegmentsPage />} />
        <Route path="/mapa" element={<MapPage />} />
        <Route path="/ordens" element={<OrdersPage />} />
        <Route path="/equipes" element={<TeamsPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/sessoes" replace />} />
    </Routes>
  )
}

export default function App() {
  return (
    <ToastProvider>
      <AuthProvider signIn={auth.signIn} signOut={auth.signOut} restore={auth.restore}>
        <BrowserRouter>
          <Shell />
        </BrowserRouter>
        <ToastContainer />
      </AuthProvider>
    </ToastProvider>
  )
}
