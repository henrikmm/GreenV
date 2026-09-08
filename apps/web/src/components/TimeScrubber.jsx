import { motion } from 'framer-motion'
import { CalendarClock } from 'lucide-react'

const s = {
  wrap: { padding: '2px 4px' },
  header: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 },
  badge: {
    display: 'inline-flex', alignItems: 'center', gap: 6, padding: '4px 10px',
    borderRadius: 20, background: 'var(--motiva-subtle)', color: 'var(--motiva)',
    fontSize: 12, fontWeight: 700, fontFamily: 'var(--font-mono)',
  },
  liveTag: {
    fontSize: 10.5, fontWeight: 700, color: 'var(--accent)', background: 'var(--accent-bg)',
    padding: '2px 8px', borderRadius: 20, textTransform: 'uppercase', letterSpacing: '0.04em',
  },
  track: { position: 'relative', height: 28, display: 'flex', alignItems: 'center' },
  input: {
    width: '100%', margin: 0, accentColor: 'var(--motiva)', cursor: 'pointer', height: 4,
  },
  ticks: { display: 'flex', justifyContent: 'space-between', marginTop: 2 },
  tick: { fontSize: 9.5, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' },
}

export default function TimeScrubber({ weeks, value, onChange }) {
  const isLive = value === weeks.length - 1
  const current = weeks[value]

  return (
    <div style={s.wrap}>
      <div style={s.header}>
        <span style={s.badge}><CalendarClock size={13} /> Semana de {current.week}</span>
        {isLive ? (
          <span style={s.liveTag}>Ao vivo</span>
        ) : (
          <motion.span
            initial={{ opacity: 0 }} animate={{ opacity: 1 }}
            style={{ fontSize: 11, color: 'var(--text-muted)' }}
          >
            {weeks.length - 1 - value} {weeks.length - 1 - value === 1 ? 'semana atrás' : 'semanas atrás'}
          </motion.span>
        )}
      </div>

      <div style={s.track}>
        <input
          type="range" min={0} max={weeks.length - 1} step={1} value={value}
          onChange={e => onChange(Number(e.target.value))}
          style={s.input}
        />
      </div>

      <div style={s.ticks}>
        <span style={s.tick}>{weeks[0].week}</span>
        <span style={s.tick}>{weeks[Math.floor(weeks.length / 2)].week}</span>
        <span style={s.tick}>{weeks[weeks.length - 1].week} (hoje)</span>
      </div>
    </div>
  )
}
