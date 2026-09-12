import { useNavigate } from 'react-router-dom'
import { motion } from 'framer-motion'
import { LayoutDashboard, Map, ClipboardList, Sprout, Users } from 'lucide-react'
import UserMenu from './UserMenu'

const s = {
  nav: {
    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    padding: '0 20px', height: 52, background: 'var(--motiva)',
    borderBottom: '1px solid rgba(255,255,255,0.1)', flexShrink: 0,
  },
  left: { display: 'flex', alignItems: 'center', gap: 28 },
  brand: { display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' },
  brandIcon: {
    width: 26, height: 26, borderRadius: 7, background: 'rgba(255,255,255,0.16)',
    display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'white',
  },
  brandText: { fontSize: 15, fontWeight: 800, color: 'white', letterSpacing: '-0.01em' },
  tabs: { display: 'flex', gap: 2, position: 'relative' },
  tab: (active) => ({
    position: 'relative', display: 'flex', alignItems: 'center', gap: 7,
    padding: '7px 14px', borderRadius: 7, fontSize: 13, fontWeight: 600,
    cursor: 'pointer', border: 'none', fontFamily: 'inherit', background: 'transparent',
    color: active ? 'white' : 'rgba(255,255,255,0.62)', zIndex: 1, transition: 'color 0.15s',
  }),
  activePill: {
    position: 'absolute', inset: 0, background: 'rgba(255,255,255,0.16)', borderRadius: 7, zIndex: 0,
  },
  right: { display: 'flex', alignItems: 'center', gap: 16 },
  roadTag: { fontSize: 11, color: 'rgba(255,255,255,0.5)', fontFamily: 'var(--font-mono)' },
}

/** As abas da demonstração. A versão que lê a API passa as suas, que são outras. */
export const DEMO_TABS = [
  { key: 'dashboard', label: 'Visão Geral', path: '/', icon: LayoutDashboard },
  { key: 'map', label: 'Mapa', path: '/mapa', icon: Map },
  { key: 'orders', label: 'Ordens de Serviço', path: '/ordens', icon: ClipboardList },
  { key: 'teams', label: 'Equipes', path: '/equipes', icon: Users },
]

export { LayoutDashboard, Map, ClipboardList, Users }

/**
 * A barra do topo.
 *
 * As abas e o rótulo da via vêm por propriedade porque as duas aplicações não têm as mesmas
 * telas nem falam da mesma via: a demonstração é sempre o Rodoanel, e a versão real mostra
 * qualquer sessão onde ela tenha sido capturada.
 */
export default function NavBar({ currentPage, tabs = DEMO_TABS, roadTag = 'SP-021 · RODOANEL OESTE' }) {
  const navigate = useNavigate()

  return (
    <nav style={s.nav}>
      <div style={s.left}>
        <div style={s.brand} onClick={() => navigate('/')}>
          <div style={s.brandIcon}><Sprout size={15} /></div>
          <span style={s.brandText}>MOTIVA</span>
        </div>

        <div style={s.tabs}>
          {tabs.map(t => {
            const Icon = t.icon
            const active = currentPage === t.key
            return (
              <button key={t.key} style={s.tab(active)} onClick={() => navigate(t.path)}>
                {active && (
                  <motion.div style={s.activePill} layoutId="nav-active-pill"
                    transition={{ type: 'spring', stiffness: 500, damping: 38 }} />
                )}
                <Icon size={14} style={{ position: 'relative', zIndex: 1 }} />
                <span style={{ position: 'relative', zIndex: 1 }}>{t.label}</span>
              </button>
            )
          })}
        </div>
      </div>

      <div style={s.right}>
        <span style={s.roadTag}>{roadTag}</span>
        <UserMenu />
      </div>
    </nav>
  )
}
