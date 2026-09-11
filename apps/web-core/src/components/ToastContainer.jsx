import { AnimatePresence, motion } from 'framer-motion'
import { CheckCircle2, AlertTriangle, Info, X, Undo2 } from 'lucide-react'
import { useToast } from '../context/ToastContext'

const ICONS = { success: CheckCircle2, danger: AlertTriangle, info: Info }
const COLORS = { success: '#16a34a', danger: '#dc2626', info: 'var(--motiva)' }

const s = {
  wrap: {
    position: 'fixed', bottom: 20, right: 20, zIndex: 10000,
    display: 'flex', flexDirection: 'column', gap: 8, width: 320,
  },
  toast: {
    display: 'flex', alignItems: 'flex-start', gap: 10, padding: '12px 14px',
    background: 'white', border: '1px solid var(--border)', borderRadius: 'var(--radius-md)',
    boxShadow: 'var(--shadow-float)',
  },
  message: { fontSize: 12.5, color: 'var(--text-primary)', fontWeight: 500, lineHeight: 1.4 },
  actionBtn: {
    display: 'flex', alignItems: 'center', gap: 4, fontSize: 12, fontWeight: 700,
    color: 'var(--motiva)', background: 'none', border: 'none', cursor: 'pointer',
    fontFamily: 'inherit', marginTop: 4, padding: 0,
  },
  closeBtn: {
    background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer',
    display: 'flex', flexShrink: 0, marginLeft: 'auto',
  },
}

export default function ToastContainer() {
  const { toasts, removeToast } = useToast()

  return (
    <div style={s.wrap}>
      <AnimatePresence>
        {toasts.map(t => {
          const Icon = ICONS[t.type] || Info
          const color = COLORS[t.type] || COLORS.info
          return (
            <motion.div
              key={t.id} style={s.toast}
              initial={{ opacity: 0, x: 40, scale: 0.95 }}
              animate={{ opacity: 1, x: 0, scale: 1 }}
              exit={{ opacity: 0, x: 40, scale: 0.95 }}
              transition={{ type: 'spring', stiffness: 400, damping: 32 }}
            >
              <Icon size={17} style={{ color, flexShrink: 0, marginTop: 1 }} />
              <div style={{ flex: 1 }}>
                <div style={s.message}>{t.message}</div>
                {t.actionLabel && (
                  <button
                    style={s.actionBtn}
                    onClick={() => { t.onAction?.(); removeToast(t.id) }}
                  >
                    <Undo2 size={12} /> {t.actionLabel}
                  </button>
                )}
              </div>
              <button style={s.closeBtn} onClick={() => removeToast(t.id)}><X size={14} /></button>
            </motion.div>
          )
        })}
      </AnimatePresence>
    </div>
  )
}
