import { useState, useMemo } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { RotateCw, Trash2, ClipboardList, Layers, Sprout, History } from 'lucide-react'
import { NavBar } from '@greenv/web-core'
import { Badge } from '@greenv/web-core'
import { OrderHistoryModal } from '@greenv/web-core'
import { STATUS_MAP, PRIORITY_MAP } from '@greenv/web-core'
import { useAuth } from '@greenv/web-core'
import { useToast } from '@greenv/web-core'

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
    borderTop: `3px solid ${accent}`, boxShadow: 'var(--shadow-card)',
  }),
  statNumber: { fontSize: 28, fontWeight: 700, fontFamily: 'var(--font-mono)' },
  statLabel: { fontSize: 11, color: 'var(--text-muted)', marginTop: 2, textTransform: 'uppercase' },
  table: {
    width: '100%', background: 'white', borderRadius: 'var(--radius-md)',
    border: '1px solid var(--border)', borderCollapse: 'separate',
    borderSpacing: 0, overflow: 'hidden', boxShadow: 'var(--shadow-card)',
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
  trechoBadge: {
    display: 'inline-flex', alignItems: 'center', gap: 4, marginLeft: 6,
    fontSize: 10.5, fontWeight: 600, color: 'var(--motiva)', background: 'var(--motiva-subtle)',
    padding: '2px 7px', borderRadius: 20,
  },
  actionBtn: {
    padding: '6px 10px', background: 'var(--bg-secondary)',
    border: '1px solid var(--border)', borderRadius: 6,
    cursor: 'pointer', fontFamily: 'inherit', display: 'inline-flex',
    color: 'var(--text-secondary)', marginRight: 6,
  },
  actionBtnDanger: {
    padding: '6px 10px', background: 'rgba(220,38,38,0.06)',
    border: '1px solid rgba(220,38,38,0.2)', borderRadius: 6,
    cursor: 'pointer', fontFamily: 'inherit', display: 'inline-flex', color: '#dc2626',
  },
  empty: {
    textAlign: 'center', padding: '60px 20px',
    color: 'var(--text-muted)', fontSize: 14,
  },
  leafPop: {
    position: 'absolute', left: 74, top: 10, color: '#16a34a',
    display: 'inline-flex', pointerEvents: 'none',
  },
}

export default function OrdersPage({ orders, onUpdateOrder, onDeleteOrder, onRestoreOrder }) {
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

  const { user } = useAuth()
  const { addToast } = useToast()
  const [justCompleted, setJustCompleted] = useState(null)
  const [historyOrder, setHistoryOrder] = useState(null)

  const handleDelete = (order) => {
    onDeleteOrder(order.id)
    addToast({
      type: 'danger',
      message: `${order.id} excluída.`,
      actionLabel: 'Desfazer',
      onAction: () => onRestoreOrder?.(order),
    })
  }

  const cycleStatus = (order) => {
    const flow = ['pendente', 'em_andamento', 'concluida']
    const idx = flow.indexOf(order.status)
    const next = flow[(idx + 1) % flow.length]
    const entry = { status: next, at: new Date().toISOString(), by: user?.name || 'Sistema' }
    onUpdateOrder(order.id, { status: next, history: [...(order.history || []), entry] })
    if (next === 'concluida') {
      setJustCompleted(order.id)
      setTimeout(() => setJustCompleted(id => (id === order.id ? null : id)), 900)
    }
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

        <div style={s.statsRow}>
          <div style={s.statCard('var(--motiva)')}>
            <div style={{ ...s.statNumber, color: 'var(--motiva)' }}>{counts.total}</div>
            <div style={s.statLabel}>Total</div>
          </div>
          <div style={s.statCard('#ca8a04')}>
            <div style={{ ...s.statNumber, color: '#ca8a04' }}>{counts.pendente}</div>
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

        {orders.length === 0 ? (
          <div style={{ ...s.table, ...s.empty }}>
            <ClipboardList size={36} style={{ opacity: 0.4, marginBottom: 10 }} />
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
              <AnimatePresence initial={false}>
                {filtered.map((order, i) => {
                  const st = STATUS_MAP[order.status] || STATUS_MAP.pendente
                  const pr = PRIORITY_MAP[order.priority] || PRIORITY_MAP.media
                  return (
                    <motion.tr
                      key={order.id}
                      initial={{ opacity: 0 }}
                      animate={{ opacity: 1 }}
                      exit={{ opacity: 0 }}
                      transition={{ duration: 0.2, delay: Math.min(i * 0.02, 0.3) }}
                      onMouseOver={e => e.currentTarget.style.background = 'var(--bg-secondary)'}
                      onMouseOut={e => e.currentTarget.style.background = 'white'}
                    >
                      <td style={{ ...s.td, fontFamily: 'var(--font-mono)', fontSize: 11, fontWeight: 600 }}>
                        {order.id}
                      </td>
                      <td style={{ ...s.td, color: 'var(--text-secondary)' }}>
                        {new Date(order.createdAt).toLocaleDateString('pt-BR')}
                      </td>
                      <td style={s.td}>
                        <Badge color={pr.color}>{pr.label}</Badge>
                      </td>
                      <td style={{ ...s.td, position: 'relative' }}>
                        <Badge color={st.color}>{st.label}</Badge>
                        {justCompleted === order.id && (
                          <span className="leaf-pop" style={s.leafPop}><Sprout size={13} /></span>
                        )}
                      </td>
                      <td style={{ ...s.td, fontSize: 12 }}>
                        {order.equipment}
                        {order.trechoCount > 1 && (
                          <span style={s.trechoBadge}><Layers size={10} /> {order.trechoCount} trechos</span>
                        )}
                      </td>
                      <td style={{ ...s.td, fontFamily: 'var(--font-mono)', fontSize: 12 }}>
                        {order.area}
                      </td>
                      <td style={{ ...s.td, fontSize: 12, color: order.team ? 'var(--text-primary)' : 'var(--text-muted)' }}>
                        {order.team || '—'}
                      </td>
                      <td style={s.td}>
                        <button style={s.actionBtn} onClick={() => setHistoryOrder(order)} title="Ver histórico">
                          <History size={13} />
                        </button>
                        <button style={s.actionBtn} onClick={() => cycleStatus(order)} title="Avançar status">
                          <RotateCw size={13} />
                        </button>
                        <button style={s.actionBtnDanger} onClick={() => handleDelete(order)} title="Excluir">
                          <Trash2 size={13} />
                        </button>
                      </td>
                    </motion.tr>
                  )
                })}
              </AnimatePresence>
            </tbody>
          </table>
        )}

        {filtered.length === 0 && orders.length > 0 && (
          <div style={{ textAlign: 'center', padding: 40, color: 'var(--text-muted)' }}>
            Nenhuma OS encontrada com esses filtros.
          </div>
        )}
      </div>

      <AnimatePresence>
        {historyOrder && (
          <OrderHistoryModal order={historyOrder} onClose={() => setHistoryOrder(null)} />
        )}
      </AnimatePresence>
    </div>
  )
}
