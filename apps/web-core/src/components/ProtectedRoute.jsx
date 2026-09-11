import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'

/**
 * Fecha uma rota para quem não entrou.
 *
 * Serve das duas formas: envolvendo um elemento, como a demo faz rota a rota, ou como rota de
 * layout sem filhos, que é como a versão real agrupa tudo de uma vez. Sem o segundo caso, cada
 * rota nova precisa lembrar de se proteger, e uma esquecida não falha em teste nenhum — só fica
 * aberta.
 */
export default function ProtectedRoute({ children }) {
  const { user } = useAuth()
  if (!user) return <Navigate to="/login" replace />
  return children ?? <Outlet />
}
