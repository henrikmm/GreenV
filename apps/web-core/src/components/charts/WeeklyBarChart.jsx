import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'

export default function WeeklyBarChart({ data, color = 'var(--motiva)', label = 'OS' }) {
  return (
    <ResponsiveContainer width="100%" height={200}>
      <BarChart data={data} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
        <XAxis dataKey="week" tick={{ fontSize: 11, fill: 'var(--text-muted)' }} axisLine={false} tickLine={false} />
        <YAxis tick={{ fontSize: 11, fill: 'var(--text-muted)' }} axisLine={false} tickLine={false} width={28} allowDecimals={false} />
        <Tooltip formatter={(v) => [`${v} ${label}`, '']} labelFormatter={(l) => `Semana de ${l}`} />
        <Bar dataKey="count" fill={color} radius={[5, 5, 0, 0]} barSize={20} />
      </BarChart>
    </ResponsiveContainer>
  )
}
