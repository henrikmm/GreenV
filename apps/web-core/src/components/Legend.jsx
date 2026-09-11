import { motion } from 'framer-motion'
import { LEVELS } from '../utils/classification'

export default function Legend({ offsetRight = 16 }) {
  return (
    <motion.div
      style={{
        position: 'absolute', bottom: 24, right: offsetRight, zIndex: 800,
        background: 'white', border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)', padding: '12px 16px',
        boxShadow: 'var(--shadow-card-lg)', minWidth: 170,
      }}
      animate={{ right: offsetRight }}
      transition={{ type: 'spring', stiffness: 380, damping: 38 }}
    >
      <div style={{
        fontSize: 10, fontWeight: 700, color: 'var(--text-muted)',
        textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 8,
      }}>
        Altura da Vegetação
      </div>
      {Object.entries(LEVELS).map(([k, v]) => (
        <div key={k} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 5 }}>
          <div style={{
            width: 12, height: 12, borderRadius: 3,
            background: v.bg, border: `2px solid ${v.color}`,
          }} />
          <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{v.desc}</span>
        </div>
      ))}
    </motion.div>
  )
}
