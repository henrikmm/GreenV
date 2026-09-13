import { Fragment, useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { AnimatePresence } from 'framer-motion'
import { ArrowLeft, ClipboardList, ChevronRight, ChevronDown } from 'lucide-react'
import { LEVELS, vegetationLevel, Card } from '@greenv/web-core'
import { sessions, teams as teamsApi } from '../api/greenv'
import { placeOfSegment, placeOfSession } from '../api/place'
import { readingOf } from '../api/reading'
import NewOrderModal from '../components/NewOrderModal'
import SessionMap from '../components/SessionMap'
import SegmentDetail from '../components/SegmentDetail'
import PageShell from '../components/PageShell'

/**
 * Uma sessão: por onde passou, o que mediu, e a foto de cada ponto.
 *
 * O mapa ocupa a largura toda e a lista de trechos fica ao lado do quadro selecionado, que é o
 * mesmo arranjo que a tela de mapa da demonstração usa.
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
  list: { marginTop: 12 },
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

export default function SessionDetailPage() {
  const { sessionId } = useParams()
  const navigate = useNavigate()
  const [state, setState] = useState({ loading: true })
  // Um trecho aberto por vez, como na lista de trechos, e o quadro que o abriu.
  const [openIndex, setOpenIndex] = useState(null)
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
  const [chosen, setChosen] = useState([])
  const [ordering, setOrdering] = useState(false)

  useEffect(() => {
    let live = true
    setState({ loading: true })
    setOpenIndex(null)
    setFocusFrame(null)
    setFocusIndex(null)
    setFocusSegment(null)
    setOffset(0)
    setChosen([])
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
  const measured = segments.filter(segment => segment.measurementState != null)
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

  const chosenSegments = measured.filter(segment => chosen.includes(segment.segmentIndex))
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
          {new Date(session.startedAt).toLocaleString('pt-BR')} · {session.segmentCount} trechos,
          {' '}{session.measuredSegmentCount} medidos · {frames.length} quadros publicados
        </div>
      </div>

      <Card style={{ padding: 12 }}>
        {/* Clicar num ponto abre o trecho a que ele pertence, já naquele quadro. O painel
            lateral que existia aqui mostrava a foto longe da leitura que a explica; dentro do
            trecho ela aparece junto da altura, da cobertura e dos quadros vizinhos. */}
        <SessionMap track={track} frames={frames} height={440}
          onFrameClick={(frame) => {
            setOpenIndex(frame.segmentIndex)
            setFocusFrame(frame.fileName)
            setFocusIndex(frame.segmentIndex)
            const naPagina = segments.find(s => s.segmentIndex === frame.segmentIndex)
            setFocusSegment(naPagina ?? null)
            if (!naPagina) {
              sessions.segment(sessionId, frame.segmentIndex)
                .then(setFocusSegment)
                .catch(() => setFocusIndex(null))
            }
          }} />
        {/* A linha é a sessão inteira; os pontos são só os trechos listados abaixo. Buscar os
            quadros de todos eles seria uma chamada por trecho, que é o que esta tela deixou de
            fazer. Dizer isso é melhor do que deixar a pessoa concluir que a captura acabou
            onde os pontos acabam. */}
        <div style={s.mapNote}>
          A linha é o caminho inteiro da sessão. Os pontos clicáveis são os quadros dos{' '}
          {segments.length} trechos desta página{total > segments.length ? ` de ${total}` : ''}.
        </div>
      </Card>

      <div style={s.list}>
        <Card delay={0.05} style={{ padding: '18px 18px 4px' }}>
          <div style={s.cardHead}>
            <div>
              <div style={s.cardTitle}>Trechos</div>
              <div style={s.cardHint}>
                A altura é o percentil 95 das células medidas, a partir do solo local de cada uma.
                Marque um ou mais para abrir uma ordem.
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
                Mostrando só o trecho <strong style={s.focusIndex}>{focusIndex}</strong>, o do
                ponto que você clicou no mapa.
              </span>
              <button style={s.focusBack}
                onClick={() => { setFocusIndex(null); setOpenIndex(null); setFocusFrame(null) }}>
                ver todos os trechos
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
                <th style={s.th} /><th style={s.th}>#</th><th style={s.th}>Nível</th>
                <th style={s.th}>Altura p95</th><th style={s.th}>Células</th><th style={s.th}>GPS</th>
                <th style={s.th} />
              </tr>
            </thead>
            <tbody>
              {visibleSegments.map(segment => {
                const level = vegetationLevel(segment.measurementLevel)
                const open = openIndex === segment.segmentIndex
                return (
                  <Fragment key={segment.segmentIndex}>
                  <tr style={s.clickable(open)}
                    onClick={() => {
                      setOpenIndex(open ? null : segment.segmentIndex)
                      if (open) setFocusIndex(null)
                      setFocusFrame(null)
                    }}>
                    <td style={s.td}>
                      {/* Só um trecho medido pode justificar uma ordem, e a API recusa o resto
                          com 409. Desabilitar aqui diz isso antes de alguém tentar. */}
                      <input type="checkbox" disabled={segment.measurementState == null}
                        checked={chosen.includes(segment.segmentIndex)}
                        onClick={(event) => event.stopPropagation()}
                        onChange={() => setChosen(previous => previous.includes(segment.segmentIndex)
                          ? previous.filter(index => index !== segment.segmentIndex)
                          : [...previous, segment.segmentIndex])} />
                    </td>
                    <td style={{ ...s.td, ...s.mono }}>{segment.segmentIndex}</td>
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
                      <td style={s.detailCell} colSpan={7}>
                        {/* O mesmo componente da lista de trechos, para que abrir um trecho
                            daqui responda exatamente o que abrir de lá responde. */}
                        <SegmentDetail segment={{ ...segment, sessionId }}
                          focusFileName={focusFrame} />
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
