import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Cell, ResponsiveContainer } from 'recharts'

export default function TeamBarChart({ data, onBarClick }) {
  return (
    <ResponsiveContainer width="100%" height={200}>
      <BarChart data={data} layout="vertical" margin={{ top: 4, right: 16, left: 0, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" horizontal={false} />
        <XAxis type="number" tick={{ fontSize: 11, fill: 'var(--text-muted)' }} axisLine={false} tickLine={false} allowDecimals={false} />
        <YAxis type="category" dataKey="short" width={92} tick={{ fontSize: 11.5, fill: 'var(--text-secondary)' }} axisLine={false} tickLine={false} />
        <Tooltip
          cursor={{ fill: 'var(--bg-secondary)' }}
          formatter={(v) => [`${v} OS`, 'Concluídas']}
        />
        <Bar
          dataKey="completed" radius={[0, 6, 6, 0]} barSize={16}
          onClick={(d) => onBarClick?.(d.id)} cursor={onBarClick ? 'pointer' : 'default'}
        >
          {data.map((d, i) => <Cell key={i} fill={d.color} />)}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  )
}
