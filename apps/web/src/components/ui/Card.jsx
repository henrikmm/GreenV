import { motion } from 'framer-motion'

const base = {
  background: 'var(--bg-card)', border: '1px solid var(--border)',
  borderRadius: 'var(--radius-md)', boxShadow: 'var(--shadow-card)',
  padding: 18,
}

export default function Card({ children, style, delay = 0, ...rest }) {
  return (
    <motion.div
      style={{ ...base, ...style }}
      initial={{ opacity: 0, y: 14 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35, delay, ease: [0.16, 1, 0.3, 1] }}
      {...rest}
    >
      {children}
    </motion.div>
  )
}
