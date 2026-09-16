import { Fragment, useEffect, useRef, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { AnimatePresence } from 'framer-motion'
import { ArrowLeft, ClipboardList, ChevronRight, ChevronDown } from 'lucide-react'
import { LEVELS, Card } from '@greenv/web-core'
import { sessions, teams as teamsApi } from '../api/greenv'
import { placeOfSegment, placeOfSession } from '../api/place'
import { readingOf } from '../api/reading'
import { sameStretch, stretchKey, stretchRange } from '../api/stretch'
import NewOrderModal from '../components/NewOrderModal'
import SessionMap from '../components/SessionMap'
import SegmentDetail from '../components/SegmentDetail'
import PageShell from '../components/PageShell'

/**
 * Uma sessão: por onde passou, o que mediu, e a foto de cada ponto.
 *
 * O mapa ocupa a largura toda e a lista fica ao lado do quadro selecionado, que é o mesmo arranjo
 * que a tela de mapa da demonstração usa.
 *
 * A lista tem dois níveis, porque a captura tem dois. O **segmento** é o que o telefone enviou —
 * dez segundos de vídeo — e é por segmento que a sessão foi gravada. O **trecho** é a janela de
 * cerca de 25 m em que o segmento foi medido, e é o trecho que tem altura, nível e células, e
 * para onde uma equipe é mandada. Abrir um segmento é ver os trechos dele. Um segmento medido
 * antes do corte não tem nenhum, e abre direto no detalhe, como sempre abriu.
 */
const PAGE_SIZE = 25

const s = {
  back: {
    display: 'flex', alignItems: 'center', gap: 5, fontSize: 12, color: 'var(--text-muted)',
    cursor: 'pointer', background: 'none', border: 'none', padding: 0, fontFamily: 'inherit',
    marginBottom: 8,
  },
  header: { marginBottom: 18 },
  title: { fontSize: 22, fontWeight: 700, color: 'var(--text-primary)' },
  subtitle: { fontSize: 13, color: 'var(--text-secondary)', marginTop: 3 },
  list: { marginTop: 12, scrollMarginTop: 12 },
  chipsRow: { display: 'flex', gap: 8, marginBottom: 12, flexWrap: 'wrap', alignItems: 'center' },
  focusBar: {
    display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12,
    padding: '8px 12px', marginBottom: 10, borderRadius: 'var(--radius-sm)',
    background: 'var(--motiva-subtle)', fontSize: 11.5, color: 'var(--text-secondary)',
    flexWrap: 'wrap',
  },
  focusIndex: { fontFamily: 'var(--font-mono)', color: 'var(--text-primary)' },
  pager: {
    display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12,
    padding: '8px 2px 12px', fontSize: 12,
  },
  mapNote: { fontSize: 11, color: 'var(--text-muted)', padding: '8px 2px 0', lineHeight: 1.5 },
  pagerCount: { fontSize: 11.5, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' },
  pagerButtons: { display: 'flex', gap: 8 },
  pageBtn: (enabled) => ({
    padding: '6px 13px', fontSize: 12, fontWeight: 600, fontFamily: 'inherit',
    borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'white',
    color: enabled ? 'var(--text-primary)' : 'var(--text-muted)',
    cursor: enabled ? 'pointer' : 'not-allowed', opacity: enabled ? 1 : 0.5,
  }),
  focusBack: {
    padding: '5px 11px', fontSize: 11.5, fontWeight: 600, fontFamily: 'inherit',
    cursor: 'pointer', borderRadius: 'var(--radius-sm)', background: 'white',
    border: '1px solid var(--border)', color: 'var(--motiva)',
  },
  chip: (active, colour) => ({
    display: 'inline-flex', alignItems: 'center', gap: 5, padding: '6px 11px', borderRadius: 20,
    fontSize: 12, fontWeight: 600, cursor: 'pointer', fontFamily: 'inherit',
    border: `1.5px solid ${active ? colour : 'var(--border)'}`,
    background: active ? `${colour}15` : 'white',
    color: active ? colour : 'var(--text-secondary)',
  }),
  chipCount: { fontFamily: 'var(--font-mono)', fontSize: 10.5, opacity: 0.75, marginLeft: 2 },
  dot: (colour) => ({ width: 7, height: 7, borderRadius: '50%', background: colour }),
  cardTitle: { fontSize: 13, fontWeight: 700, marginBottom: 2 },
  cardHint: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 14 },
  table: { width: '100%', borderCollapse: 'separate', borderSpacing: 0 },
  clickable: (open) => ({ cursor: 'pointer', background: open ? 'var(--motiva-subtle)' : 'transparent' }),
  chevron: { color: 'var(--text-muted)', width: 30, textAlign: 'center', lineHeight: 0 },
  detailCell: { padding: '0 10px', background: 'var(--bg-secondary)', borderBottom: '1px solid var(--border)' },
  th: {
    padding: '9px 10px', textAlign: 'left', fontSize: 10, fontWeight: 700,
    color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em',
    borderBottom: '1px solid var(--border)',
  },
  td: { padding: '12px 10px', fontSize: 12.5, borderBottom: '1px solid var(--border)' },
  mono: { fontFamily: 'var(--font-mono)' },
  windowBox: { padding: '4px 0 10px' },
  windowTitle: {
    fontSize: 10, fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase',
    letterSpacing: '0.06em', padding: '10px 0 2px',
  },
  windowState: { padding: '14px 0', fontSize: 12, color: 'var(--text-muted)' },
  pill: (reading) => ({
    display: 'inline-flex', padding: '3px 10px', borderRadius: 20, fontSize: 11, fontWeight: 600,
    color: reading.colour, background: reading.background, lineHeight: 1.35,
  }),
  hint: { fontSize: 12.5, color: 'var(--text-muted)', lineHeight: 1.6 },
  cardHead: { display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12 },
  orderBtn: (enabled) => ({
    display: 'flex', alignItems: 'center', gap: 6, padding: '8px 13px', fontSize: 12.5,
    fontWeight: 700, borderRadius: 'var(--radius-sm)', fontFamily: 'inherit', flexShrink: 0,
    cursor: enabled ? 'pointer' : 'not-allowed',
    border: `1.5px solid ${enabled ? 'var(--motiva)' : 'var(--border)'}`,
    background: enabled ? 'var(--motiva)' : 'white',
    color: enabled ? 'white' : 'var(--text-muted)',
  }),
}

/** A chave de um trecho aberto dentro da lista, antes mesmo de a linha dele ter chegado. */
const windowKeyOf = (segmentIndex, windowIndex) => `${segmentIndex}:${windowIndex}`

export default function SessionDetailPage() {
  const { sessionId } = useParams()
  const navigate = useNavigate()
  const [state, setState] = useState({ loading: true })
  // Um segmento aberto por vez e, dentro dele, um trecho — e o quadro que abriu os dois.
  const [openIndex, setOpenIndex] = useState(null)
  const [openWindow, setOpenWindow] = useState(null)
  const [focusFrame, setFocusFrame] = useState(null)
  // Clicar num ponto é uma pergunta sobre um trecho. Deixar os outros oito na tela obriga a
  // procurar de novo, no meio deles, aquele que acabou de ser apontado. O mapa de cima continua
  // inteiro, porque é por ele que se troca de trecho.
  const [focusIndex, setFocusIndex] = useState(null)
  // Finita não é o mesmo que curta: uma hora de campo são trezentos e sessenta trechos de dez
  // segundos. Filtro, página e contagem vêm do servidor, como na lista de trechos.
  const [filterLevel, setFilterLevel] = useState(null)
  const [offset, setOffset] = useState(0)
  const [summary, setSummary] = useState(null)
  const [focusSegment, setFocusSegment] = useState(null)
  const listRef = useRef(null)
  // As linhas escolhidas são guardadas inteiras, não por índice: um alvo de ordem é um trecho, e
  // ele precisa levar junto a janela e a medição que justificam a ordem.
  const [chosen, setChosen] = useState([])
  const [ordering, setOrdering] = useState(false)
  // Os trechos de cada segmento, buscados na primeira vez que alguém precisa deles. Buscar os de
  // todos os segmentos da página seriam vinte e cinco chamadas antes de a tela aparecer, e quase
  // nenhuma seria aberta.
  const [windowsBySegment, setWindowsBySegment] = useState({})

  useEffect(() => {
    let live = true
    setState({ loading: true })
    setOpenIndex(null)
    setOpenWindow(null)
    setFocusFrame(null)
    setFocusIndex(null)
    setFocusSegment(null)
    setOffset(0)
    setChosen([])
    setWindowsBySegment({})
    Promise.all([
      sessions.get(sessionId), sessions.track(sessionId),
      // Uma ordem pode ser aberta sem equipe, então a lista falhar não impede o resto.
      teamsApi.list().catch(() => []),
      sessions.segmentsSummary(sessionId).catch(() => null),
    ])
      .then(([session, track, teams, tally]) => {
        if (!live) return
        setSummary(tally)
        setState({ loading: false, session, track, teams, segments: [], frames: [], total: 0 })
      })
      .catch(error => { if (live) setState({ loading: false, error }) })
    return () => { live = false }
  }, [sessionId])

  // A página de trechos, e os quadros só dos trechos dela. Vinte e cinco chamadas por página em
  // vez de uma por trecho da sessão inteira, que é o que fazia esta tela demorar a aparecer.
  useEffect(() => {
    let live = true
    sessions.segments(sessionId, { level: filterLevel, limit: PAGE_SIZE, offset })
      .then(async page => {
        const lists = await Promise.all(
          page.items.map(segment =>
            sessions.frames(sessionId, segment.segmentIndex)
              .then(list => list.map(frame => ({ ...frame, segmentIndex: segment.segmentIndex })))
              // Um trecho sem manifesto responde 409, o que é uma resposta e não uma falha.
              .catch(() => [])),
        )
        if (!live) return
        setState(previous => ({
          ...previous,
          segments: page.items,
          total: page.total,
          hasMore: page.hasMore,
          frames: lists.flat(),
        }))
      })
      .catch(() => { if (live) setState(previous => ({ ...previous, segments: [], total: 0 })) })
    return () => { live = false }
  }, [sessionId, filterLevel, offset])

  // Trocar de filtro volta para a primeira página, senão a lista abre vazia num deslocamento
  // que o novo filtro não alcança.
  useEffect(() => { setOffset(0) }, [filterLevel])

  // O mapa ocupa a dobra inteira, então escolher um ponto nele deixava a resposta fora da tela
  // e a pessoa rolando atrás da foto que acabou de pedir. Escolher é perguntar; levar até a
  // resposta faz parte.
  useEffect(() => {
    if (focusIndex === null) return undefined
    const node = listRef.current
    if (!node) return undefined

    const trazer = () => node.scrollIntoView({ behavior: 'smooth', block: 'start' })
    trazer()

    // O detalhe do trecho chega depois, com o mapa e a foto, e é ele que dá à página altura
    // para rolar. Uma rolagem só, disparada na hora do clique, para no limite de uma página que
    // ainda era curta e deixa a foto meio palmo abaixo da borda. Observar o crescimento é o que
    // faz a rolagem terminar onde a resposta está, e não onde ela ainda não estava.
    const observador = new ResizeObserver(trazer)
    observador.observe(node)
    // E para de insistir, senão disputa a rolagem com quem já está lendo.
    const fim = setTimeout(() => observador.disconnect(), 4000)
    return () => { observador.disconnect(); clearTimeout(fim) }
  }, [focusIndex, focusFrame])

  /** Um segmento cortado em janelas. Os medidos antes do corte não têm nenhuma. */
  const hasWindows = (segment) => (segment.windowCount ?? 0) > 0

  const loadWindows = async (segmentIndex) => {
    const cached = windowsBySegment[segmentIndex]
    if (cached) return cached
    // Um segmento sem trechos publicados responde vazio, e isso é resposta e não falha.
    const rows = await sessions.windows(sessionId, segmentIndex).catch(() => [])
    setWindowsBySegment(previous => ({ ...previous, [segmentIndex]: rows }))
    return rows
  }

  /** Abre o segmento apontado no mapa, o trecho dentro dele, e deixa a lista só com esse segmento. */
  const focusOn = (segmentIndex, windowIndex) => {
    setOpenIndex(segmentIndex)
    setFocusIndex(segmentIndex)
    setOpenWindow(windowIndex == null ? null : windowKeyOf(segmentIndex, windowIndex))
    const onPage = (state.segments ?? []).find(segment => segment.segmentIndex === segmentIndex)
    if (windowIndex != null || (onPage && hasWindows(onPage))) loadWindows(segmentIndex)
    setFocusSegment(onPage ?? null)
    // O trecho apontado pode nem estar na página carregada, e aí ele é buscado por conta própria.
    if (!onPage) {
      sessions.segment(sessionId, segmentIndex)
        .then(setFocusSegment)
        .catch(() => setFocusIndex(null))
    }
  }

  const entryOf = (row) => ({ ...row, sessionId })
  const isChosen = (row) => chosen.some(other => sameStretch(other, entryOf(row)))
  const chosenOfSegment = (segmentIndex) =>
    chosen.filter(row => row.segmentIndex === segmentIndex).length

  const toggleStretch = (row) => setChosen(previous => (
    previous.some(other => sameStretch(other, entryOf(row)))
      ? previous.filter(other => !sameStretch(other, entryOf(row)))
      : [...previous, entryOf(row)]
  ))

  /**
   * Marcar um segmento é marcar os trechos medidos dele.
   *
   * A equipe é mandada a 25 m de margem, e é o trecho que carrega a medição que justifica a
   * ordem — o segmento enviado não é um destino. Marcar pela linha de cima existe para não obrigar
   * ninguém a abrir o segmento e marcar oito caixas para despachar a volta inteira.
   */
  const toggleSegment = async (segment) => {
    if (chosenOfSegment(segment.segmentIndex) > 0) {
      setChosen(previous => previous.filter(row => row.segmentIndex !== segment.segmentIndex))
      return
    }
    const rows = hasWindows(segment) ? await loadWindows(segment.segmentIndex) : [segment]
    const measurable = rows.filter(row => row.measurementState != null).map(entryOf)
    setChosen(previous => [
      ...previous,
      ...measurable.filter(entry => !previous.some(other => sameStretch(other, entry))),
    ])
  }

  // O lugar vem dos trechos medidos, não do campo de via: aquele era texto livre digitado em
  // campo e não identifica lugar nenhum. A API resolve a rua de cada trecho; uma sessão que
  // cruza mais de uma diz a primeira e conta as outras.
  // O rótulo sai dos trechos carregados, que agora são uma página. Numa sessão de uma saída só
  // isso dá o mesmo nome, e quando não dá o próprio rótulo já diz "e mais N vias".
  const place = placeOfSession((state.segments ?? [])
    .filter(segment => segment.measurementState != null))

  if (state.loading) {
    return <PageShell currentPage="sessions"><div style={s.hint}>Carregando sessão…</div></PageShell>
  }
  if (state.error) {
    return (
      <PageShell currentPage="sessions">
        <div style={{ ...s.hint, color: LEVELS[3].color }}>{state.error.message}</div>
      </PageShell>
    )
  }

  const { session, segments, track, frames, teams, total = 0, hasMore = false } = state
  // As contagens são da sessão inteira e vêm do servidor: uma contagem da página seria um fato
  // sobre a requisição, e não sobre a sessão.
  const countsByLevel = summary?.countsByLevel ?? {}
  const countOf = (level) => countsByLevel[String(level)] ?? 0
  const levelsPresent = [3, 2, 1, 0].filter(level => countOf(level) > 0)

  // Em foco a lista é só o trecho apontado, que pode nem estar na página carregada — daí ele
  // ser buscado por conta própria quando não estiver.
  const visibleSegments = focusIndex !== null
    ? (segments.filter(segment => segment.segmentIndex === focusIndex).length
        ? segments.filter(segment => segment.segmentIndex === focusIndex)
        : (focusSegment ? [focusSegment] : []))
    : segments

  // A escolha atravessa páginas e filtros, porque guarda a linha inteira e não um índice da
  // página carregada.
  const chosenSegments = chosen
  const road = place ? place.label.toUpperCase() : 'SEM POSIÇÃO REGISTRADA'

  return (
    <PageShell currentPage="sessions" roadTag={road}>
      <div style={s.header}>
        <button style={s.back} onClick={() => navigate('/sessoes')}>
          <ArrowLeft size={13} /> Sessões
        </button>
        <div style={s.title}>{place?.label ?? 'Sem posição registrada'}</div>
        <div style={s.subtitle}>
          {place?.detail && <>{place.detail} · </>}
          {new Date(session.startedAt).toLocaleString('pt-BR')} · {session.segmentCount} segmentos,
          {' '}{session.measuredSegmentCount} medidos · {frames.length} quadros publicados
        </div>
      </div>

      <Card style={{ padding: 12 }}>
        {/* Clicar num ponto abre o trecho a que ele pertence, já naquele quadro. O painel
            lateral que existia aqui mostrava a foto longe da leitura que a explica; dentro do
            trecho ela aparece junto da altura, da cobertura e dos quadros vizinhos. */}
        <SessionMap track={track} frames={frames} height={440}
          // O quadro traz a janela que o reconstruiu, então abrir a foto é abrir o trecho dela.
          onFrameClick={(frame) => {
            focusOn(frame.segmentIndex, frame.windowIndex ?? null)
            setFocusFrame(frame.fileName)
          }}
          // Cada faixa colorida é um trecho medido, então clicar numa delas é perguntar por
          // aquele trecho e não pelo segmento em que ele caiu.
          onStretchClick={(properties) => {
            focusOn(properties.segmentIndex, properties.windowIndex ?? null)
            setFocusFrame(null)
          }} />
        {/* A linha é a sessão inteira; os pontos são só os segmentos listados abaixo. Buscar os
            quadros de todos eles seria uma chamada por segmento, que é o que esta tela deixou de
            fazer. Dizer isso é melhor do que deixar a pessoa concluir que a captura acabou
            onde os pontos acabam. */}
        <div style={s.mapNote}>
          A linha é o caminho inteiro da sessão. Cada faixa colorida é um trecho medido, e os
          pontos clicáveis são os quadros dos {segments.length} segmentos desta página
          {total > segments.length ? ` de ${total}` : ''}.
        </div>
      </Card>

      <div style={s.list} ref={listRef}>
        <Card delay={0.05} style={{ padding: '18px 18px 4px' }}>
          <div style={s.cardHead}>
            <div>
              <div style={s.cardTitle}>Segmentos enviados</div>
              <div style={s.cardHint}>
                Cada segmento foi medido em trechos de cerca de 25 m; abra um para ver os trechos
                dele. A altura é o percentil 95 das células medidas, a partir do solo local de cada
                uma. Marcar um segmento marca os trechos medidos dele, que são os alvos da ordem.
              </div>
            </div>
            <button style={s.orderBtn(chosenSegments.length > 0)}
              disabled={chosenSegments.length === 0}
              onClick={() => setOrdering(true)}>
              <ClipboardList size={13} />
              {chosenSegments.length > 1 ? `Criar OS (${chosenSegments.length})` : 'Criar OS'}
            </button>
          </div>

          {/* Só aparece quando há mais de um nível para separar: numa sessão de dois trechos
              iguais os chips seriam quatro botões que não mudam nada. */}
          {levelsPresent.length > 1 && (
            <div style={s.chipsRow}>
              <button style={s.chip(filterLevel === null && focusIndex === null, 'var(--motiva)')}
                onClick={() => { setFilterLevel(null); setFocusIndex(null) }}>
                Todos <span style={s.chipCount}>{summary?.total ?? 0}</span>
              </button>
              {levelsPresent.map(level => (
                <button key={level} style={s.chip(filterLevel === level && focusIndex === null, LEVELS[level].color)}
                  onClick={() => {
                    setFocusIndex(null)
                    setFilterLevel(filterLevel === level ? null : level)
                  }}>
                  <span style={s.dot(LEVELS[level].color)} />{LEVELS[level].desc}
                  <span style={s.chipCount}>{countOf(level)}</span>
                </button>
              ))}
            </div>
          )}
          {focusIndex !== null && (
            <div style={s.focusBar}>
              <span>
                Mostrando só o segmento <strong style={s.focusIndex}>{focusIndex}</strong>, o do
                ponto que você clicou no mapa.
              </span>
              <button style={s.focusBack}
                onClick={() => {
                  setFocusIndex(null); setOpenIndex(null); setOpenWindow(null); setFocusFrame(null)
                }}>
                ver todos os segmentos
              </button>
            </div>
          )}

          {focusIndex === null && total > PAGE_SIZE && (
            <div style={s.pager}>
              <span style={s.pagerCount}>
                {offset + 1}–{offset + segments.length} de {total}
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

          <table style={s.table}>
            <thead>
              <tr>
                <th style={s.th} /><th style={s.th}>#</th><th style={s.th}>Trechos</th>
                <th style={s.th}>Nível</th>
                <th style={s.th}>Altura p95</th><th style={s.th}>Células</th><th style={s.th}>GPS</th>
                <th style={s.th} />
              </tr>
            </thead>
            <tbody>
              {visibleSegments.map(segment => {
                const open = openIndex === segment.segmentIndex
                const cut = hasWindows(segment)
                return (
                  <Fragment key={segment.segmentIndex}>
                  <tr style={s.clickable(open)}
                    onClick={() => {
                      setOpenIndex(open ? null : segment.segmentIndex)
                      setOpenWindow(null)
                      if (open) setFocusIndex(null)
                      else if (cut) loadWindows(segment.segmentIndex)
                      setFocusFrame(null)
                    }}>
                    <td style={s.td}>
                      {/* Só uma medição pode justificar uma ordem, e a API recusa o resto com
                          409. Desabilitar aqui diz isso antes de alguém tentar. */}
                      <input type="checkbox"
                        disabled={!cut && segment.measurementState == null}
                        checked={chosenOfSegment(segment.segmentIndex) > 0}
                        title={cut ? 'Marca todos os trechos medidos deste segmento' : undefined}
                        onClick={(event) => event.stopPropagation()}
                        onChange={() => toggleSegment(segment)} />
                    </td>
                    <td style={{ ...s.td, ...s.mono }}>{segment.segmentIndex}</td>
                    {/* Quantos trechos o segmento rendeu e quantos deles foram medidos. Um
                        segmento anterior ao corte não tem nenhum e continua sendo a linha
                        inteira. */}
                    <td style={{ ...s.td, ...s.mono }}>
                      {cut ? `${segment.measuredWindowCount ?? 0} de ${segment.windowCount}` : '—'}
                    </td>
                    <td style={s.td}>
                      <span style={s.pill(readingOf(segment))} title={readingOf(segment).title}>
                        {readingOf(segment).label}
                      </span>
                    </td>
                    <td style={{ ...s.td, ...s.mono }}>
                      {segment.measurementExtent95P95M != null
                        ? `${(segment.measurementExtent95P95M * 100).toFixed(0)} cm`
                        : '—'}
                    </td>
                    <td style={s.td}>
                      {segment.measurementCellsMeasured != null
                        ? `${segment.measurementCellsMeasured} medidas · ${segment.measurementCellsAbstained} sem evidência`
                        : '—'}
                    </td>
                    <td style={s.td}>{segment.trackLocationQuality ?? '—'}</td>
                    <td style={{ ...s.td, ...s.chevron }}>
                      {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                    </td>
                  </tr>
                  {open && (
                    <tr>
                      <td style={s.detailCell} colSpan={8}>
                        {/* Um segmento cortado abre nos trechos dele; um medido inteiro abre
                            direto no detalhe, que é o mesmo componente da lista de trechos —
                            abrir daqui responde exatamente o que abrir de lá responde. */}
                        {cut ? (
                          <SegmentWindows
                            rows={windowsBySegment[segment.segmentIndex]}
                            sessionId={sessionId}
                            openWindow={openWindow}
                            onOpenWindow={setOpenWindow}
                            isChosen={isChosen}
                            onToggle={toggleStretch}
                            focusFileName={focusFrame} />
                        ) : (
                          <SegmentDetail segment={{ ...segment, sessionId }}
                            focusFileName={focusFrame} />
                        )}
                      </td>
                    </tr>
                  )}
                  </Fragment>
                )
              })}
            </tbody>
          </table>
        </Card>

      </div>

      <AnimatePresence>
        {ordering && chosenSegments.length > 0 && (
          <NewOrderModal
            segments={chosenSegments.map(segment => ({ ...segment, sessionId }))}
            teams={teams ?? []}
            onCreated={() => setChosen([])}
            onClose={() => setOrdering(false)}
          />
        )}
      </AnimatePresence>
    </PageShell>
  )
}

/**
 * Os trechos de um segmento enviado: as janelas de cerca de 25 m em que ele foi medido.
 *
 * Cada uma tem a própria altura, o próprio nível e as próprias células, e é cada uma delas que
 * vira alvo de uma ordem — uma equipe é mandada a 25 m de margem, não ao segmento inteiro. Abrir
 * um trecho daqui mostra o mesmo detalhe que abrir uma leitura na lista de trechos mostra, porque
 * é o mesmo componente e a mesma linha.
 */
function SegmentWindows({
  rows, sessionId, openWindow, onOpenWindow, isChosen, onToggle, focusFileName,
}) {
  if (rows === undefined) return <div style={s.windowState}>Carregando trechos…</div>
  if (rows.length === 0) {
    return <div style={s.windowState}>Este segmento ainda não publicou trechos medidos.</div>
  }

  return (
    <div style={s.windowBox}>
      <div style={s.windowTitle}>Trechos deste segmento</div>
      <table style={s.table}>
        <thead>
          <tr>
            <th style={s.th} /><th style={s.th}>Trecho</th><th style={s.th}>Nível</th>
            <th style={s.th}>Altura p95</th><th style={s.th}>Células</th><th style={s.th} />
          </tr>
        </thead>
        <tbody>
          {rows.map(row => {
            const reading = readingOf(row)
            const key = windowKeyOf(row.segmentIndex, row.windowIndex)
            const open = openWindow === key
            return (
              <Fragment key={stretchKey({ ...row, sessionId })}>
              <tr style={s.clickable(open)} onClick={() => onOpenWindow(open ? null : key)}>
                <td style={s.td}>
                  <input type="checkbox" disabled={row.measurementState == null}
                    checked={isChosen(row)}
                    onClick={(event) => event.stopPropagation()}
                    onChange={() => onToggle(row)} />
                </td>
                {/* Onde o trecho começa e termina ao longo do caminho da câmera. É o que
                    distingue um trecho do vizinho, que está a vinte e cinco metros dele. */}
                <td style={{ ...s.td, ...s.mono }}>
                  {stretchRange(row) ?? `trecho ${row.windowIndex}`}
                </td>
                <td style={s.td}>
                  <span style={s.pill(reading)} title={reading.title}>{reading.label}</span>
                </td>
                <td style={{ ...s.td, ...s.mono }}>
                  {row.measurementExtent95P95M != null
                    ? `${(row.measurementExtent95P95M * 100).toFixed(0)} cm`
                    : '—'}
                </td>
                <td style={s.td}>
                  {row.measurementCellsMeasured != null
                    ? `${row.measurementCellsMeasured} medidas · ${row.measurementCellsAbstained} sem evidência`
                    : '—'}
                </td>
                <td style={{ ...s.td, ...s.chevron }}>
                  {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                </td>
              </tr>
              {open && (
                <tr>
                  <td style={s.detailCell} colSpan={6}>
                    <SegmentDetail segment={{ ...row, sessionId }} focusFileName={focusFileName} />
                  </td>
                </tr>
              )}
              </Fragment>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
