export default function Badge({ color, bg, children }) {
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 5,
      padding: '3px 10px', borderRadius: 20, fontSize: 11, fontWeight: 600,
      color, background: bg ?? `${color}18`,
    }}>
      {children}
    </span>
  )
}
