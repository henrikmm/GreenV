import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from './AuthContext'

const s = {
  splash: {
    height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center',
    background: 'var(--bg-secondary)', color: 'var(--text-muted)',
    fontSize: 13, fontFamily: 'var(--font-sans)',
  },
}

export default function RequireAuth({ children }) {
  const { status } = useAuth()
  const location = useLocation()

  // Without this the login page flashes on every reload for someone already signed in: the check
  // is a round trip, and 'anonymous' is only true once it has come back.
  if (status === 'checking') {
    return <div style={s.splash}>Verificando sessão…</div>
  }

  if (status === 'anonymous') {
    return <Navigate to="/entrar" replace state={{ from: location }} />
  }

  return children
}
