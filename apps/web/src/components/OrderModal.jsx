import { useState } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { X, Check, MapPin } from 'lucide-react'
import { EQUIPMENT_TYPES, LEVELS, formatArea, generateOrderId } from '../utils/classification'
import { buildOrder } from '../utils/orders'
import { TEAMS } from '../data/mockTeams'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'

const s = {
  backdrop: {
    position: 'fixed', inset: 0, background: 'var(--bg-overlay)',
    backdropFilter: 'blur(6px)', display: 'flex', alignItems: 'center',
    justifyContent: 'center', zIndex: 9999,
  },
  modal: {
    background: 'white', border: '1px solid var(--border)',
    borderRadius: 'var(--radius-lg)', width: 480, maxHeight: '90vh',
    overflowY: 'auto', boxShadow: 'var(--shadow-modal)',
  },
  header: { padding: '24px 24px 0', display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' },
  modalTitle: { fontSize: 18, fontWeight: 700 },
  orderId: { fontSize: 12, fontFamily: 'var(--font-mono)', color: 'var(--text-muted)', marginTop: 4 },
  closeBtn: {
    background: 'var(--bg-secondary)', border: '1px solid var(--border)',
    borderRadius: 'var(--radius-sm)', color: 'var(--text-secondary)',
    width: 32, height: 32, display: 'flex', alignItems: 'center',
    justifyContent: 'center', cursor: 'pointer', fontFamily: 'inherit',
  },
  body: { padding: 24 },
  infoGrid: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginBottom: 20 },
  infoBox: {
    padding: '12px 14px', background: 'var(--bg-secondary)',
    borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)',
  },
  infoLabel: { fontSize: 10, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 4 },
  infoValue: { fontSize: 13, fontWeight: 600 },
  trechoList: { marginBottom: 20, border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)', overflow: 'hidden' },
  trechoRow: {
    display: 'flex', alignItems: 'center', gap: 9, padding: '10px 12px',
    borderBottom: '1px solid var(--border)', background: 'white',
  },
  trechoDot: (c) => ({ width: 8, height: 8, borderRadius: '50%', background: c, flexShrink: 0 }),
  trechoName: { fontSize: 12.5, fontWeight: 600, flex: 1 },
  trechoArea: { fontSize: 11.5, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' },
  summaryBox: {
    display: 'flex', gap: 10, padding: '10px 12px', background: 'var(--motiva-subtle)',
    fontSize: 11.5, color: 'var(--text-secondary)',
  },
  divider: { height: 1, background: 'var(--border)', margin: '20px 0' },
  formGroup: { marginBottom: 16 },
  label: { display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--text-secondary)', marginBottom: 6 },
  input: {
    width: '100%', padding: '10px 12px', background: 'var(--bg-secondary)',
    border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)',
    color: 'var(--text-primary)', fontSize: 13, fontFamily: 'inherit', outline: 'none',
  },
  select: {
    width: '100%', padding: '10px 12px', background: 'var(--bg-secondary)',
    border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)',
    color: 'var(--text-primary)', fontSize: 13, fontFamily: 'inherit',
    outline: 'none', appearance: 'none', cursor: 'pointer',
  },
  textarea: {
    width: '100%', padding: '10px 12px', background: 'var(--bg-secondary)',
    border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)',
    color: 'var(--text-primary)', fontSize: 13, fontFamily: 'inherit',
    outline: 'none', resize: 'vertical', minHeight: 72,
  },
  footer: { padding: '0 24px 24px', display: 'flex', gap: 10 },
  btnPrimary: {
    flex: 1, padding: '12px 20px', background: 'var(--motiva)', color: 'white',
    border: 'none', borderRadius: 'var(--radius-sm)', fontSize: 13,
    fontWeight: 700, cursor: 'pointer', fontFamily: 'inherit',
  },
  btnSecondary: {
    padding: '12px 20px', background: 'var(--bg-secondary)', color: 'var(--text-secondary)',
    border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)',
    fontSize: 13, fontWeight: 600, cursor: 'pointer', fontFamily: 'inherit',
  },
  success: {
    position: 'absolute', inset: 0, background: 'white',
    borderRadius: 'var(--radius-lg)', display: 'flex', flexDirection: 'column',
    alignItems: 'center', justifyContent: 'center', gap: 12,
  },
  successIcon: {
    width: 56, height: 56, borderRadius: '50%', background: 'rgba(22,163,74,0.12)',
    display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#16a34a',
  },
}

