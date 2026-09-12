import { useState } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { X, Check } from 'lucide-react'
import { LEVELS, vegetationLevel, PRIORITY_MAP, useToast } from '@greenv/web-core'
import { serviceOrders } from '../api/greenv'

/**
 * Abrir uma ordem a partir de trechos medidos.
 *
 * O irmão deste na demonstração é `OrderModal`, e o formulário é o mesmo. O que muda é quem
 * calcula o quê. Lá o modal monta a ordem inteira no navegador: inventa o identificador, soma a
 * área dos polígonos e deduz o equipamento do nome do trecho. Aqui ele só envia a decisão — os
 * alvos, a prioridade, a equipe, a data e a observação — e a API deriva o resto das medições.
 *
 * É a diferença entre uma ordem que afirma uma altura e uma ordem que aponta para a medição de
 * onde a altura veio. Por isso a referência só aparece depois da resposta: quem a emite é o
 * servidor, e fingir um número antes disso seria mostrar algo que pode não ser o que foi gravado.
 */
const s = {
  backdrop: {
    position: 'fixed', inset: 0, background: 'var(--bg-overlay)',
    backdropFilter: 'blur(6px)', display: 'flex', alignItems: 'center',
    justifyContent: 'center', zIndex: 9999,
  },
  modal: {
    background: 'white', border: '1px solid var(--border)',
    borderRadius: 'var(--radius-lg)', width: 480, maxHeight: '90vh',
    overflowY: 'auto', boxShadow: 'var(--shadow-modal)', position: 'relative',
  },
  header: {
    padding: '24px 24px 0', display: 'flex', justifyContent: 'space-between',
    alignItems: 'flex-start',
  },
  modalTitle: { fontSize: 18, fontWeight: 700 },
  subtitle: { fontSize: 12, color: 'var(--text-muted)', marginTop: 4 },
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
  infoLabel: {
    fontSize: 10, color: 'var(--text-muted)', textTransform: 'uppercase',
    letterSpacing: '0.06em', marginBottom: 4,
  },
  infoValue: { fontSize: 13, fontWeight: 600 },
  trechoList: {
    marginBottom: 12, border: '1px solid var(--border)',
    borderRadius: 'var(--radius-sm)', overflow: 'hidden',
  },
  trechoRow: {
    display: 'flex', alignItems: 'center', gap: 9, padding: '10px 12px',
    borderBottom: '1px solid var(--border)', background: 'white',
  },
  trechoDot: (colour) => ({ width: 8, height: 8, borderRadius: '50%', background: colour, flexShrink: 0 }),
  trechoName: { fontSize: 12.5, fontWeight: 600, flex: 1 },
  trechoHeight: { fontSize: 11.5, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' },
  summaryBox: {
    display: 'flex', gap: 10, padding: '10px 12px', background: 'var(--motiva-subtle)',
    fontSize: 11.5, color: 'var(--text-secondary)', borderRadius: 'var(--radius-sm)',
    marginBottom: 20, lineHeight: 1.5,
  },
  divider: { height: 1, background: 'var(--border)', margin: '20px 0' },
  formGroup: { marginBottom: 16 },
  label: {
    display: 'block', fontSize: 12, fontWeight: 600,
    color: 'var(--text-secondary)', marginBottom: 6,
  },
  input: {
    width: '100%', padding: '10px 12px', background: 'var(--bg-secondary)',
    border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)',
    color: 'var(--text-primary)', fontSize: 13, fontFamily: 'inherit', outline: 'none',
  },
  select: {
    width: '100%', padding: '10px 12px', background: 'var(--bg-secondary)',
    border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)',
    color: 'var(--text-primary)', fontSize: 13, fontFamily: 'inherit',
    outline: 'none', cursor: 'pointer',
  },
  textarea: {
    width: '100%', padding: '10px 12px', background: 'var(--bg-secondary)',
    border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)',
    color: 'var(--text-primary)', fontSize: 13, fontFamily: 'inherit',
    outline: 'none', resize: 'vertical', minHeight: 72,
  },
  error: {
    background: 'rgba(220,38,38,0.08)', color: '#b91c1c', fontSize: 12.5,
    padding: '9px 12px', borderRadius: 'var(--radius-sm)', marginBottom: 16,
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

export default function NewOrderModal({ sessionId, segments, teams = [], onCreated, onClose }) {
  const { addToast } = useToast()
  const [form, setForm] = useState({ priority: 'media', teamId: '', scheduledFor: '', notes: '' })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [created, setCreated] = useState(null)

  const combined = segments.length > 1
  // Um trecho sem altura avaliada entra como 0, nunca como 1: altura desconhecida não pode virar
  // prioridade baixa. É a mesma regra do modal da demonstração.
  const worst = Math.max(0, ...segments.map(segment => vegetationLevel(segment.measurementLevel)))
  const tallest = Math.max(0, ...segments.map(segment => segment.measurementExtent95P95M ?? 0))

  async function submit() {
    setBusy(true)
    setError('')
    try {
      const order = await serviceOrders.open({
        priority: form.priority,
        teamId: form.teamId || null,
        scheduledFor: form.scheduledFor || null,
        notes: form.notes || null,
        targets: segments.map(segment => ({ sessionId, segmentIndex: segment.segmentIndex })),
      })
      setCreated(order)
      addToast({
        type: 'success',
        message: combined
          ? `${order.reference} criada — ${segments.length} trechos numa OS só.`
          : `${order.reference} criada com sucesso.`,
      })
      onCreated?.(order)
      setTimeout(() => onClose(), 1600)
    } catch (failure) {
      setBusy(false)
      setError(failure.message || 'Não foi possível abrir a ordem.')
    }
  }

  return (
    <motion.div
      style={s.backdrop} onClick={(event) => event.target === event.currentTarget && onClose()}
      initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
    >
      <motion.div
        style={s.modal}
        initial={{ opacity: 0, y: 24, scale: 0.98 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        exit={{ opacity: 0, y: 16, scale: 0.98 }}
        transition={{ duration: 0.25, ease: [0.16, 1, 0.3, 1] }}
      >
        <AnimatePresence>
          {created && (
            <motion.div style={s.success} initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
              <motion.div style={s.successIcon}
                initial={{ scale: 0.5, opacity: 0 }} animate={{ scale: 1, opacity: 1 }}
                transition={{ type: 'spring', stiffness: 400, damping: 20 }}>
                <Check size={28} />
              </motion.div>
              <div style={{ fontSize: 16, fontWeight: 700 }}>
                {combined ? 'OS combinada criada' : 'Ordem criada'}
              </div>
              <div style={{ fontSize: 13, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
                {created.reference}
              </div>
            </motion.div>
          )}
        </AnimatePresence>

        <div style={s.header}>
          <div>
            <div style={s.modalTitle}>{combined ? 'Nova OS combinada' : 'Nova ordem de serviço'}</div>
            <div style={s.subtitle}>A referência é emitida pela API ao salvar.</div>
          </div>
          <button style={s.closeBtn} onClick={onClose}><X size={16} /></button>
        </div>

        <div style={s.body}>
          <div style={s.infoGrid}>
            <div style={s.infoBox}>
              <div style={s.infoLabel}>Trechos</div>
              <div style={s.infoValue}>{segments.length}</div>
            </div>
            <div style={s.infoBox}>
              <div style={s.infoLabel}>Nível</div>
              <div style={{ ...s.infoValue, color: LEVELS[worst].color }}>{LEVELS[worst].label}</div>
            </div>
            <div style={s.infoBox}>
              <div style={s.infoLabel}>Maior altura p95</div>
              <div style={{ ...s.infoValue, fontFamily: 'var(--font-mono)' }}>
                {tallest > 0 ? `${(tallest * 100).toFixed(0)} cm` : '—'}
              </div>
            </div>
            <div style={s.infoBox}>
              <div style={s.infoLabel}>Equipamento</div>
              <div style={{ ...s.infoValue, fontSize: 11.5 }}>derivado pela API</div>
            </div>
          </div>

          <div style={s.trechoList}>
            {segments.map(segment => {
              const level = vegetationLevel(segment.measurementLevel)
              return (
                <div key={segment.segmentIndex} style={s.trechoRow}>
                  <span style={s.trechoDot(LEVELS[level].color)} />
                  <span style={s.trechoName}>Trecho {segment.segmentIndex}</span>
                  <span style={s.trechoHeight}>
                    {segment.measurementExtent95P95M != null
                      ? `${(segment.measurementExtent95P95M * 100).toFixed(0)} cm`
                      : 'sem altura'}
                  </span>
                </div>
              )
            })}
          </div>

          <div style={s.summaryBox}>
            A área da ordem é estimada a partir da faixa de cinco metros que a grade mediu. Não há
            geometria de parcela neste sistema, e nenhuma leitura foi conferida com fita métrica.
          </div>

          <div style={s.divider} />

          {error && <div style={s.error}>{error}</div>}

          <div style={s.formGroup}>
            <label style={s.label}>Prioridade</label>
            <select style={s.select} value={form.priority}
              onChange={event => setForm(f => ({ ...f, priority: event.target.value }))}>
              {Object.entries(PRIORITY_MAP).map(([key, meta]) => (
                <option key={key} value={key}>{meta.label}</option>
              ))}
            </select>
          </div>

          <div style={s.formGroup}>
            <label style={s.label}>Equipe</label>
            <select style={s.select} value={form.teamId}
              onChange={event => setForm(f => ({ ...f, teamId: event.target.value }))}>
              <option value="">Sem equipe atribuída</option>
              {teams.map(team => (
                <option key={team.teamId} value={team.teamId}>{team.name}</option>
              ))}
            </select>
          </div>

          <div style={s.formGroup}>
            <label style={s.label}>Data prevista</label>
            <input type="date" style={s.input} value={form.scheduledFor}
              onChange={event => setForm(f => ({ ...f, scheduledFor: event.target.value }))} />
          </div>

          <div style={s.formGroup}>
            <label style={s.label}>Observações</label>
            <textarea style={s.textarea} placeholder="Acesso, restrições, o que a equipe precisa saber…"
              value={form.notes}
              onChange={event => setForm(f => ({ ...f, notes: event.target.value }))} />
          </div>
        </div>

        <div style={s.footer}>
          <button style={{ ...s.btnPrimary, opacity: busy ? 0.65 : 1 }} disabled={busy} onClick={submit}>
            {busy ? 'Abrindo…' : combined ? `Abrir OS com ${segments.length} trechos` : 'Abrir ordem'}
          </button>
          <button style={s.btnSecondary} onClick={onClose}>Cancelar</button>
        </div>
      </motion.div>
    </motion.div>
  )
}
