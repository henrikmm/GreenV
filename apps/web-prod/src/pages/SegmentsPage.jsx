import { Fragment, useEffect, useMemo, useState } from 'react'
import { AnimatePresence } from 'framer-motion'
import { ClipboardList, Ruler, ChevronRight, ChevronDown } from 'lucide-react'
import { LEVELS, vegetationLevel, Card, dayKey, dayLabel } from '@greenv/web-core'
import { measurements, teams as teamsApi } from '../api/greenv'
import { placeOfSegment } from '../api/place'
import { readingOf } from '../api/reading'
import PageShell from '../components/PageShell'
import NewOrderModal from '../components/NewOrderModal'
import SegmentDetail from '../components/SegmentDetail'

/**
 * Toda leitura, a mais alta primeiro.
 *
 * A visão por sessão esconde exatamente o que importa. Uma volta de carro produz oito trechos, e
 * a sessão inteira recebe um rótulo só — o pior nível, um lugar médio. Se sete estão limpos e um
 * está com três metros de mato, a sessão diz "nível 3" e não diz onde. Aqui a linha é a leitura:
 * cada trecho tem a própria altura, o próprio lugar e a própria qualidade de GPS, e a ordenação
 * padrão é pela altura, porque é assim que se decide para onde a equipe vai primeiro.
 *
 * Uma ordem aberta daqui atravessa sessões sem cerimônia: os dois piores trechos do dia raramente
 * foram gravados na mesma volta, e é justamente combiná-los que economiza deslocamento.
 */
const s = {
  header: { display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 20, gap: 12 },
  title: { fontSize: 22, fontWeight: 700, color: 'var(--text-primary)' },
  subtitle: { fontSize: 13, color: 'var(--text-secondary)', marginTop: 3 },
  orderBtn: (enabled) => ({
    display: 'flex', alignItems: 'center', gap: 6, padding: '10px 16px', fontSize: 13,
    fontWeight: 700, borderRadius: 'var(--radius-sm)', fontFamily: 'inherit', flexShrink: 0,
    cursor: enabled ? 'pointer' : 'not-allowed',
    border: `1.5px solid ${enabled ? 'var(--motiva)' : 'var(--border)'}`,
    background: enabled ? 'var(--motiva)' : 'white',
    color: enabled ? 'white' : 'var(--text-muted)',
  }),
  statsRow: { display: 'flex', gap: 12, marginBottom: 20 },
  statCard: (accent) => ({
    flex: 1, padding: '16px 18px', background: 'white',
    borderRadius: 'var(--radius-md)', border: '1px solid var(--border)',
    borderTop: `3px solid ${accent}`, boxShadow: 'var(--shadow-card)',
  }),
  statNumber: { fontSize: 28, fontWeight: 700, fontFamily: 'var(--font-mono)' },
  statLabel: { fontSize: 11, color: 'var(--text-muted)', marginTop: 2, textTransform: 'uppercase' },
  filtersRow: { display: 'flex', gap: 10, marginBottom: 20, flexWrap: 'wrap', alignItems: 'center' },
  chip: (active, colour) => ({
    display: 'inline-flex', alignItems: 'center', gap: 5, padding: '7px 13px', borderRadius: 20,
    fontSize: 12.5, fontWeight: 600, cursor: 'pointer', fontFamily: 'inherit',
    border: `1.5px solid ${active ? colour : 'var(--border)'}`,
    background: active ? `${colour}15` : 'white',
    color: active ? colour : 'var(--text-secondary)',
  }),
  dot: (colour) => ({ width: 8, height: 8, borderRadius: '50%', background: colour }),
  daySelect: {
    padding: '8px 12px', background: 'white', border: '1px solid var(--border)',
    borderRadius: 'var(--radius-sm)', fontSize: 12.5, fontFamily: 'inherit',
    color: 'var(--text-primary)', outline: 'none', cursor: 'pointer',
  },
  searchInput: {
    padding: '8px 12px', background: 'white', border: '1px solid var(--border)',
    borderRadius: 'var(--radius-sm)', fontSize: 13, fontFamily: 'inherit',
    color: 'var(--text-primary)', outline: 'none', flex: 1, minWidth: 200,
  },
  table: { width: '100%', borderCollapse: 'separate', borderSpacing: 0 },
  th: {
    padding: '11px 14px', textAlign: 'left', fontSize: 10, fontWeight: 700,
    color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em',
    background: 'var(--bg-secondary)', borderBottom: '1px solid var(--border)',
  },
  td: { padding: '13px 14px', fontSize: 12.5, borderBottom: '1px solid var(--border)', verticalAlign: 'middle' },
  rank: { fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--text-muted)', width: 30 },
  subDate: { fontSize: 11, color: 'var(--text-muted)', marginTop: 1 },
  place: { fontSize: 13, fontWeight: 600, color: 'var(--text-primary)' },
  placeDetail: { fontSize: 11, color: 'var(--text-muted)', marginTop: 1 },
  height: { fontFamily: 'var(--font-mono)', fontSize: 14, fontWeight: 700 },
  bar: (fraction, colour) => ({
    height: 4, borderRadius: 2, marginTop: 4, background: colour,
    width: `${Math.max(4, Math.round(fraction * 100))}%`, maxWidth: 120,
  }),
  pill: (reading) => ({
    display: 'inline-flex', padding: '3px 10px', borderRadius: 20, fontSize: 11, fontWeight: 600,
    color: reading.colour, background: reading.background, lineHeight: 1.35,
  }),
  empty: { padding: 40, textAlign: 'center', color: 'var(--text-muted)', fontSize: 13 },
  row: (open) => ({ cursor: 'pointer', background: open ? 'var(--motiva-subtle)' : 'transparent' }),
  // Sem `display: flex` aqui. Uma célula que vira contêiner flex deixa de ser célula: sai da
  // grade de colunas da tabela e se desenha por conta própria, que era a coluna solta e
  // deslocada à direita, com traços que não batiam com as linhas.
  chevron: { color: 'var(--text-muted)', width: 34, textAlign: 'center', lineHeight: 0 },
  chevronIcon: { display: 'inline-block', verticalAlign: 'middle' },
  detailCell: { padding: '0 14px', background: 'var(--bg-secondary)', borderBottom: '1px solid var(--border)' },
}

