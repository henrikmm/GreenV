import { motion } from 'framer-motion'
import { Layers, Filter, ListTree, Maximize2 } from 'lucide-react'
import { LEVELS, vegetationLevel } from '@greenv/web-core'
import { stretchKey, stretchLabel, stretchRange } from '../api/stretch'

/**
 * A barra lateral do mapa, no mesmo vocabulário da demonstração.
 *
 * A demo resume 642 polígonos por nível, liga e desliga camadas e filtra por altura. Aqui o
 * resumo é por trecho medido, as camadas são as duas que a API desenha, e no lugar da lista de
 * polígonos vem a lista de sessões — que é o que existe para escolher.
 */
const s = {
  sidebar: {
    width: 300, height: '100%', background: 'white', borderRight: '1px solid var(--border)',
    display: 'flex', flexDirection: 'column', overflow: 'hidden', flexShrink: 0,
  },
  body: { flex: 1, overflowY: 'auto', padding: '16px 20px' },
  section: { marginBottom: 22 },
  sectionTitle: {
    display: 'flex', alignItems: 'center', gap: 6, fontSize: 10, fontWeight: 700,
    color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.08em',
    marginBottom: 10,
  },
  statsGrid: { display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 8 },
  statBox: (c) => ({
    padding: '10px 8px', background: 'var(--bg-secondary)', borderRadius: 'var(--radius-sm)',
    textAlign: 'center', borderLeft: `3px solid ${c}`,
  }),
  statValue: { fontSize: 20, fontWeight: 700, fontFamily: 'var(--font-mono)' },
  statLabel: {
    fontSize: 9, color: 'var(--text-muted)', marginTop: 2,
    textTransform: 'uppercase', letterSpacing: '0.05em',
  },
  toggle: {
    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    padding: '10px 12px', background: 'var(--bg-secondary)', borderRadius: 'var(--radius-sm)',
    marginBottom: 6, cursor: 'pointer', border: '1px solid var(--border)',
  },
  toggleLabel: { fontSize: 13, fontWeight: 500 },
  sw: (on) => ({
    width: 36, height: 20, borderRadius: 10, position: 'relative',
    background: on ? 'var(--motiva)' : '#ccc', transition: 'background 0.2s', flexShrink: 0,
  }),
  swDot: (on) => ({
    width: 16, height: 16, borderRadius: '50%', background: 'white',
    position: 'absolute', top: 2, left: on ? 18 : 2, transition: 'left 0.2s',
  }),
  chip: (active, color) => ({
    display: 'inline-flex', alignItems: 'center', gap: 5, padding: '5px 12px', borderRadius: 20,
    fontSize: 12, fontWeight: 600, cursor: 'pointer', marginRight: 6, marginBottom: 6,
    fontFamily: 'inherit',
    border: `1.5px solid ${active ? color : 'var(--border)'}`,
    background: active ? `${color}15` : 'transparent',
    color: active ? color : 'var(--text-secondary)',
  }),
  dot: (c) => ({ width: 8, height: 8, borderRadius: '50%', background: c }),
  card: (active) => ({
    padding: '11px 13px', borderRadius: 'var(--radius-sm)', marginBottom: 8, cursor: 'pointer',
    border: `1px solid ${active ? 'var(--motiva)' : 'var(--border)'}`,
    background: active ? 'var(--motiva-subtle)' : 'var(--bg-secondary)',
  }),
  cardRoad: { fontSize: 13, fontWeight: 600, color: 'var(--text-primary)' },
  cardWhen: { fontSize: 11, color: 'var(--text-muted)', marginTop: 2 },
  cardRow: { display: 'flex', alignItems: 'center', gap: 8, marginTop: 8 },
  pill: (level) => ({
    display: 'inline-flex', padding: '2px 9px', borderRadius: 20, fontSize: 10.5, fontWeight: 600,
    color: LEVELS[level].color, background: LEVELS[level].bg,
  }),
  open: {
    marginLeft: 'auto', fontSize: 11, fontWeight: 700, color: 'var(--motiva)',
    background: 'none', border: 'none', cursor: 'pointer', fontFamily: 'inherit', padding: 0,
  },
  state: { fontSize: 12.5, color: 'var(--text-muted)', lineHeight: 1.6 },
  frameBtn: {
    width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
    padding: '11px 16px', borderRadius: 'var(--radius-sm)', fontSize: 13, fontWeight: 700,
    cursor: 'pointer', fontFamily: 'inherit', border: '1.5px solid var(--motiva)',
    background: 'white', color: 'var(--motiva)',
  },
  frameHint: { fontSize: 11.5, color: 'var(--text-muted)', marginTop: 8, lineHeight: 1.5 },
  stretchList: { marginTop: 10, borderTop: '1px solid var(--border)', paddingTop: 8 },
  stretchRow: (active) => ({
    display: 'flex', alignItems: 'center', gap: 7, padding: '5px 6px', borderRadius: 6,
    cursor: 'pointer', background: active ? 'var(--motiva-subtle)' : 'transparent',
  }),
  stretchDot: (colour) => ({
    width: 7, height: 7, borderRadius: '50%', background: colour, flexShrink: 0,
  }),
  stretchName: {
    fontSize: 11.5, flex: 1, color: 'var(--text-secondary)', whiteSpace: 'nowrap',
    overflow: 'hidden', textOverflow: 'ellipsis',
  },
  stretchHeight: { fontSize: 11, fontWeight: 700, fontFamily: 'var(--font-mono)' },
}

