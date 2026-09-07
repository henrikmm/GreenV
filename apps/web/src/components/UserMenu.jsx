import { useState, useRef, useEffect } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { LogOut, ChevronDown } from 'lucide-react'
import { useAuth } from '../context/AuthContext'

const s = {
  wrap: { position: 'relative' },
  trigger: {
    display: 'flex', alignItems: 'center', gap: 8, background: 'rgba(255,255,255,0.12)',
    border: '1px solid rgba(255,255,255,0.16)', borderRadius: 999, padding: '4px 10px 4px 4px',
    cursor: 'pointer', color: 'white',
  },
  avatar: {
    width: 26, height: 26, borderRadius: '50%', background: 'white', color: 'var(--motiva)',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    fontSize: 11, fontWeight: 700, flexShrink: 0,
  },
  name: { fontSize: 12.5, fontWeight: 600 },
  menu: {
    position: 'absolute', top: 'calc(100% + 8px)', right: 0, width: 220,
    background: 'white', border: '1px solid var(--border)', borderRadius: 'var(--radius-md)',
    boxShadow: 'var(--shadow-float)', overflow: 'hidden', zIndex: 2000,
  },
  menuHeader: { padding: '14px 16px', borderBottom: '1px solid var(--border)' },
  menuName: { fontSize: 13.5, fontWeight: 700, color: 'var(--text-primary)' },
  menuRole: { fontSize: 11.5, color: 'var(--text-muted)', marginTop: 2 },
  menuItem: {
    display: 'flex', alignItems: 'center', gap: 9, width: '100%', padding: '11px 16px',
    background: 'none', border: 'none', fontSize: 12.5, color: 'var(--text-secondary)',
    cursor: 'pointer', fontFamily: 'inherit', textAlign: 'left',
  },
}

export default function UserMenu() {
  const { user, logout } = useAuth()
  const [open, setOpen] = useState(false)
  const ref = useRef(null)

  useEffect(() => {
    const onClick = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false) }
    document.addEventListener('mousedown', onClick)
    return () => document.removeEventListener('mousedown', onClick)
  }, [])

  if (!user) return null

  return (
    <div style={s.wrap} ref={ref}>
      <button style={s.trigger} onClick={() => setOpen(v => !v)}>
        <div style={s.avatar}>{user.initials}</div>
        <span style={s.name}>{user.name.split(' ')[0]}</span>
        <ChevronDown size={13} />
      </button>

      <AnimatePresence>
        {open && (
          <motion.div
            style={s.menu}
            initial={{ opacity: 0, y: -6, scale: 0.97 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -6, scale: 0.97 }}
            transition={{ duration: 0.15 }}
          >
            <div style={s.menuHeader}>
              <div style={s.menuName}>{user.name}</div>
              <div style={s.menuRole}>{user.role}</div>
            </div>
            <button style={s.menuItem} onClick={logout}>
              <LogOut size={14} /> Sair
            </button>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}
