import { useCallback, useEffect, useMemo, useState } from 'react'
import { RotateCw, Trash2, ClipboardList, Layers } from 'lucide-react'
import { Badge, STATUS_MAP, PRIORITY_MAP, LEVELS, vegetationLevel, useToast } from '@greenv/web-core'
import { serviceOrders, teams as teamsApi } from '../api/greenv'
import PageShell from '../components/PageShell'

/**
 * Ordens de serviço, contra medições que existem.
 *
 * A diferença em relação à demonstração é o alvo. Lá uma ordem aponta para polígonos do KMZ,
 * porque é o que ela tem; aqui aponta para trechos medidos, que é a única coisa neste sistema que
 * carrega uma altura real e uma posição real. A área vem da faixa de cinco metros que a grade
 * mediu, e é uma estimativa — não existe geometria de parcela em lugar nenhum.
 */
const s = {
  header: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 },
  title: { fontSize: 22, fontWeight: 700, color: 'var(--text-primary)' },
  subtitle: { fontSize: 13, color: 'var(--text-secondary)', marginTop: 2 },
  statsRow: { display: 'flex', gap: 12, marginBottom: 24 },
  statCard: (accent) => ({
    flex: 1, padding: '16px 18px', background: 'white',
    borderRadius: 'var(--radius-md)', border: '1px solid var(--border)',
    borderTop: `3px solid ${accent}`, boxShadow: 'var(--shadow-card)',
  }),
  statNumber: { fontSize: 28, fontWeight: 700, fontFamily: 'var(--font-mono)' },
  statLabel: { fontSize: 11, color: 'var(--text-muted)', marginTop: 2, textTransform: 'uppercase' },
  filtersRow: { display: 'flex', gap: 10, marginBottom: 20, flexWrap: 'wrap', alignItems: 'center' },
  filterSelect: {
    padding: '8px 12px', background: 'white', border: '1px solid var(--border)',
    borderRadius: 'var(--radius-sm)', fontSize: 13, fontFamily: 'inherit',
    color: 'var(--text-primary)', outline: 'none', cursor: 'pointer', minWidth: 150,
  },
  searchInput: {
    padding: '8px 12px', background: 'white', border: '1px solid var(--border)',
    borderRadius: 'var(--radius-sm)', fontSize: 13, fontFamily: 'inherit',
    color: 'var(--text-primary)', outline: 'none', flex: 1, minWidth: 200,
  },
  table: {
    width: '100%', background: 'white', borderRadius: 'var(--radius-md)',
    border: '1px solid var(--border)', borderCollapse: 'separate', borderSpacing: 0,
    overflow: 'hidden', boxShadow: 'var(--shadow-card)',
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
  reference: { fontFamily: 'var(--font-mono)', fontSize: 11.5, fontWeight: 600 },
  trechoBadge: {
    display: 'inline-flex', alignItems: 'center', gap: 4, marginLeft: 6,
    fontSize: 10.5, fontWeight: 600, color: 'var(--motiva)', background: 'var(--motiva-subtle)',
    padding: '2px 7px', borderRadius: 20,
  },
  actionBtn: {
    padding: '6px 10px', background: 'var(--bg-secondary)', border: '1px solid var(--border)',
    borderRadius: 6, cursor: 'pointer', fontFamily: 'inherit', display: 'inline-flex',
    color: 'var(--text-secondary)', marginRight: 6,
  },
  actionBtnDanger: {
    padding: '6px 10px', background: 'rgba(220,38,38,0.06)',
    border: '1px solid rgba(220,38,38,0.2)', borderRadius: 6,
    cursor: 'pointer', fontFamily: 'inherit', display: 'inline-flex', color: '#dc2626',
  },
  empty: { textAlign: 'center', padding: '60px 20px', color: 'var(--text-muted)', fontSize: 14 },
}

/** A ordem em que o botão de avançar move uma ordem. Cancelar é outra coisa, e é o botão ao lado. */
const FLOW = ['pendente', 'em_andamento', 'concluida']

function formatArea(squareMetres) {
  if (squareMetres == null) return '—'
  if (squareMetres >= 10000) return `${(squareMetres / 10000).toFixed(2)} ha`
  return `${Math.round(squareMetres).toLocaleString('pt-BR')} m²`
}

