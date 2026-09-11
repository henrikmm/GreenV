import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from 'recharts'
import { LEVELS } from '../../utils/classification'

export default function LevelDonut({ counts, total }) {
  const data = Object.entries(counts).map(([level, value]) => ({
    name: LEVELS[level].desc, value, color: LEVELS[level].color,
  }))

  return (
    <div style={{ position: 'relative' }}>
      <ResponsiveContainer width="100%" height={200}>
        <PieChart>
          <Pie data={data} dataKey="value" nameKey="name" innerRadius={62} outerRadius={86}
            paddingAngle={3} stroke="none">
            {data.map((d, i) => <Cell key={i} fill={d.color} />)}
          </Pie>
          <Tooltip formatter={(v, n) => [`${v} trechos`, n]} />
        </PieChart>
      </ResponsiveContainer>
      <div style={{
        position: 'absolute', top: '50%', left: '50%', transform: 'translate(-50%,-50%)',
        textAlign: 'center', pointerEvents: 'none',
      }}>
        <div style={{ fontSize: 24, fontWeight: 700, fontFamily: 'var(--font-mono)' }}>{total}</div>
        <div style={{ fontSize: 10, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>trechos</div>
      </div>
    </div>
  )
}
