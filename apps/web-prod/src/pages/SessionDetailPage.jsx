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
  // A sessão é finita e já veio inteira, então o filtro é local. Na lista de trechos ele teve
  // de ir para o servidor porque lá a lista é paginada e o navegador só tem uma página.
  const [filterLevel, setFilterLevel] = useState(null)
  const [chosen, setChosen] = useState([])
  const [ordering, setOrdering] = useState(false)

  useEffect(() => {
    let live = true
    setState({ loading: true })
    setOpenIndex(null)
    setFocusFrame(null)
    setFocusIndex(null)
    setChosen([])
    Promise.all([
      sessions.get(sessionId), sessions.segments(sessionId), sessions.track(sessionId),
      // Uma ordem pode ser aberta sem equipe, então a lista falhar não impede o resto.
      teamsApi.list().catch(() => []),
    ])
      .then(async ([session, segments, track, teams]) => {
        const lists = await Promise.all(
          segments.map(segment =>
            sessions.frames(sessionId, segment.segmentIndex)
              .then(list => list.map(frame => ({ ...frame, segmentIndex: segment.segmentIndex })))
              // Um trecho sem manifesto responde 409, o que é uma resposta e não uma falha.
              .catch(() => [])),
        )
        if (live) setState({ loading: false, session, segments, track, teams, frames: lists.flat() })
      })
      .catch(error => { if (live) setState({ loading: false, error }) })
    return () => { live = false }
  }, [sessionId])

  // O lugar vem dos trechos medidos, não do campo de via: aquele era texto livre digitado em
  // campo e não identifica lugar nenhum. A API resolve a rua de cada trecho; uma sessão que
  // cruza mais de uma diz a primeira e conta as outras.
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

  const { session, segments, track, frames, teams } = state
  const measured = segments.filter(segment => segment.measurementState != null)
  // Sem `useMemo`: estas duas linhas ficam depois dos retornos de carregando e de erro, e um
  // hook ali roda em número diferente entre uma renderização e a seguinte, que é erro de React.
  // Uma sessão tem um punhado de trechos, então contar de novo não custa nada.
  const countsByLevel = {}
  for (const segment of segments) {
    const level = vegetationLevel(segment.measurementLevel)
    countsByLevel[level] = (countsByLevel[level] ?? 0) + 1
  }

  const visibleSegments = focusIndex !== null
    ? segments.filter(segment => segment.segmentIndex === focusIndex)
    : filterLevel === null
      ? segments
      : segments.filter(segment => vegetationLevel(segment.measurementLevel) === filterLevel)

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
          }} />
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
          {Object.keys(countsByLevel).length > 1 && (
            <div style={s.chipsRow}>
              <button style={s.chip(filterLevel === null && focusIndex === null, 'var(--motiva)')}
                onClick={() => { setFilterLevel(null); setFocusIndex(null) }}>
                Todos <span style={s.chipCount}>{segments.length}</span>
              </button>
              {[3, 2, 1, 0].filter(level => countsByLevel[level]).map(level => (
                <button key={level} style={s.chip(filterLevel === level && focusIndex === null, LEVELS[level].color)}
                  onClick={() => {
                    setFocusIndex(null)
                    setFilterLevel(filterLevel === level ? null : level)
                  }}>
                  <span style={s.dot(LEVELS[level].color)} />{LEVELS[level].desc}
                  <span style={s.chipCount}>{countsByLevel[level]}</span>
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
