import { Link, useLocation } from 'react-router-dom'
import { UserMenu } from '@greenv/web-core'

/** Barra simples: a versão real ainda tem uma tela só, e um menu de quatro abas mentiria. */
export default function AppBar({ children }) {
  const { pathname } = useLocation()
  return (
    <div>
      <header style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '12px 24px', borderBottom: '1px solid var(--border)', background: 'white',
      }}>
        <Link to="/sessoes" style={{ fontWeight: 700, fontSize: 15, color: 'var(--motiva)' }}>
          GreenV
          <span style={{ marginLeft: 8, fontWeight: 400, fontSize: 12, color: 'var(--text-muted)' }}>
            dados reais da API
          </span>
        </Link>
        <nav style={{ display: 'flex', gap: 16, alignItems: 'center', fontSize: 13 }}>
          <Link to="/sessoes" style={{ color: pathname.startsWith('/sessoes') ? 'var(--motiva)' : 'var(--text-muted)' }}>
            Sessões
          </Link>
          <UserMenu />
        </nav>
      </header>
      {children}
    </div>
  )
}