/** Quantos trechos de uma sessão a barra lista antes de resumir o resto. */
const SHOWN_STRETCHES = 8

function Toggle({ label, active, onToggle }) {
  return (
    <div style={s.toggle} onClick={onToggle}>
      <span style={s.toggleLabel}>{label}</span>
      <div style={s.sw(active)}><div style={s.swDot(active)} /></div>
    </div>
  )
}

export default function SessionsSidebar({
  entries = [], counts, selectedId, onSelect, onOpen, onOpenSegment,
  layers, onToggleLayer, filterLevel, onFilterLevel,
  loading, error,
}) {
  const drawableCount = entries.filter(entry => entry.drawable).length

  return (
    <div style={s.sidebar}>
      <div style={s.body}>
        <div style={s.section}>
          {/* Cada sessão está num lugar diferente, então voltar a ver todas de uma vez é a ação
              que esta tela mais precisa — o equivalente ao "Planejar Rota" da demonstração. */}
          <motion.button style={s.frameBtn} onClick={() => onSelect(null)} whileTap={{ scale: 0.97 }}>
            <Maximize2 size={15} /> Enquadrar tudo
          </motion.button>
          <div style={s.frameHint}>
            {drawableCount} de {entries.length} {entries.length === 1 ? 'sessão tem' : 'sessões têm'}
            {' '}trilha desenhável. Clique numa para enquadrá-la sozinha.
          </div>
        </div>

        <div style={s.section}>
          <div style={s.sectionTitle}>Resumo — trechos medidos</div>
          <div style={s.statsGrid}>
            {[1, 2, 3].map(level => (
              <div key={level} style={s.statBox(LEVELS[level].color)}>
                <div style={{ ...s.statValue, color: LEVELS[level].color }}>{counts?.[level] ?? 0}</div>
                <div style={s.statLabel}>Nível {level}</div>
              </div>
            ))}
          </div>
        </div>

        <div style={s.section}>
          <div style={s.sectionTitle}><Layers size={12} /> Camadas</div>
          <Toggle label="Trilha da câmera" active={layers.track} onToggle={() => onToggleLayer('track')} />
          <Toggle label="Faixa medida (5 m)" active={layers.band} onToggle={() => onToggleLayer('band')} />
        </div>

        <div style={s.section}>
          <div style={s.sectionTitle}><Filter size={12} /> Filtrar por nível</div>
          <button style={s.chip(filterLevel === null, 'var(--motiva)')} onClick={() => onFilterLevel(null)}>
            Todos
          </button>
          {[0, 1, 2, 3].map(level => (
            <button key={level} style={s.chip(filterLevel === level, LEVELS[level].color)}
              onClick={() => onFilterLevel(filterLevel === level ? null : level)}>
              <span style={s.dot(LEVELS[level].color)} />{LEVELS[level].desc}
            </button>
          ))}
        </div>

        <div style={s.section}>
          <div style={s.sectionTitle}><ListTree size={12} /> Sessões</div>
          {loading && <div style={s.state}>Carregando sessões…</div>}
          {error && <div style={{ ...s.state, color: LEVELS[3].color }}>{error.message}</div>}
          {!loading && entries.length === 0 && <div style={s.state}>Nenhuma sessão capturada ainda.</div>}

          {entries.map(({ session, track, worst, drawable, place, stretches }) => (
            <motion.div key={session.sessionId} whileTap={{ scale: 0.99 }}
              style={s.card(selectedId === session.sessionId)}
              onClick={() => onSelect(drawable ? session.sessionId : null)}>
              <div style={s.cardRoad}>{place?.label ?? 'Sem posição registrada'}</div>
              <div style={s.cardWhen}>
                {place?.detail && <>{place.detail}<br /></>}
                {new Date(session.startedAt).toLocaleString('pt-BR')}
              </div>
              <div style={s.cardRow}>
                <span style={s.pill(worst)}>{LEVELS[worst].label}</span>
                <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                  {session.measuredSegmentCount} de {session.segmentCount}
                </span>
                <button style={s.open}
                  onClick={(event) => { event.stopPropagation(); onOpen(session.sessionId) }}>
                  abrir →
                </button>
              </div>
              {!drawable && <div style={{ ...s.cardWhen, marginTop: 6 }}>sem trilha desenhável</div>}

              {/* Os trechos dentro da sessão. Uma volta cruza ruas e mistura um trecho
                  limpo com um crítico, e o rótulo da sessão sozinho esconde justamente
                  isso. Aqui cada leitura aparece com a própria rua e a própria altura. */}
              {/* Os mais altos primeiro, e só os primeiros: um segmento enviado rende vários
                  trechos de cerca de 25 m, e uma sessão inteira não cabe numa barra de 300 px.
                  O que a lista responde é para onde mandar a equipe, e isso está no topo. */}
              {stretches?.length > 0 && (
                <div style={s.stretchList}>
                  {stretches.slice(0, SHOWN_STRETCHES).map(({ segment, place: spot }) => {
                    const level = vegetationLevel(segment.measurementLevel)
                    return (
                      // A janela entra na chave: um segmento enviado rende vários trechos
                      // medidos, e o índice do segmento sozinho repete entre eles.
                      <div key={stretchKey(segment)}
                        style={s.stretchRow(selectedId === session.sessionId)}
                        title={[spot?.detail, stretchLabel(segment)].filter(Boolean).join(' · ')
                          || undefined}
                        onClick={(event) => {
                          event.stopPropagation()
                          onOpenSegment?.(session.sessionId, segment.segmentIndex)
                        }}>
                        <span style={s.stretchDot(LEVELS[level].color)} />
                        <span style={s.stretchName}>
                          {spot?.label ?? `Trecho ${segment.segmentIndex}`}
                          {stretchRange(segment) && <> · {stretchRange(segment)}</>}
                        </span>
                        <span style={{ ...s.stretchHeight, color: LEVELS[level].color }}>
                          {segment.measurementExtent95P95M != null
                            ? `${(segment.measurementExtent95P95M * 100).toFixed(0)} cm`
                            : '—'}
                        </span>
                      </div>
                    )
                  })}
                  {stretches.length > SHOWN_STRETCHES && (
                    <div style={{ ...s.cardWhen, marginTop: 6 }}>
                      e mais {stretches.length - SHOWN_STRETCHES} trechos medidos
                    </div>
                  )}
                </div>
              )}
              {drawable && (() => {
                const drawnSegments = track.features.filter(f => f.properties?.kind === 'track').length
                return (
                  <div style={{ ...s.cardWhen, marginTop: 4 }}>
                    {drawnSegments} {drawnSegments === 1 ? 'trecho' : 'trechos'} no mapa
                  </div>
                )
              })()}
            </motion.div>
          ))}
        </div>
      </div>
    </div>
  )
}
