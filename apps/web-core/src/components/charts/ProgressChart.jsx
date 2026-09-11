import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'

function CustomTooltip({ active, payload, label }) {
  if (!active || !payload?.length) return null
  return (
    <div style={{
      background: 'white', border: '1px solid var(--border)', borderRadius: 8,
      padding: '8px 12px', boxShadow: 'var(--shadow-card-lg)', fontSize: 12,
    }}>
      <div style={{ fontWeight: 700, marginBottom: 2 }}>Semana de {label}</div>
      <div style={{ color: 'var(--motiva)' }}>{payload[0].value.toFixed(2)} ha tratados</div>
      <div style={{ color: 'var(--text-muted)' }}>{payload[0].payload.osCompleted} OS concluídas</div>
    </div>
  )
}

export default function ProgressChart({ data }) {
  return (
    <ResponsiveContainer width="100%" height={220}>
      <AreaChart data={data} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
        <defs>
          <linearGradient id="areaFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="var(--motiva)" stopOpacity={0.35} />
            <stop offset="100%" stopColor="var(--motiva)" stopOpacity={0.02} />
          </linearGradient>
        </defs>
        <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
        <XAxis dataKey="week" tick={{ fontSize: 11, fill: 'var(--text-muted)' }} axisLine={false} tickLine={false} />
        <YAxis tick={{ fontSize: 11, fill: 'var(--text-muted)' }} axisLine={false} tickLine={false} width={36} />
        <Tooltip content={<CustomTooltip />} />
        <Area type="monotone" dataKey="areaHa" stroke="var(--motiva)" strokeWidth={2.5} fill="url(#areaFill)" />
      </AreaChart>
    </ResponsiveContainer>
  )
}
