import { useState, useMemo } from 'react'
import NavBar from '../components/NavBar'
import { LEVELS } from '../utils/classification'

const STATUS_MAP = {
  pendente: { label: 'Pendente', color: '#ca0b04', bg: 'rgba(202,138,4,0.12)' },
  em_andamento: { label: 'Em Andamento', color: '#3b82f6', bg: 'rgba(59,130,246,0.12)' },
  concluida: { label: 'Concluída', color: '#16a34a', bg: 'rgba(22,163,74,0.12)' },
  cancelada: { label: 'Cancelada', color: '#9d9db0', bg: 'rgba(157,157,176,0.12)' },
}

const PRIORITY_MAP = {
  baixa: { label: 'Baixa', color: '#16a34a' },
  media: { label: 'Média', color: '#ca8a04' },
  alta: { label: 'Alta', color: '#dc2626' },
  urgente: { label: 'Urgente', color: '#dc2626' },
}

const s = {
  page: {
    display: 'flex', flexDirection: 'column', height: '100vh',
    background: 'var(--bg-secondary)', overflow: 'hidden',
  },
  content: {
    flex: 1, padding: '24px 32px', overflowY: 'auto',
  },
  header: {
    display: 'flex', justifyContent: 'space-between', alignItems: 'center',
    marginBottom: 20,
  },
  title: { fontSize: 22, fontWeight: 700, color: 'var(--text-primary)' },
  subtitle: { fontSize: 13, color: 'var(--text-secondary)', marginTop: 2 },
  filtersRow: {
    display: 'flex', gap: 10, marginBottom: 20, flexWrap: 'wrap', alignItems: 'center',
  },
  filterSelect: {
    padding: '8px 12px', background: 'white', border: '1px solid var(--border)',
    borderRadius: 'var(--radius-sm)', fontSize: 13, fontFamily: 'inherit',
    color: 'var(--text-primary)', outline: 'none', cursor: 'pointer',
    appearance: 'none', minWidth: 150,
  },
  searchInput: {
    padding: '8px 12px', background: 'white', border: '1px solid var(--border)',
    borderRadius: 'var(--radius-sm)', fontSize: 13, fontFamily: 'inherit',
    color: 'var(--text-primary)', outline: 'none', flex: 1, minWidth: 200,
  },
  statsRow: {
    display: 'flex', gap: 12, marginBottom: 24,
  },
  statCard: (accent) => ({
    flex: 1, padding: '16px 18px', background: 'white',
    borderRadius: 'var(--radius-md)', border: '1px solid var(--border)',
    borderTop: `3px solid ${accent}`,
  }),
  statNumber: { fontSize: 28, fontWeight: 700, fontFamily: 'var(--font-mono)' },
  statLabel: { fontSize: 11, color: 'var(--text-muted)', marginTop: 2, textTransform: 'uppercase' },
  table: {
    width: '100%', background: 'white', borderRadius: 'var(--radius-md)',
    border: '1px solid var(--border)', borderCollapse: 'separate',
    borderSpacing: 0, overflow: 'hidden',
  },
  th: {
    padding: '12px 16px', textAlign: 'left', fontSize: 10, fontWeight: 700,
    color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em',
    background: 'var(--bg-secondary)', borderBottom: '1px solid var(--border)',
  },
  td: {
    padding: '14px 16px', fontSize: 13, borderBottom: '1px solid var(--border)',
    verticalAlign: 'middle',
  },
  badge: (color, bg) => ({
    display: 'inline-block', padding: '3px 10px', borderRadius: 20,
    fontSize: 11, fontWeight: 600, color, background: bg,
  }),
  actionBtn: {
    padding: '6px 12px', background: 'var(--bg-secondary)',
    border: '1px solid var(--border)', borderRadius: 6,
    fontSize: 12, cursor: 'pointer', fontFamily: 'inherit',
    color: 'var(--text-secondary)', marginRight: 6,
  },
  actionBtnDanger: {
    padding: '6px 12px', background: 'rgba(220,38,38,0.06)',
    border: '1px solid rgba(220,38,38,0.2)', borderRadius: 6,
    fontSize: 12, cursor: 'pointer', fontFamily: 'inherit',
    color: '#dc2626',
  },
  empty: {
    textAlign: 'center', padding: '60px 20px',
    color: 'var(--text-muted)', fontSize: 14,
  },
  emptyIcon: {
    fontSize: 40, marginBottom: 12, opacity: 0.4,
  },
}