const keyOf = (segment) => `${segment.sessionId}:${segment.segmentIndex}`

export default function SegmentsPage() {
  const [state, setState] = useState({ loading: true, items: [], teams: [] })
  const [filterLevel, setFilterLevel] = useState(null)
  // O dia da captura, não o da medição: é a saída a campo que a pessoa lembra.
  const [filterDay, setFilterDay] = useState('')
  const [search, setSearch] = useState('')
  const [chosen, setChosen] = useState([])
  const [ordering, setOrdering] = useState(false)
  // Um trecho aberto por vez: dois mapas lado a lado numa tabela competem por atenção e
  // por rede, e a pergunta que a lista responde é sempre sobre uma leitura.
  const [openKey, setOpenKey] = useState(null)

  useEffect(() => {
    let live = true
    Promise.all([measurements.list({ limit: 200 }), teamsApi.list().catch(() => [])])
      .then(([page, teams]) => { if (live) setState({ loading: false, items: page.items, teams }) })
      .catch(error => { if (live) setState({ loading: false, items: [], teams: [], error }) })
    return () => { live = false }
  }, [])

  const { items, teams, loading, error } = state

  // Cada trecho já vem com o próprio lugar resolvido pela API.
  const placeOf = (segment) => placeOfSegment(segment)

  // Os dias em que se saiu a campo, do mais recente para o mais antigo, com quantas leituras
  // cada um rendeu. Sai dos próprios trechos, então não custa uma segunda consulta.
  const days = useMemo(() => {
    const tally = new Map()
    for (const segment of items) {
      const key = dayKey(segment.capturedAt)
      if (!key) continue
      const seen = tally.get(key)
      tally.set(key, { key, label: dayLabel(segment.capturedAt), count: (seen?.count ?? 0) + 1 })
    }
    return [...tally.values()].sort((a, b) => b.key.localeCompare(a.key))
  }, [items])

  // O dia filtra antes de tudo, por isso os cartões e as contagens dos chips saem daqui: um
  // número do mês inteiro sobre uma lista de um dia só seria uma contradição na mesma tela.
  const inDay = useMemo(() => (
    filterDay ? items.filter(segment => dayKey(segment.capturedAt) === filterDay) : items
  ), [items, filterDay])

  // Sem altura vai para o fim, não para o começo: uma leitura que ninguém conseguiu medir não é
  // uma leitura baixa, e ordenar nulo como zero a enterraria junto com a grama aparada.
  const ranked = useMemo(() => [...inDay].sort((a, b) =>
    (b.measurementExtent95P95M ?? -1) - (a.measurementExtent95P95M ?? -1)), [inDay])

  const tallest = ranked[0]?.measurementExtent95P95M ?? 0

  const visible = useMemo(() => ranked.filter(segment => {
    if (filterLevel !== null && vegetationLevel(segment.measurementLevel) !== filterLevel) return false
    if (!search) return true
    const place = placeOf(segment)
    return (place?.label ?? '').toLowerCase().includes(search.toLowerCase())
      || (place?.detail ?? '').toLowerCase().includes(search.toLowerCase())
  }), [ranked, filterLevel, search])

  const counts = useMemo(() => {
    const tally = { 0: 0, 1: 0, 2: 0, 3: 0 }
    for (const segment of inDay) tally[vegetationLevel(segment.measurementLevel)] += 1
    return tally
  }, [inDay])

  const chosenSegments = items.filter(segment => chosen.includes(keyOf(segment)))

  return (
    <PageShell currentPage="segments">
      <div style={s.header}>
        <div>
          <div style={s.title}>Trechos medidos</div>
          <div style={s.subtitle}>
            Toda leitura, a mais alta primeiro. Uma sessão mistura trecho limpo com trecho
            crítico; aqui cada um responde por si.
          </div>
        </div>
        <button style={s.orderBtn(chosenSegments.length > 0)}
          disabled={chosenSegments.length === 0}
          onClick={() => setOrdering(true)}>
          <ClipboardList size={14} />
          {chosenSegments.length > 1 ? `Criar OS (${chosenSegments.length})` : 'Criar OS'}
        </button>
      </div>

      <div style={s.statsRow}>
        <div style={s.statCard(LEVELS[3].color)}>
          <div style={{ ...s.statNumber, color: LEVELS[3].color }}>{counts[3]}</div>
          <div style={s.statLabel}>Acima de 30 cm</div>
        </div>
        <div style={s.statCard(LEVELS[2].color)}>
          <div style={{ ...s.statNumber, color: LEVELS[2].color }}>{counts[2]}</div>
          <div style={s.statLabel}>Entre 10 e 30 cm</div>
        </div>
        <div style={s.statCard(LEVELS[1].color)}>
          <div style={{ ...s.statNumber, color: LEVELS[1].color }}>{counts[1]}</div>
          <div style={s.statLabel}>Abaixo de 10 cm</div>
        </div>
        <div style={s.statCard('var(--motiva)')}>
          <div style={{ ...s.statNumber, color: 'var(--motiva)' }}>
            {tallest > 0 ? `${(tallest * 100).toFixed(0)}` : '—'}
          </div>
          <div style={s.statLabel}>Maior altura, cm</div>
        </div>
      </div>

      <div style={s.filtersRow}>
        <button style={s.chip(filterLevel === null, 'var(--motiva)')} onClick={() => setFilterLevel(null)}>
          Todos
        </button>
        {[3, 2, 1, 0].map(level => (
          <button key={level} style={s.chip(filterLevel === level, LEVELS[level].color)}
            onClick={() => setFilterLevel(filterLevel === level ? null : level)}>
            <span style={s.dot(LEVELS[level].color)} />{LEVELS[level].desc}
          </button>
        ))}
        {days.length > 1 && (
          <select style={s.daySelect} value={filterDay}
            onChange={event => setFilterDay(event.target.value)}>
            <option value="">Todos os dias</option>
            {days.map(day => (
              <option key={day.key} value={day.key}>{day.label} ({day.count})</option>
            ))}
          </select>
        )}
        <input style={s.searchInput} placeholder="Buscar por rua ou bairro…"
          value={search} onChange={event => setSearch(event.target.value)} />
      </div>

      {error && <Card><div style={{ ...s.empty, color: LEVELS[3].color }}>{error.message}</div></Card>}

      {!error && (
        <Card style={{ padding: 0, overflow: 'hidden' }}>
          {loading && <div style={s.empty}>Carregando leituras…</div>}
          {!loading && visible.length === 0 && (
            <div style={s.empty}>
              <Ruler size={34} style={{ opacity: 0.4, marginBottom: 10 }} />
              <div>Nenhuma leitura com esse filtro.</div>
            </div>
          )}
          {!loading && visible.length > 0 && (
            <table style={s.table}>
              <thead>
                <tr>
                  <th style={s.th} /><th style={s.th}>#</th><th style={s.th}>Onde</th>
                  <th style={s.th}>Altura p95</th><th style={s.th}>Nível</th>
                  <th style={s.th}>Células</th><th style={s.th}>GPS</th>
                  <th style={s.th}>Capturado em</th><th style={s.th} />
                </tr>
              </thead>
              <tbody>
                {visible.map((segment, position) => {
                  const level = vegetationLevel(segment.measurementLevel)
                  const reading = readingOf(segment)
                  const place = placeOf(segment)
                  const height = segment.measurementExtent95P95M
                  return (
                    <Fragment key={keyOf(segment)}>
                    <tr style={s.row(openKey === keyOf(segment))}
                      onClick={() => setOpenKey(openKey === keyOf(segment) ? null : keyOf(segment))}>
                      <td style={s.td}>
                        <input type="checkbox" checked={chosen.includes(keyOf(segment))}
                          onClick={(event) => event.stopPropagation()}
                          onChange={() => setChosen(previous => previous.includes(keyOf(segment))
                            ? previous.filter(key => key !== keyOf(segment))
                            : [...previous, keyOf(segment)])} />
                      </td>
                      <td style={{ ...s.td, ...s.rank }}>{position + 1}</td>
                      <td style={s.td}>
                        <div style={s.place}>
                          {place?.label ?? <span style={{ color: 'var(--text-muted)' }}>sem posição</span>}
                        </div>
                        {place?.detail && <div style={s.placeDetail}>{place.detail}</div>}
                      </td>
                      <td style={s.td}>
                        <div style={{ ...s.height, color: LEVELS[level].color }}>
                          {height != null ? `${(height * 100).toFixed(0)} cm` : '—'}
                        </div>
                        {/* A barra é relativa à leitura mais alta da lista, não a um máximo
                            absoluto: o que se compara aqui é um trecho com os outros. */}
                        {height != null && tallest > 0 && (
                          <div style={s.bar(height / tallest, LEVELS[level].color)} />
                        )}
                      </td>
                      <td style={s.td}>
                        <span style={s.pill(reading)} title={reading.title}>{reading.label}</span>
                      </td>
                      <td style={s.td}>
                        {segment.measurementCellsMeasured != null
                          ? `${segment.measurementCellsMeasured} · ${segment.measurementCellsAbstained} sem evidência`
                          : '—'}
                      </td>
                      <td style={s.td}>{segment.trackLocationQuality ?? '—'}</td>
                      {/* A captura primeiro, e a medição abaixo. O filtro de dia é pelo dia da
                          saída a campo, e essas duas datas divergem de verdade: uma sessão
                          gravada em 10/09 foi medida em 11/09. Mostrar só a medição faria a
                          coluna contradizer o filtro logo acima dela. */}
                      <td style={s.td}>
                        {segment.capturedAt
                          ? new Date(segment.capturedAt).toLocaleDateString('pt-BR')
                          : '—'}
                        {segment.measuredAt && (
                          <div style={s.subDate}>
                            medido {new Date(segment.measuredAt).toLocaleDateString('pt-BR')}
                          </div>
                        )}
                      </td>
                      <td style={{ ...s.td, ...s.chevron }}>
                        {openKey === keyOf(segment)
                          ? <ChevronDown size={15} style={s.chevronIcon} />
                          : <ChevronRight size={15} style={s.chevronIcon} />}
                      </td>
                    </tr>
                    {openKey === keyOf(segment) && (
                      <tr key={`${keyOf(segment)}:aberto`}>
                        <td style={s.detailCell} colSpan={9}>
                          <SegmentDetail segment={segment} place={place} />
                        </td>
                      </tr>
                    )}
                    </Fragment>
                  )
                })}
              </tbody>
            </table>
          )}
        </Card>
      )}

      <AnimatePresence>
        {ordering && chosenSegments.length > 0 && (
          <NewOrderModal
            segments={chosenSegments.map(segment => ({
              ...segment, placeLabel: placeOf(segment)?.label,
            }))}
            teams={teams ?? []}
            onCreated={() => setChosen([])}
            onClose={() => setOrdering(false)}
          />
        )}
      </AnimatePresence>
    </PageShell>
  )
}
