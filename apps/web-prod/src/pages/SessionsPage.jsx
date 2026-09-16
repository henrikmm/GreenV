import { Fragment, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Route, Ruler, TriangleAlert, Camera, ArrowUpRight, ChevronRight, ChevronDown } from 'lucide-react'
import {
  LEVELS, vegetationLevel, AnimatedNumber, Card, useAuth,
  LevelDonut, WeeklyBarChart, bucketByWeek,
} from '@greenv/web-core'
import { sessions as sessionsApi, measurements } from '../api/greenv'
import { placeOfSegment } from '../api/place'
import { stretchKey, stretchRange } from '../api/stretch'
import PageShell from '../components/PageShell'
import SessionMap from '../components/SessionMap'

/**
 * Por onde o painel começa.
 *
 * Esta tela não existe na demonstração e não poderia existir antes: até a API ganhar uma rota de
 * listagem, não havia como descobrir o que tinha sido capturado sem já ter o identificador. O
 * arranjo é o da visão geral da demo — indicadores, dois gráficos, dois cartões, a tabela —
 * porque é o mesmo produto. O que muda é a origem: aqui cada número sai de uma medição que
 * existe, e nenhum é semeado.
 */
const KPI_ACCENTS = ['#5e22f3', '#0ea5a0', '#dc2626', '#ca8a04']

const s = {
  header: { marginBottom: 22 },
  greeting: { fontSize: 22, fontWeight: 700 },
  subtitle: { fontSize: 13, color: 'var(--text-secondary)', marginTop: 3 },
  kpiGrid: { display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginBottom: 12 },
  kpiIcon: (bg, c) => ({
    width: 32, height: 32, borderRadius: 9, background: bg, color: c,
    display: 'flex', alignItems: 'center', justifyContent: 'center', marginBottom: 12,
  }),
  kpiValue: { fontSize: 25, fontWeight: 700, fontFamily: 'var(--font-mono)' },
  kpiLabel: { fontSize: 11.5, color: 'var(--text-muted)', marginTop: 3 },
  chartsGrid: { display: 'grid', gridTemplateColumns: '1.6fr 1fr', gap: 12, marginBottom: 12 },
  cardTitle: { fontSize: 13, fontWeight: 700, marginBottom: 2 },
  cardHint: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 14 },
  criticalRow: {
    display: 'flex', alignItems: 'center', gap: 10, padding: '9px 4px',
    borderBottom: '1px solid var(--border)',
  },
  criticalDot: (c) => ({ width: 8, height: 8, borderRadius: '50%', background: c, flexShrink: 0 }),
  criticalName: { fontSize: 12.5, fontWeight: 600, flex: 1 },
  criticalValue: { fontSize: 11.5, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' },
  linkBtn: {
    display: 'flex', alignItems: 'center', gap: 4, fontSize: 12, fontWeight: 600,
    color: 'var(--motiva)', background: 'none', border: 'none', cursor: 'pointer',
    fontFamily: 'inherit', marginTop: 10, padding: 0,
  },
  statRow: {
    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    padding: '10px 12px', background: 'var(--bg-secondary)', borderRadius: 'var(--radius-sm)',
    marginBottom: 8,
  },
  statLabel: { display: 'flex', alignItems: 'center', gap: 8, fontSize: 12.5, color: 'var(--text-secondary)' },
  statValue: { fontSize: 15, fontWeight: 700, fontFamily: 'var(--font-mono)' },
  toolbar: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14, flexWrap: 'wrap' },
  daySelect: {
    padding: '7px 11px', background: 'white', border: '1px solid var(--border)',
    borderRadius: 'var(--radius-sm)', fontSize: 12.5, fontFamily: 'inherit',
    color: 'var(--text-primary)', outline: 'none', cursor: 'pointer',
  },
  toggle: (on) => ({
    padding: '7px 13px', borderRadius: 'var(--radius-sm)', fontSize: 12.5, fontWeight: 600,
    cursor: 'pointer', fontFamily: 'inherit',
    border: `1px solid ${on ? 'var(--motiva)' : 'var(--border)'}`,
    background: on ? 'var(--motiva-subtle)' : 'white',
    color: on ? 'var(--motiva)' : 'var(--text-secondary)',
  }),
  table: { width: '100%', borderCollapse: 'separate', borderSpacing: 0 },
  th: {
    padding: '10px 12px', textAlign: 'left', fontSize: 10, fontWeight: 700,
    color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em',
    borderBottom: '1px solid var(--border)',
  },
  td: { padding: '13px 12px', fontSize: 12.5, borderBottom: '1px solid var(--border)' },
  row: { cursor: 'pointer' },
  mono: { fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--text-muted)' },
  pill: (color, bg) => ({
    display: 'inline-flex', alignItems: 'center', gap: 6, padding: '3px 10px',
    borderRadius: 20, fontSize: 11, fontWeight: 600, color, background: bg,
  }),
  empty: { padding: 34, textAlign: 'center', color: 'var(--text-muted)', fontSize: 12.5 },
  dateLabel: { fontSize: 11.5, color: 'var(--text-muted)' },
  pager: {
    display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12,
    padding: '10px 2px 14px', fontSize: 12,
  },
  pagerCount: { fontSize: 11.5, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' },
  pageBtn: (enabled) => ({
    padding: '6px 13px', fontSize: 12, fontWeight: 600, fontFamily: 'inherit',
    borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'white',
    color: enabled ? 'var(--text-primary)' : 'var(--text-muted)',
    cursor: enabled ? 'pointer' : 'not-allowed', opacity: enabled ? 1 : 0.5,
  }),
  chevron: { color: 'var(--text-muted)', width: 26, textAlign: 'center', lineHeight: 0 },
  openRow: { cursor: 'pointer', background: 'var(--motiva-subtle)' },
  previewCell: { padding: '0 12px 14px', background: 'var(--bg-secondary)', borderBottom: '1px solid var(--border)' },
  previewHead: {
    display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12,
    padding: '10px 0', flexWrap: 'wrap',
  },
  previewTitle: { fontSize: 12, color: 'var(--text-secondary)' },
  previewState: { padding: '18px 0', fontSize: 12, color: 'var(--text-muted)' },
  // Um ponto por nível, com a contagem ao lado. É o que a ordenação por críticos usa, então
  // precisa estar visível na linha - ordenar por um número que não aparece é adivinhação.
  tally: { display: 'inline-flex', alignItems: 'center', gap: 8 },
  tallyItem: (colour) => ({
    display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 11.5,
    fontFamily: 'var(--font-mono)', color: colour, fontWeight: 700,
  }),
  openBtn: {
    display: 'inline-flex', alignItems: 'center', gap: 5, padding: '6px 11px', fontSize: 11.5,
    fontWeight: 700, fontFamily: 'inherit', borderRadius: 'var(--radius-sm)',
    border: '1px solid var(--border)', background: 'white', color: 'var(--motiva)',
    cursor: 'pointer',
  },
}

