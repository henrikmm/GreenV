import { Fragment, useEffect, useMemo, useState } from 'react'
import { AnimatePresence } from 'framer-motion'
import { ClipboardList, Ruler, ChevronRight, ChevronDown } from 'lucide-react'
import { LEVELS, vegetationLevel, Card } from '@greenv/web-core'
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
  chipsRow: { display: 'flex', gap: 10, marginBottom: 10, flexWrap: 'wrap', alignItems: 'center' },
  controlsRow: { display: 'flex', gap: 8, marginBottom: 20, flexWrap: 'wrap', alignItems: 'center' },
  dateLabel: { fontSize: 12, color: 'var(--text-muted)' },
  chip: (active, colour) => ({
    display: 'inline-flex', alignItems: 'center', gap: 5, padding: '7px 13px', borderRadius: 20,
    fontSize: 12.5, fontWeight: 600, cursor: 'pointer', fontFamily: 'inherit',
    border: `1.5px solid ${active ? colour : 'var(--border)'}`,
    background: active ? `${colour}15` : 'white',
    color: active ? colour : 'var(--text-secondary)',
  }),
  dot: (colour) => ({ width: 8, height: 8, borderRadius: '50%', background: colour }),
  control: {
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
  chipCount: {
    fontFamily: 'var(--font-mono)', fontSize: 11, opacity: 0.75, marginLeft: 2,
  },
  clearDay: {
    padding: '7px 11px', fontSize: 12, fontFamily: 'inherit', cursor: 'pointer',
    border: '1px solid var(--border)', background: 'white', color: 'var(--text-muted)',
    borderRadius: 'var(--radius-sm)',
  },
  pager: {
    display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12,
    padding: '12px 14px', borderTop: '1px solid var(--border)',
  },
  pagerCount: { fontSize: 12, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' },
  pagerButtons: { display: 'flex', gap: 8 },
  pageBtn: (enabled) => ({
    padding: '7px 14px', fontSize: 12.5, fontWeight: 600, fontFamily: 'inherit',
    borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'white',
    color: enabled ? 'var(--text-primary)' : 'var(--text-muted)',
    cursor: enabled ? 'pointer' : 'not-allowed', opacity: enabled ? 1 : 0.5,
  }),
  chevron: { color: 'var(--text-muted)', width: 34, textAlign: 'center', lineHeight: 0 },
  chevronIcon: { display: 'inline-block', verticalAlign: 'middle' },
  detailCell: { padding: '0 14px', background: 'var(--bg-secondary)', borderBottom: '1px solid var(--border)' },
}

const keyOf = (segment) => `${segment.sessionId}:${segment.segmentIndex}`

const ORDERS = [
  ['HEIGHT_DESC', 'Mais altos primeiro'],
  ['HEIGHT_ASC', 'Mais baixos primeiro'],
  ['CAPTURED_DESC', 'Captura mais recente'],
  ['CAPTURED_ASC', 'Captura mais antiga'],
  ['MEASURED_DESC', 'Medição mais recente'],
]

const PAGE_SIZE = 25

/**
 * Um dia local vira um instante, no fuso de quem olha.
 *
 * O navegador é quem sabe onde a pessoa está; a API compara instantes, que é a única coisa que
 * uma coluna com fuso compara sem inventar. `plusDays` existe porque as duas pontas do intervalo
 * não são simétricas: "de 10/09" começa à meia-noite do dia 10, mas "até 11/09" tem de terminar
 * à meia-noite do dia 12, senão o dia que a pessoa escolheu como fim fica de fora dele.
 */
function instantOfDay(value, plusDays = 0) {
  if (!value) return undefined
  const [year, month, day] = value.split('-').map(Number)
  return new Date(year, month - 1, day + plusDays).toISOString()
}

export default function SegmentsPage() {
  const [sort, setSort] = useState('HEIGHT_DESC')
  const [filterLevel, setFilterLevel] = useState(null)
  // O dia da captura, não o da medição: é a saída a campo que a pessoa lembra. Campos de data
  // em vez de uma lista de dias, porque enumerar os dias que existem custaria varrer a tabela
  // inteira — que é justamente o que esta tela deixou de fazer. As duas pontas são
  // independentes: só "de" é daquele dia em diante, só "até" é tudo até ele.
  const [fromDay, setFromDay] = useState('')
  const [toDay, setToDay] = useState('')
  const [search, setSearch] = useState('')
  const [debounced, setDebounced] = useState('')
  const [offset, setOffset] = useState(0)

  const [state, setState] = useState({ loading: true, items: [], total: 0, hasMore: false })
  const [summary, setSummary] = useState(null)
  const [teams, setTeams] = useState([])

  // Os trechos escolhidos são guardados inteiros, não por chave. Uma ordem combinada junta os
  // piores do dia e eles raramente caem na mesma página; guardar só a chave perderia a escolha
  // no instante em que a linha saísse da tela.
  const [chosen, setChosen] = useState([])
  const [ordering, setOrdering] = useState(false)
  // Um trecho aberto por vez: dois mapas lado a lado numa tabela competem por atenção e
  // por rede, e a pergunta que a lista responde é sempre sobre uma leitura.
  const [openKey, setOpenKey] = useState(null)

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(search), 300)
    return () => clearTimeout(timer)
  }, [search])

  useEffect(() => { teamsApi.list().then(setTeams).catch(() => setTeams([])) }, [])

  const filters = useMemo(() => ({
    level: filterLevel,
    search: debounced,
    capturedFrom: instantOfDay(fromDay),
    capturedTo: instantOfDay(toDay, 1),
  }), [filterLevel, debounced, fromDay, toDay])

  // Trocar um filtro volta para a primeira página. Manter o deslocamento deixaria a lista vazia
  // com um total cheio, que lê como erro e é só a página cinco de um resultado de três linhas.
  useEffect(() => { setOffset(0); setOpenKey(null) }, [filters, sort])

  useEffect(() => {
    let live = true
    setState(previous => ({ ...previous, loading: true }))
    measurements.list({ ...filters, sort, limit: PAGE_SIZE, offset })
      .then(page => {
        if (live) setState({ loading: false, items: page.items, total: page.total, hasMore: page.hasMore })
      })
      .catch(error => { if (live) setState({ loading: false, items: [], total: 0, hasMore: false, error }) })
    return () => { live = false }
  }, [filters, sort, offset])

  // Os contadores não mudam quando se rola a lista, então só são buscados quando o filtro muda.
  useEffect(() => {
    let live = true
    measurements.summary(filters)
      .then(answer => { if (live) setSummary(answer) })
      .catch(() => { if (live) setSummary(null) })
    return () => { live = false }
  }, [filters])

  const { items: visible, total, hasMore, loading, error } = state

  // Cada trecho já vem com o próprio lugar resolvido pela API.
  const placeOf = (segment) => placeOfSegment(segment)

  const counts = summary?.countsByLevel ?? {}
  const countOf = (level) => counts[String(level)] ?? 0
  const tallest = summary?.tallestExtent95M ?? 0

  const chosenSegments = chosen
  const toggleChosen = (segment) => setChosen(previous => (
    previous.some(other => keyOf(other) === keyOf(segment))
      ? previous.filter(other => keyOf(other) !== keyOf(segment))
      : [...previous, segment]
  ))
  const isChosen = (segment) => chosen.some(other => keyOf(other) === keyOf(segment))

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
          <div style={{ ...s.statNumber, color: LEVELS[3].color }}>{countOf(3)}</div>
          <div style={s.statLabel}>Acima de 30 cm</div>
        </div>
        <div style={s.statCard(LEVELS[2].color)}>
          <div style={{ ...s.statNumber, color: LEVELS[2].color }}>{countOf(2)}</div>
          <div style={s.statLabel}>Entre 10 e 30 cm</div>
        </div>
        <div style={s.statCard(LEVELS[1].color)}>
          <div style={{ ...s.statNumber, color: LEVELS[1].color }}>{countOf(1)}</div>
          <div style={s.statLabel}>Abaixo de 10 cm</div>
        </div>
        <div style={s.statCard('var(--motiva)')}>
          <div style={{ ...s.statNumber, color: 'var(--motiva)' }}>
            {tallest > 0 ? `${(tallest * 100).toFixed(0)}` : '—'}
          </div>
          <div style={s.statLabel}>Maior altura, cm</div>
        </div>
      </div>

      {/* Duas linhas: o nível é uma escolha, o resto é um recorte. Numa linha só os sete
          controles empurravam a busca para fora da tela numa janela estreita. */}
      <div style={s.chipsRow}>
        <button style={s.chip(filterLevel === null, 'var(--motiva)')} onClick={() => setFilterLevel(null)}>
          Todos
        </button>
        {[3, 2, 1, 0].map(level => (
          <button key={level} style={s.chip(filterLevel === level, LEVELS[level].color)}
            onClick={() => setFilterLevel(filterLevel === level ? null : level)}>
            <span style={s.dot(LEVELS[level].color)} />{LEVELS[level].desc}
            <span style={s.chipCount}>{countOf(level)}</span>
          </button>
        ))}
      </div>

      <div style={s.controlsRow}>
        <select style={s.control} value={sort} onChange={event => setSort(event.target.value)}>
          {ORDERS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>

        {/* `max` e `min` cruzados deixam o próprio campo recusar um intervalo invertido, em vez
            de a lista voltar vazia sem explicar por quê. */}
        <span style={s.dateLabel}>de</span>
        <input type="date" style={s.control} value={fromDay} max={toDay || undefined}
          onChange={event => setFromDay(event.target.value)} />
        <span style={s.dateLabel}>até</span>
        <input type="date" style={s.control} value={toDay} min={fromDay || undefined}
          onChange={event => setToDay(event.target.value)} />
        {(fromDay || toDay) && (
          <button style={s.clearDay} onClick={() => { setFromDay(''); setToDay('') }}>
            limpar datas
          </button>
        )}

        <input style={s.searchInput} placeholder="Buscar por rua ou bairro…"
          value={search} onChange={event => setSearch(event.target.value)} />
      </div>

      {error && <Card><div style={{ ...s.empty, color: LEVELS[3].color }}>{error.message}</div></Card>}

      {!error && (
        <Card style={{ padding: 0, overflow: 'hidden' }}>
          {loading && visible.length === 0 && <div style={s.empty}>Carregando leituras…</div>}
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
                  <th style={s.th}>Altura p90</th><th style={s.th}>Nível</th>
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
                        <input type="checkbox" checked={isChosen(segment)}
                          onClick={(event) => event.stopPropagation()}
                          onChange={() => toggleChosen(segment)} />
                      </td>
                      <td style={{ ...s.td, ...s.rank }}>{offset + position + 1}</td>
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

          {/* O rodapé diz de quantas, porque com a lista paginada a contagem de linhas na tela
              deixou de responder isso. */}
          {total > 0 && (
            <div style={s.pager}>
              <span style={s.pagerCount}>
                {offset + 1}–{offset + visible.length} de {total}
                {chosen.length > 0 && ` · ${chosen.length} escolhido${chosen.length > 1 ? 's' : ''}`}
              </span>
              <div style={s.pagerButtons}>
                <button style={s.pageBtn(offset > 0)} disabled={offset === 0}
                  onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}>
                  Anterior
                </button>
                <button style={s.pageBtn(hasMore)} disabled={!hasMore}
                  onClick={() => setOffset(offset + PAGE_SIZE)}>
                  Próxima
                </button>
              </div>
            </div>
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
