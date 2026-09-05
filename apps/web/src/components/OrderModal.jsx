import { useState } from 'react'
import { EQUIPMENT_TYPES, LEVELS, vegetationLevel, formatArea, generateOrderId } from '../utils/classification'

const TEAMS = {
  eq1: 'Equipe 1 — Zona Norte',
  eq2: 'Equipe 2 — Zona Sul',
  eq3: 'Equipe 3 — Manutenção Especial',
  terceirizada: 'Terceirizada',
}

const s = {
  backdrop: {
    position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.45)',
    backdropFilter: 'blur(6px)', display: 'flex', alignItems: 'center',
    justifyContent: 'center', zIndex: 9999, animation: 'fadeIn 0.2s ease',
  },
  modal: {
    background: 'white', border: '1px solid var(--border)',
    borderRadius: 'var(--radius-lg)', width: 460, maxHeight: '90vh',
    overflowY: 'auto', boxShadow: 'var(--shadow-modal)', animation: 'slideUp 0.25s ease',
  },
  header: { padding: '24px 24px 0', display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' },
  modalTitle: { fontSize: 18, fontWeight: 700 },
  orderId: { fontSize: 12, fontFamily: 'var(--font-mono)', color: 'var(--text-muted)', marginTop: 4 },
  closeBtn: {
    background: 'var(--bg-secondary)', border: '1px solid var(--border)',
    borderRadius: 'var(--radius-sm)', color: 'var(--text-secondary)',
    width: 32, height: 32, display: 'flex', alignItems: 'center',
    justifyContent: 'center', cursor: 'pointer', fontSize: 16, fontFamily: 'inherit',
  },
  body: { padding: 24 },
  infoGrid: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginBottom: 20 },
  infoBox: {
    padding: '12px 14px', background: 'var(--bg-secondary)',
    borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)',
  },
  infoLabel: { fontSize: 10, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 4 },
  infoValue: { fontSize: 13, fontWeight: 600 },
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
    flex: 1, padding: '12px 20px', background: '#5e22f3', color: 'white',
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
    alignItems: 'center', justifyContent: 'center', gap: 12, animation: 'fadeIn 0.3s ease',
  },
  successIcon: {
    width: 56, height: 56, borderRadius: '50%', background: 'rgba(22,163,74,0.12)',
    display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 28,
  },
}

export default function OrderModal({ feature, onSubmit, onClose }) {
  const [submitted, setSubmitted] = useState(false)
  const [form, setForm] = useState({ priority: 'media', team: '', scheduledDate: '', notes: '' })

  const orderId = generateOrderId()
  const eq = EQUIPMENT_TYPES[feature.properties.name] || { short: feature.properties.name }
  const level = vegetationLevel(feature.properties.vegetation_level)
  const lvl = LEVELS[level]

  const handleSubmit = () => {
    const order = {
      id: orderId,
      createdAt: new Date().toISOString(),
      status: 'pendente',
      priority: form.priority,
      team: form.team ? TEAMS[form.team] || form.team : '',
      scheduledDate: form.scheduledDate,
      notes: form.notes,
      equipment: eq.short,
      area: formatArea(feature.properties.area_m2),
      areaM2: feature.properties.area_m2,
      vegetationLevel: level,
      lat: feature.properties.centroid_lat,
      lon: feature.properties.centroid_lon,
    }
    onSubmit(order)
    setSubmitted(true)
    setTimeout(() => onClose(), 1500)
  }

  return (
    <div style={s.backdrop} onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div style={{ ...s.modal, position: 'relative' }}>
        {submitted && (
          <div style={s.success}>
            <div style={s.successIcon}>✓</div>
            <div style={{ fontSize: 16, fontWeight: 700 }}>Ordem Criada</div>
            <div style={{ fontSize: 13, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>{orderId}</div>
          </div>
        )}

        <div style={s.header}>
          <div>
            <div style={s.modalTitle}>Nova Ordem de Serviço</div>
            <div style={s.orderId}>{orderId}</div>
          </div>
          <button style={s.closeBtn} onClick={onClose}>✕</button>
        </div>

        <div style={s.body}>
          <div style={s.infoGrid}>
            <div style={s.infoBox}>
              <div style={s.infoLabel}>Classificação</div>
              <div style={{ ...s.infoValue, color: lvl.color }}>{lvl.label} — {lvl.desc}</div>
            </div>
            <div style={s.infoBox}>
              <div style={s.infoLabel}>Área</div>
              <div style={s.infoValue}>{formatArea(feature.properties.area_m2)}</div>
            </div>
            <div style={s.infoBox}>
              <div style={s.infoLabel}>Equipamento</div>
              <div style={s.infoValue}>{eq.short}</div>
            </div>
            <div style={s.infoBox}>
              <div style={s.infoLabel}>Coordenadas</div>
              <div style={{ ...s.infoValue, fontFamily: 'var(--font-mono)', fontSize: 11 }}>
                {feature.properties.centroid_lat.toFixed(5)}, {feature.properties.centroid_lon.toFixed(5)}
              </div>
            </div>
          </div>

          <div style={s.divider} />

          <div style={s.formGroup}>
            <label style={s.label}>Prioridade</label>
            <select style={s.select} value={form.priority}
              onChange={e => setForm(f => ({ ...f, priority: e.target.value }))}>
              <option value="baixa">🟢 Baixa — Nível 1</option>
              <option value="media">🟡 Média — Nível 2</option>
              <option value="alta">🔴 Alta — Nível 3</option>
              <option value="urgente">🚨 Urgente — Risco à segurança</option>
            </select>
          </div>

          <div style={s.formGroup}>
            <label style={s.label}>Equipe Responsável</label>
            <select style={s.select} value={form.team}
              onChange={e => setForm(f => ({ ...f, team: e.target.value }))}>
              <option value="">Selecionar equipe...</option>
              {Object.entries(TEAMS).map(([k, v]) => (
                <option key={k} value={k}>{v}</option>
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
          <button style={s.btnPrimary} onClick={handleSubmit}
            onMouseOver={e => e.target.style.background = '#4c1ad0'}
            onMouseOut={e => e.target.style.background = '#5e22f3'}>
            Criar Ordem de Serviço
          </button>
        </div>
      </div>

      <style>{`
        @keyframes fadeIn { from{opacity:0} to{opacity:1} }
        @keyframes slideUp { from{opacity:0;transform:translateY(20px)} to{opacity:1;transform:translateY(0)} }
      `}</style>
    </div>
  )
}