/**
 * Como ordenar as sessões, nos nomes que a API entende.
 *
 * O padrão é a pior primeiro, que é a pergunta que a tela responde: para onde mandar a equipe.
 * A ordenação acontece no servidor, sobre todas as sessões — ordenar a página no navegador
 * ordenaria as vinte e cinco que vieram por data, e não as piores que existem.
 */
const ORDENS = [
  ['CRITICAL_DESC', 'Mais críticas primeiro'],
  ['STARTED_DESC', 'Mais recentes primeiro'],
  ['STARTED_ASC', 'Mais antigas primeiro'],
]

const PAGE_SIZE = 25

/** O dia escolhido no campo de data vira o instante que a API espera, no fuso de quem olha. */
function instantOfDay(value, plusDays = 0) {
  if (!value) return undefined
  const [year, month, day] = value.split('-').map(Number)
  return new Date(year, month - 1, day + plusDays).toISOString()
}

export default function SessionsPage() {
  const navigate = useNavigate()
  const { user } = useAuth()
  const [state, setState] = useState({ loading: true })
  const [measuredOnly, setMeasuredOnly] = useState(false)
  const [ordem, setOrdem] = useState('CRITICAL_DESC')
  const [fromDay, setFromDay] = useState('')
  const [toDay, setToDay] = useState('')
  const [offset, setOffset] = useState(0)
  // Uma sessão aberta por vez, e a trilha dela buscada só quando alguém abre: são cinquenta
  // sessões na página e quase nenhuma será olhada.
  const [openSession, setOpenSession] = useState(null)
  const [tracks, setTracks] = useState({})

  // Trocar de filtro ou de ordem volta para a primeira página: a página três de uma pergunta
  // não é a página três de outra, e ficar nela mostraria um pedaço arbitrário do meio.
  useEffect(() => { setOffset(0); setOpenSession(null) }, [measuredOnly, ordem, fromDay, toDay])

  useEffect(() => {
    let live = true
    setState(previous => ({ ...previous, loading: true }))
    // A lista vem do servidor já filtrada, ordenada e paginada. Os indicadores do topo vêm do
    // resumo, que conta o conjunto inteiro; os cinco mais altos vêm de uma página de cinco.
    // Nenhum dos três precisa das leituras todas no navegador, e baixá-las seria responder
    // sobre o que coube na memória em vez de sobre o que foi medido.
    Promise.all([
      sessionsApi.list({
        measuredOnly,
        sort: ordem,
        capturedFrom: instantOfDay(fromDay),
        capturedTo: instantOfDay(toDay, 1),
        limit: PAGE_SIZE,
        offset,
      }),
      measurements.summary(),
      measurements.list({ sort: 'HEIGHT_DESC', limit: 5 }),
    ])
      .then(([page, resumo, maiores]) => {
        if (live) setState({ loading: false, page, resumo, maiores: maiores.items ?? [] })
      })
      .catch(error => { if (live) setState({ loading: false, error }) })
    return () => { live = false }
  }, [measuredOnly, ordem, fromDay, toDay, offset])

  const { page, resumo, maiores, loading, error } = state

  // Cada número do topo é do conjunto inteiro, não da página: os níveis e a maior altura vêm do
  // resumo que o servidor calcula sobre tudo, e os cinco mais altos vêm de uma página de cinco
  // pedida na ordem de altura. Somar o que coube na memória responderia sobre a requisição.
  const counts = useMemo(() => ({
    1: Number(resumo?.countsByLevel?.['1'] ?? 0),
    2: Number(resumo?.countsByLevel?.['2'] ?? 0),
    3: Number(resumo?.countsByLevel?.['3'] ?? 0),
  }), [resumo])

  const tallest = useMemo(() => maiores ?? [], [maiores])

  /**
   * O lugar de uma sessão, do jeito que a API resolveu.
   *
   * Vem com a linha e não é montado aqui: numa lista paginada o navegador não tem as leituras
   * das sessões que não estão na página, e dizer "sem posição" por isso seria mentira sobre o
   * dado. Quando a volta cruzou mais de uma via, a contagem é o que permite dizer isso.
   */
  const placeOf = (session) => {
    if (!session?.placeLabel) return null
    const outras = (session.placeLabelCount ?? 1) - 1
    return {
      label: session.placeLabel,
      detail: outras > 0 ? `e mais ${outras} ${outras === 1 ? 'via' : 'vias'}` : session.placeDetail,
    }
  }

  // As semanas saem das sessões desta página, que é o que o navegador tem. O rótulo do cartão
  // diz isso; um gráfico que parecesse falar do histórico inteiro seria mais bonito e mentiroso.
  const weekly = useMemo(
    () => bucketByWeek((page?.items ?? []).map(session => session.startedAt), 10),
    [page])

  const overdue = counts[3]
  const highest = resumo?.tallestExtent95M ?? 0
  const measuredTotal = counts[1] + counts[2] + counts[3]

  const kpis = [
    { icon: Route, label: 'Sessões capturadas', value: page?.total ?? 0 },
    { icon: Ruler, label: 'Trechos medidos', value: measured?.total ?? 0 },
    { icon: TriangleAlert, label: 'Acima de 30 cm', value: overdue },
    { icon: Camera, label: 'Maior altura p95', value: highest * 100, decimals: 0, suffix: ' cm' },
  ]

  return (
    <PageShell currentPage="sessions">
      <div style={s.header}>
        <div style={s.greeting}>Olá, {user?.name?.split(' ')[0] || 'operador'} 👋</div>
        <div style={s.subtitle}>Visão geral das capturas — o que foi gravado e o que já foi medido.</div>
      </div>

      <div style={s.kpiGrid}>
        {kpis.map((k, i) => {
          const Icon = k.icon
          const accent = KPI_ACCENTS[i]
          return (
            <Card key={k.label} delay={i * 0.05}>
              <div style={s.kpiIcon(`${accent}18`, accent)}><Icon size={16} /></div>
              <div style={s.kpiValue}>
                <AnimatedNumber value={k.value} decimals={k.decimals || 0} suffix={k.suffix ?? ''} />
              </div>
              <div style={s.kpiLabel}>{k.label}</div>
            </Card>
          )
        })}
      </div>

      <div style={s.chartsGrid}>
        <Card delay={0.15}>
          <div style={s.cardTitle}>Capturas por semana</div>
          <div style={s.cardHint}>Sessões gravadas em campo, últimas 10 semanas</div>
          <WeeklyBarChart data={weekly} label="sessões" />
        </Card>
        <Card delay={0.2}>
          <div style={s.cardTitle}>Trechos por nível</div>
          <div style={s.cardHint}>Classificação da altura medida em cada trecho</div>
          <LevelDonut counts={counts} total={measuredTotal} />
        </Card>
      </div>

      <Card delay={0.25} style={{ marginBottom: 12 }}>
        <div style={s.cardTitle}>Trechos mais altos</div>
        <div style={s.cardHint}>Maior altura p95 medida — prioridade máxima</div>
        {tallest.map(segment => (
          // A janela entra na chave e no rótulo: dois trechos vizinhos da mesma volta têm o mesmo
          // segmento e a mesma rua, e o que os separa são vinte e cinco metros.
          <div key={stretchKey(segment)} style={s.criticalRow}>
            <span style={s.criticalDot(LEVELS[vegetationLevel(segment.measurementLevel)].color)} />
            <span style={s.criticalName}>
              {placeOfSegment(segment)?.label ?? `Trecho ${segment.segmentIndex}`}
              {stretchRange(segment) && <> · {stretchRange(segment)}</>}
            </span>
            <span style={s.criticalValue}>
              {(segment.measurementExtent95P95M * 100).toFixed(0)} cm
            </span>
          </div>
        ))}
        {tallest.length === 0 && <div style={s.empty}>Nenhum trecho medido ainda.</div>}
        <button style={s.linkBtn} onClick={() => navigate('/trechos')}>
          Ver todos os trechos <ArrowUpRight size={13} />
        </button>
      </Card>

      <Card delay={0.35} style={{ padding: '18px 18px 4px' }}>
        <div style={s.cardTitle}>Sessões de captura</div>
        <div style={s.cardHint}>Clique numa linha para ver a trilha e os quadros daquela sessão.</div>

        <div style={s.toolbar}>
          <button style={s.toggle(measuredOnly)} onClick={() => setMeasuredOnly(v => !v)}>
            Somente com medição
          </button>
          {/* `max` e `min` cruzados deixam o próprio campo recusar um intervalo invertido, em
              vez de a lista voltar vazia sem explicar por quê. As datas vão para o servidor: um
              filtro aplicado aqui recortaria as vinte e cinco que vieram, e não as que existem. */}
          <span style={s.dateLabel}>de</span>
          <input type="date" style={s.daySelect} value={fromDay} max={toDay || undefined}
            onChange={event => setFromDay(event.target.value)} />
          <span style={s.dateLabel}>até</span>
          <input type="date" style={s.daySelect} value={toDay} min={fromDay || undefined}
            onChange={event => setToDay(event.target.value)} />
          {(fromDay || toDay) && (
            <button style={s.toggle(false)} onClick={() => { setFromDay(''); setToDay('') }}>
              limpar datas
            </button>
          )}
          <select style={s.daySelect} value={ordem}
            onChange={event => setOrdem(event.target.value)}>
            {ORDENS.map(([valor, rotulo]) => (
              <option key={valor} value={valor}>{rotulo}</option>
            ))}
          </select>
        </div>

        {loading && <div style={s.empty}>Carregando…</div>}
        {error && <div style={{ ...s.empty, color: LEVELS[3].color }}>{error.message}</div>}

        {page && !loading && (
          <table style={s.table}>
            <thead>
              <tr>
                <th style={s.th} />
                <th style={s.th}>Início</th>
                <th style={s.th}>Onde</th>
                <th style={s.th}>Dispositivo</th>
                <th style={s.th}>Trechos por nível</th>
                <th style={s.th}>Medidos</th>
                <th style={s.th} />
              </tr>
            </thead>
            <tbody>
              {(page?.items ?? []).map(session => {
                const aberta = openSession === session.sessionId
                const niveis = {
                  1: session.level1Count ?? 0,
                  2: session.level2Count ?? 0,
                  3: session.level3Count ?? 0,
                  0: session.unratedCount ?? 0,
                }
                const track = tracks[session.sessionId]
                return (
                <Fragment key={session.sessionId}>
                <tr style={aberta ? s.openRow : s.row}
                  onClick={() => abrirSessao(session.sessionId)}>
                  <td style={{ ...s.td, ...s.chevron }}>
                    {aberta ? <ChevronDown size={15} /> : <ChevronRight size={15} />}
                  </td>
                  <td style={s.td}>{new Date(session.startedAt).toLocaleString('pt-BR')}</td>
                  <td style={s.td}>{(() => {
                    const place = placeOf(session)
                    if (!place) {
                      return <span style={{ color: 'var(--text-muted)' }}>sem posição</span>
                    }
                    return (
                      <>
                        {place.label}
                        {place.detail && (
                          <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{place.detail}</div>
                        )}
                      </>
                    )
                  })()}</td>
                  <td style={{ ...s.td, ...s.mono }}>{session.deviceId}</td>
                  <td style={s.td}>
                    {niveis[3] + niveis[2] + niveis[1] + niveis[0] === 0
                      ? <span style={{ color: 'var(--text-muted)' }}>—</span>
                      : (
                        <span style={s.tally}>
                          {[3, 2, 1, 0].filter(nivel => niveis[nivel] > 0).map(nivel => (
                            <span key={nivel} style={s.tallyItem(LEVELS[nivel].color)}
                              title={`${LEVELS[nivel].label} · ${LEVELS[nivel].desc}`}>
                              <span style={s.criticalDot(LEVELS[nivel].color)} />{niveis[nivel]}
                            </span>
                          ))}
                        </span>
                      )}
                  </td>
                  <td style={s.td}>
                    {/* Cinza quando nada foi medido: um zero em verde leria como conformidade. */}
                    <span style={session.measuredSegmentCount > 0
                      ? s.pill('var(--motiva)', 'var(--motiva-subtle)')
                      : s.pill(LEVELS[0].color, LEVELS[0].bg)}>
                      {session.measuredSegmentCount} de {session.segmentCount}
                    </span>
                  </td>
                  <td style={s.td}>
                    <button style={s.openBtn}
                      onClick={(event) => {
                        event.stopPropagation()
                        navigate(`/sessoes/${session.sessionId}`)
                      }}>
                      Abrir <ArrowUpRight size={13} />
                    </button>
                  </td>
                </tr>
                {aberta && (
                  <tr>
                    <td style={s.previewCell} colSpan={7}>
                      <div style={s.previewHead}>
                        <div style={s.previewTitle}>
                          Cada faixa colorida é um trecho de cerca de 25 m, na cor do nível dele.
                        </div>
                        <button style={s.openBtn}
                          onClick={() => navigate(`/sessoes/${session.sessionId}`)}>
                          Ver trechos e quadros <ArrowUpRight size={13} />
                        </button>
                      </div>
                      {/* O mapa aqui é para reconhecer a volta de relance; clicar num trecho
                          pede a tela da sessão, que tem a foto e a leitura ao lado. */}
                      {track === null && <div style={s.previewState}>Carregando o mapa…</div>}
                      {track === false && <div style={s.previewState}>Não foi possível carregar a trilha.</div>}
                      {track && !track.features?.length && (
                        <div style={s.previewState}>Esta sessão ainda não tem trecho medido para desenhar.</div>
                      )}
                      {track && track.features?.length > 0 && (
                        <SessionMap track={track} height={300}
                          onStretchClick={() => navigate(`/sessoes/${session.sessionId}`)} />
                      )}
                    </td>
                  </tr>
                )}
                </Fragment>
                )
              })}
              {(page?.items ?? []).length === 0 && (
                <tr><td style={{ ...s.td, ...s.empty }} colSpan={7}>
                  Nenhuma sessão {measuredOnly ? 'com medição ' : ''}encontrada
                  {fromDay || toDay ? ' nesse intervalo de datas' : ''}.
                </td></tr>
              )}
            </tbody>
          </table>
        )}

        {/* O total é do conjunto que o filtro selecionou, não do que veio: é o que diz se vale
            a pena avançar. */}
        {page && !loading && page.total > 0 && (
          <div style={s.pager}>
            <span style={s.pagerCount}>
              {offset + 1}–{offset + (page.items?.length ?? 0)} de {page.total}
            </span>
            <div style={{ display: 'flex', gap: 8 }}>
              <button style={s.pageBtn(offset > 0)} disabled={offset === 0}
                onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}>
                Anteriores
              </button>
              <button style={s.pageBtn(page.hasMore)} disabled={!page.hasMore}
                onClick={() => setOffset(offset + PAGE_SIZE)}>
                Próximas
              </button>
            </div>
          </div>
        )}
      </Card>
    </PageShell>
  )
}