export default function OrderModal({ features, onSubmit, onClose }) {
  const [submitted, setSubmitted] = useState(false)
  const [orderId] = useState(() => generateOrderId())
  const [form, setForm] = useState({ priority: 'media', team: '', scheduledDate: '', notes: '' })

  const { user } = useAuth()
  const { addToast } = useToast()
  const isCombined = features.length > 1
  const totalArea = features.reduce((sum, f) => sum + (f.properties.area_m2 || 0), 0)
  const maxLevel = Math.max(...features.map(f => f.properties.vegetation_level || 1))
  const lvl = LEVELS[maxLevel]
  const equipmentSet = [...new Set(features.map(f => (EQUIPMENT_TYPES[f.properties.name] || { short: f.properties.name }).short))]

  const handleSubmit = () => {
    const team = form.team ? (TEAMS.find(t => t.id === form.team)?.name || form.team) : ''
    const order = buildOrder(features, {
      priority: form.priority,
      team,
      scheduledDate: form.scheduledDate,
      notes: form.notes,
      status: 'pendente',
      id: orderId,
      by: user?.name,
    })
    onSubmit(order)
    addToast({
      type: 'success',
      message: isCombined
        ? `${order.id} criada — ${features.length} trechos combinados numa OS só.`
        : `${order.id} criada com sucesso.`,
    })
    setSubmitted(true)
    setTimeout(() => onClose(), 1500)
  }

  return (
    <motion.div
      style={s.backdrop} onClick={(e) => e.target === e.currentTarget && onClose()}
      initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
    >
      <motion.div
        style={{ ...s.modal, position: 'relative' }}
        initial={{ opacity: 0, y: 24, scale: 0.98 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        exit={{ opacity: 0, y: 16, scale: 0.98 }}
        transition={{ duration: 0.25, ease: [0.16, 1, 0.3, 1] }}
      >
        <AnimatePresence>
          {submitted && (
            <motion.div style={s.success} initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
              <motion.div style={s.successIcon}
                initial={{ scale: 0.5, opacity: 0 }} animate={{ scale: 1, opacity: 1 }}
                transition={{ type: 'spring', stiffness: 400, damping: 20 }}>
                <Check size={28} />
              </motion.div>
              <div style={{ fontSize: 16, fontWeight: 700 }}>
                {isCombined ? 'OS Combinada Criada' : 'Ordem Criada'}
              </div>
              <div style={{ fontSize: 13, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>{orderId}</div>
            </motion.div>
          )}
        </AnimatePresence>

        <div style={s.header}>
          <div>
            <div style={s.modalTitle}>
              {isCombined ? `Nova OS Combinada — ${features.length} trechos` : 'Nova Ordem de Serviço'}
            </div>
            <div style={s.orderId}>{orderId}</div>
          </div>
          <button style={s.closeBtn} onClick={onClose}><X size={16} /></button>
        </div>

        <div style={s.body}>
          {isCombined ? (
            <div style={s.trechoList}>
              {features.map((f, i) => {
                const level = f.properties.vegetation_level || 1
                const eq = EQUIPMENT_TYPES[f.properties.name] || { short: f.properties.name }
                return (
                  <div key={i} style={s.trechoRow}>
                    <span style={s.trechoDot(LEVELS[level].color)} />
                    <MapPin size={12} style={{ color: 'var(--text-muted)' }} />
                    <span style={s.trechoName}>{eq.short}</span>
                    <span style={s.trechoArea}>{formatArea(f.properties.area_m2)}</span>
                  </div>
                )
              })}
              <div style={s.summaryBox}>
                <span><strong>{formatArea(totalArea)}</strong> ao todo</span>
                <span>·</span>
                <span>Prioridade sugerida: <strong style={{ color: lvl.color }}>{lvl.label}</strong></span>
              </div>
            </div>
          ) : (
            <div style={s.infoGrid}>
              <div style={s.infoBox}>
                <div style={s.infoLabel}>Classificação</div>
                <div style={{ ...s.infoValue, color: lvl.color }}>{lvl.label} — {lvl.desc}</div>
              </div>
              <div style={s.infoBox}>
                <div style={s.infoLabel}>Área</div>
                <div style={s.infoValue}>{formatArea(features[0].properties.area_m2)}</div>
              </div>
              <div style={s.infoBox}>
                <div style={s.infoLabel}>Equipamento</div>
                <div style={s.infoValue}>{equipmentSet.join(', ')}</div>
              </div>
              <div style={s.infoBox}>
                <div style={s.infoLabel}>Coordenadas</div>
                <div style={{ ...s.infoValue, fontFamily: 'var(--font-mono)', fontSize: 11 }}>
                  {features[0].properties.centroid_lat.toFixed(5)}, {features[0].properties.centroid_lon.toFixed(5)}
                </div>
              </div>
            </div>
          )}

          <div style={s.divider} />

          <div style={s.formGroup}>
            <label style={s.label}>Prioridade</label>
            <select style={s.select} value={form.priority}
              onChange={e => setForm(f => ({ ...f, priority: e.target.value }))}>
              <option value="baixa">Baixa — Nível 1</option>
              <option value="media">Média — Nível 2</option>
              <option value="alta">Alta — Nível 3</option>
              <option value="urgente">Urgente — Risco à segurança</option>
            </select>
          </div>

          <div style={s.formGroup}>
            <label style={s.label}>Equipe Responsável</label>
            <select style={s.select} value={form.team}
              onChange={e => setForm(f => ({ ...f, team: e.target.value }))}>
              <option value="">Selecionar equipe...</option>
              {TEAMS.map(t => (
                <option key={t.id} value={t.id}>{t.name}</option>
              ))}
            </select>
          </div>

          <div style={s.formGroup}>
            <label style={s.label}>Data Prevista</label>
            <input type="date" style={s.input} value={form.scheduledDate}
              onChange={e => setForm(f => ({ ...f, scheduledDate: e.target.value }))} />
          </div>

          <div style={s.formGroup}>
            <label style={s.label}>Observações</label>
            <textarea style={s.textarea} placeholder="Detalhes adicionais, condições de acesso, riscos..."
              value={form.notes} onChange={e => setForm(f => ({ ...f, notes: e.target.value }))} />
          </div>
        </div>

        <div style={s.footer}>
          <button style={s.btnSecondary} onClick={onClose}>Cancelar</button>
          <motion.button style={s.btnPrimary} onClick={handleSubmit}
            whileHover={{ background: 'var(--motiva-hover)' }} whileTap={{ scale: 0.98 }}>
            {isCombined ? 'Criar OS Combinada' : 'Criar Ordem de Serviço'}
          </motion.button>
        </div>
      </motion.div>
    </motion.div>
  )
}
