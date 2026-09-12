import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider, ToastProvider, ToastContainer, ProtectedRoute, useAuth } from '@greenv/web-core'
import { auth } from './api/greenv'
import LoginPage from './pages/LoginPage'
import SessionsPage from './pages/SessionsPage'
import SessionDetailPage from './pages/SessionDetailPage'
import MapPage from './pages/MapPage'

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
  if (restoring) return <div style={{ padding: 24, color: 'var(--text-muted)' }}>Verificando sessão…</div>
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<ProtectedRoute />}>
        <Route path="/" element={<Navigate to="/sessoes" replace />} />
        <Route path="/sessoes" element={<SessionsPage />} />
        <Route path="/sessoes/:sessionId" element={<SessionDetailPage />} />
        <Route path="/mapa" element={<MapPage />} />
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
