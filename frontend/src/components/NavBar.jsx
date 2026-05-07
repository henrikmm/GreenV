import { useNavigate } from 'react-router-dom'

const s = {
  nav: {
    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    padding: '0 24px', height: 48, background: '#5e22f3',
    borderBottom: '1px solid rgba(255,255,255,0.1)', flexShrink: 0,
  },
  left: { display: 'flex', alignItems: 'center', gap: 24 },
  brand: {
    display: 'flex', alignItems: 'center', gap: 8,
    cursor: 'pointer',
  },
  brandIcon: { fontSize: 16 },
  brandText: { fontSize: 15, fontWeight: 700, color: 'white', letterSpacing: '-0.01em' },
  tabs: { display: 'flex', gap: 4 },
  tab: (active) => ({
    padding: '6px 16px', borderRadius: 6, fontSize: 13, fontWeight: 600,
    cursor: 'pointer', transition: 'all 0.15s', border: 'none', fontFamily: 'inherit',
    background: active ? 'rgba(255,255,255,0.2)' : 'transparent',
    color: active ? 'white' : 'rgba(255,255,255,0.6)',
  }),
  right: {
    fontSize: 11, color: 'rgba(255,255,255,0.5)',
    fontFamily: 'var(--font-mono)',
  },
}

export default function NavBar({ currentPage }) {
  const navigate = useNavigate()

  return (
    <nav style={s.nav}>
      <div style={s.left}>
        <div style={s.brand} onClick={() => navigate('/')}>
          <span style={s.brandText}>MOTIVA</span>
        </div>

        <div style={s.tabs}>
          <button style={s.tab(currentPage === 'map')} onClick={() => navigate('/')}>
            Mapa
          </button>
          <button style={s.tab(currentPage === 'orders')} onClick={() => navigate('/ordens')}>
            Ordens de Serviço
          </button>
        </div>
      </div>

      <div style={s.right}>
        SP-021 • Rodoanel Oeste
      </div>
    </nav>
  )
}