export default function OrdersPage({ orders, onUpdateOrder, onDeleteOrder }) {
  const [filterStatus, setFilterStatus] = useState('all')
  const [filterPriority, setFilterPriority] = useState('all')
  const [search, setSearch] = useState('')

  const filtered = useMemo(() => {
    return orders.filter(o => {
      if (filterStatus !== 'all' && o.status !== filterStatus) return false
      if (filterPriority !== 'all' && o.priority !== filterPriority) return false
      if (search) {
        const q = search.toLowerCase()
        return o.id.toLowerCase().includes(q) ||
          o.equipment.toLowerCase().includes(q) ||
          (o.team || '').toLowerCase().includes(q)
      }
      return true
    })
  }, [orders, filterStatus, filterPriority, search])

  const counts = useMemo(() => {
    const c = { pendente: 0, em_andamento: 0, concluida: 0, total: orders.length }
    orders.forEach(o => { if (c[o.status] !== undefined) c[o.status]++ })
    return c
  }, [orders])

  const cycleStatus = (order) => {
    const flow = ['pendente', 'em_andamento', 'concluida']
    const idx = flow.indexOf(order.status)
    const next = flow[(idx + 1) % flow.length]
    onUpdateOrder(order.id, { status: next })
  }

  return (
    <div style={s.page}>
      <NavBar currentPage="orders" />

      <div style={s.content}>
        <div style={s.header}>
          <div>
            <div style={s.title}>Ordens de Serviço</div>
            <div style={s.subtitle}>Gestão de roçada — Rodoanel Oeste (SP-021)</div>
          </div>
        </div>

        {/* Stats cards */}
        <div style={s.statsRow}>
          <div style={s.statCard('#5e22f3')}>
            <div style={{ ...s.statNumber, color: '#5e22f3' }}>{counts.total}</div>
            <div style={s.statLabel}>Total</div>
          </div>
          <div style={s.statCard('#ca1b04')}>
            <div style={{ ...s.statNumber, color: '#df1f11' }}>{counts.pendente}</div>
            <div style={s.statLabel}>Pendentes</div>
          </div>
          <div style={s.statCard('#3b82f6')}>
            <div style={{ ...s.statNumber, color: '#3b82f6' }}>{counts.em_andamento}</div>
            <div style={s.statLabel}>Em andamento</div>
          </div>
          <div style={s.statCard('#16a34a')}>
            <div style={{ ...s.statNumber, color: '#16a34a' }}>{counts.concluida}</div>
            <div style={s.statLabel}>Concluídas</div>
          </div>
        </div>

        {/* Filters */}
        <div style={s.filtersRow}>
          <select style={s.filterSelect} value={filterStatus}
            onChange={e => setFilterStatus(e.target.value)}>
            <option value="all">Todos os status</option>
            {Object.entries(STATUS_MAP).map(([k, v]) => (
              <option key={k} value={k}>{v.label}</option>
            ))}
          </select>
          <select style={s.filterSelect} value={filterPriority}
            onChange={e => setFilterPriority(e.target.value)}>
            <option value="all">Todas as prioridades</option>
            {Object.entries(PRIORITY_MAP).map(([k, v]) => (
              <option key={k} value={k}>{v.label}</option>
            ))}
          </select>
          <input style={s.searchInput} placeholder="Buscar por ID, equipamento ou equipe..."
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>

        {/* Table */}
        {orders.length === 0 ? (
          <div style={{ ...s.table, ...s.empty }}>
            <div style={s.emptyIcon}>📋</div>
            <div>Nenhuma ordem de serviço criada.</div>
            <div style={{ fontSize: 12, marginTop: 4, color: 'var(--text-muted)' }}>
              Vá ao mapa e clique em um polígono para criar uma OS.
            </div>
          </div>
        ) : (
          <table style={s.table}>
            <thead>
              <tr>
                <th style={s.th}>ID</th>
                <th style={s.th}>Data</th>
                <th style={s.th}>Prioridade</th>
                <th style={s.th}>Status</th>
                <th style={s.th}>Equipamento</th>
                <th style={s.th}>Área</th>
                <th style={s.th}>Equipe</th>
                <th style={s.th}>Ações</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(order => {
                const st = STATUS_MAP[order.status] || STATUS_MAP.pendente
                const pr = PRIORITY_MAP[order.priority] || PRIORITY_MAP.media
                return (
                  <tr key={order.id} style={{ transition: 'background 0.1s' }}
                    onMouseOver={e => e.currentTarget.style.background = 'var(--bg-secondary)'}
                    onMouseOut={e => e.currentTarget.style.background = 'white'}>
                    <td style={{ ...s.td, fontFamily: 'var(--font-mono)', fontSize: 11, fontWeight: 600 }}>
                      {order.id}
                    </td>
                    <td style={{ ...s.td, color: 'var(--text-secondary)' }}>
                      {new Date(order.createdAt).toLocaleDateString('pt-BR')}
                    </td>
                    <td style={s.td}>
                      <span style={{ color: pr.color, fontWeight: 600, fontSize: 12 }}>
                        ● {pr.label}
                      </span>
                    </td>
                    <td style={s.td}>
                      <span style={s.badge(st.color, st.bg)}>{st.label}</span>
                    </td>
                    <td style={{ ...s.td, fontSize: 12 }}>{order.equipment}</td>
                    <td style={{ ...s.td, fontFamily: 'var(--font-mono)', fontSize: 12 }}>
                      {order.area}
                    </td>
                    <td style={{ ...s.td, fontSize: 12, color: order.team ? 'var(--text-primary)' : 'var(--text-muted)' }}>
                      {order.team || '—'}
                    </td>
                    <td style={s.td}>
                      <button style={s.actionBtn} onClick={() => cycleStatus(order)}
                        title="Avançar status">
                        ↻
                      </button>
                      <button style={s.actionBtnDanger} onClick={() => onDeleteOrder(order.id)}
                        title="Excluir">
                        ✕
                      </button>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}

        {filtered.length === 0 && orders.length > 0 && (
          <div style={{ textAlign: 'center', padding: 40, color: 'var(--text-muted)' }}>
            Nenhuma OS encontrada com esses filtros.
          </div>
        )}
      </div>
    </div>
  )
}