export default function OrdersPage() {
  const { addToast } = useToast()
  const [state, setState] = useState({ loading: true, orders: [], teams: [] })
  const [filterStatus, setFilterStatus] = useState('all')
  const [filterPriority, setFilterPriority] = useState('all')
  const [search, setSearch] = useState('')

  const load = useCallback(() => {
    setState(previous => ({ ...previous, loading: true }))
    return Promise.all([serviceOrders.list({ limit: 200 }), teamsApi.list()])
      .then(([page, teams]) => setState({ loading: false, orders: page.items, teams }))
      .catch(error => setState({ loading: false, orders: [], teams: [], error }))
  }, [])

  useEffect(() => { load() }, [load])

  const { orders, teams, loading, error } = state

  const teamName = useMemo(() => {
    const byId = new Map(teams.map(team => [team.teamId, team.shortName]))
    return (teamId) => (teamId ? byId.get(teamId) ?? 'equipe removida' : '—')
  }, [teams])

  // Os filtros são aplicados aqui e não na API porque a lista inteira já está em memória: são
  // duzentas ordens no máximo, e filtrar no servidor custaria uma ida e volta por tecla digitada.
  const filtered = useMemo(() => orders.filter(order => {
    if (filterStatus !== 'all' && order.status !== filterStatus) return false
    if (filterPriority !== 'all' && order.priority !== filterPriority) return false
    if (!search) return true
    const needle = search.toLowerCase()
    return order.reference.toLowerCase().includes(needle)
      || (order.equipment ?? '').toLowerCase().includes(needle)
      || (order.notes ?? '').toLowerCase().includes(needle)
  }), [orders, filterStatus, filterPriority, search])

  const counts = useMemo(() => {
    const tally = { pendente: 0, em_andamento: 0, concluida: 0, total: orders.length }
    for (const order of orders) {
      if (tally[order.status] !== undefined) tally[order.status] += 1
    }
    return tally
  }, [orders])

  async function advance(order) {
    const next = FLOW[(FLOW.indexOf(order.status) + 1) % FLOW.length]
    try {
      await serviceOrders.amend(order.orderId, { status: next })
      addToast({ type: 'success', message: `${order.reference} → ${STATUS_MAP[next].label}.` })
      await load()
    } catch (failure) {
      addToast({ type: 'danger', message: failure.message })
    }
  }

  async function cancel(order) {
    try {
      await serviceOrders.cancel(order.orderId)
      // Cancelada, não excluída — por isso não há "desfazer": a linha continua lá.
      addToast({ type: 'danger', message: `${order.reference} cancelada.` })
      await load()
    } catch (failure) {
      addToast({ type: 'danger', message: failure.message })
    }
  }

  return (
    <PageShell currentPage="orders">
      <div style={s.header}>
        <div>
          <div style={s.title}>Ordens de Serviço</div>
          <div style={s.subtitle}>Roçada aberta contra trechos que foram medidos.</div>
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
          onChange={event => setFilterStatus(event.target.value)}>
          <option value="all">Todos os status</option>
          {Object.entries(STATUS_MAP).map(([key, meta]) => (
            <option key={key} value={key}>{meta.label}</option>
          ))}
        </select>
        <select style={s.filterSelect} value={filterPriority}
          onChange={event => setFilterPriority(event.target.value)}>
          <option value="all">Todas as prioridades</option>
          {Object.entries(PRIORITY_MAP).map(([key, meta]) => (
            <option key={key} value={key}>{meta.label}</option>
          ))}
        </select>
        <input style={s.searchInput} placeholder="Buscar por referência, equipamento ou observação…"
          value={search} onChange={event => setSearch(event.target.value)} />
      </div>

      {error && <div style={{ ...s.table, ...s.empty, color: LEVELS[3].color }}>{error.message}</div>}

      {!error && (loading || orders.length === 0) ? (
        <div style={{ ...s.table, ...s.empty }}>
          <ClipboardList size={36} style={{ opacity: 0.4, marginBottom: 10 }} />
          <div>{loading ? 'Carregando ordens…' : 'Nenhuma ordem de serviço aberta.'}</div>
          {!loading && (
            <div style={{ fontSize: 12, marginTop: 4, color: 'var(--text-muted)' }}>
              Abra uma sessão, escolha um trecho medido e crie a ordem a partir dele.
            </div>
          )}
        </div>
      ) : !error && (
        <table style={s.table}>
          <thead>
            <tr>
              <th style={s.th}>Referência</th>
              <th style={s.th}>Aberta em</th>
              <th style={s.th}>Prioridade</th>
              <th style={s.th}>Status</th>
              <th style={s.th}>Equipamento</th>
              <th style={s.th}>Área estimada</th>
              <th style={s.th}>Equipe</th>
              <th style={s.th}>Ações</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map(order => {
              const status = STATUS_MAP[order.status] ?? { label: order.status, color: '#9d9db0' }
              const priority = PRIORITY_MAP[order.priority] ?? { label: order.priority, color: '#9d9db0' }
              const level = vegetationLevel(order.vegetationLevel)
              return (
                <tr key={order.orderId}>
                  <td style={{ ...s.td, ...s.reference }}>{order.reference}</td>
                  <td style={s.td}>{new Date(order.createdAt).toLocaleDateString('pt-BR')}</td>
                  <td style={s.td}><Badge color={priority.color}>{priority.label}</Badge></td>
                  <td style={s.td}><Badge color={status.color}>{status.label}</Badge></td>
                  <td style={s.td}>
                    {order.equipment ?? '—'}
                    <span style={s.trechoBadge}>
                      <Layers size={10} />
                      {order.targets.length} {order.targets.length === 1 ? 'trecho' : 'trechos'}
                    </span>
                  </td>
                  <td style={{ ...s.td, fontFamily: 'var(--font-mono)', fontSize: 12 }}>
                    {formatArea(order.areaSquareMetres)}
                    {level > 0 && (
                      <span style={{ color: LEVELS[level].color, marginLeft: 8, fontSize: 11 }}>
                        {LEVELS[level].label}
                      </span>
                    )}
                  </td>
                  <td style={s.td}>{teamName(order.teamId)}</td>
                  <td style={s.td}>
                    <button style={s.actionBtn} title="Avançar status" onClick={() => advance(order)}>
                      <RotateCw size={13} />
                    </button>
                    <button style={s.actionBtnDanger} title="Cancelar" onClick={() => cancel(order)}>
                      <Trash2 size={13} />
                    </button>
                  </td>
                </tr>
              )
            })}
            {filtered.length === 0 && (
              <tr><td style={{ ...s.td, ...s.empty }} colSpan={8}>
                Nenhuma ordem com esses filtros.
              </td></tr>
            )}
          </tbody>
        </table>
      )}
    </PageShell>
  )
}
