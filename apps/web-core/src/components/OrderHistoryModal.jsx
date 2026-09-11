import { motion } from 'framer-motion'
import { X, History } from 'lucide-react'
import { STATUS_MAP } from '../orderMeta'

const s = {
  backdrop: {
    position: 'fixed', inset: 0, background: 'var(--bg-overlay)',
    backdropFilter: 'blur(6px)', display: 'flex', alignItems: 'center',
    justifyContent: 'center', zIndex: 9999,
  },
  modal: {
    background: 'white', border: '1px solid var(--border)',
    borderRadius: 'var(--radius-lg)', width: 420, maxHeight: '80vh',
    overflowY: 'auto', boxShadow: 'var(--shadow-modal)',
  },
  header: { padding: '22px 24px 4px', display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' },
  titleRow: { display: 'flex', alignItems: 'center', gap: 8 },
  title: { fontSize: 16, fontWeight: 700 },
  orderId: { fontSize: 11.5, fontFamily: 'var(--font-mono)', color: 'var(--text-muted)', marginTop: 3 },
  closeBtn: {
    background: 'var(--bg-secondary)', border: '1px solid var(--border)',
    borderRadius: 'var(--radius-sm)', color: 'var(--text-secondary)',
    width: 30, height: 30, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer',
  },
  body: { padding: '20px 24px 24px' },
  item: { position: 'relative', paddingLeft: 26, paddingBottom: 22 },
  itemLast: { paddingBottom: 0 },
  dot: (c) => ({
    position: 'absolute', left: 0, top: 2, width: 12, height: 12, borderRadius: '50%',
    background: c, border: '2px solid white', boxShadow: `0 0 0 2px ${c}`,
  }),
  line: {
    position: 'absolute', left: 5, top: 14, bottom: 0, width: 2, background: 'var(--border)',
  },
  statusLabel: { fontSize: 13.5, fontWeight: 700 },
  meta: { fontSize: 11.5, color: 'var(--text-muted)', marginTop: 2 },
  empty: { fontSize: 12.5, color: 'var(--text-muted)', textAlign: 'center', padding: '20px 0' },
}

export default function OrderHistoryModal({ order, onClose }) {
  const history = order.history?.length ? order.history : [{ status: order.status, at: order.createdAt, by: null }]

  return (
    <motion.div
      style={s.backdrop} onClick={(e) => e.target === e.currentTarget && onClose()}
      initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
    >
      <motion.div
        style={s.modal}
        initial={{ opacity: 0, y: 20, scale: 0.98 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        exit={{ opacity: 0, y: 12, scale: 0.98 }}
        transition={{ duration: 0.22, ease: [0.16, 1, 0.3, 1] }}
      >
        <div style={s.header}>
          <div>
            <div style={s.titleRow}><History size={16} /> <span style={s.title}>Histórico da OS</span></div>
            <div style={s.orderId}>{order.id}</div>
          </div>
          <button style={s.closeBtn} onClick={onClose}><X size={15} /></button>
        </div>

        <div style={s.body}>
          {history.length === 0 ? (
            <div style={s.empty}>Sem histórico registrado.</div>
          ) : (
            history.map((h, i) => {
              const meta = STATUS_MAP[h.status] || STATUS_MAP.pendente
              const isLast = i === history.length - 1
              return (
                <motion.div
                  key={i} style={{ ...s.item, ...(isLast ? s.itemLast : {}) }}
                  initial={{ opacity: 0, x: -8 }} animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: i * 0.07, duration: 0.25 }}
                >
                  {!isLast && <div style={s.line} />}
                  <div style={s.dot(meta.color)} />
                  <div style={s.statusLabel}>{meta.label}</div>
                  <div style={s.meta}>
                    {new Date(h.at).toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' })}
                    {h.by ? ` · ${h.by}` : ' · criação automática'}
                  </div>
                </motion.div>
              )
            })
          )}
        </div>
      </motion.div>
    </motion.div>
  )
}
