import { useEffect, useState } from 'react'
import { Download } from 'lucide-react'
import { LEVELS, vegetationLevel, useToast } from '@greenv/web-core'
import { sessions, saveBlob } from '../api/greenv'
import SessionMap from './SessionMap'
import FramePanel from './FramePanel'

/**
 * Um trecho aberto dentro da lista: só a trilha dele, e as fotos dele.
 *
 * Antes o único caminho para ver um quadro era abrir a sessão inteira, o que custa a posição na
 * lista e devolve oito trechos quando a pergunta era sobre um. Aqui a trilha vem filtrada pelo
 * `segmentIndex` e os quadros vêm da rota do próprio trecho, então o que aparece é o que a
 * linha prometeu.
 *
 * A trilha da sessão é buscada uma vez e reaproveitada: abrir três trechos da mesma volta não
 * pede três vezes o mesmo GeoJSON.
 */
const s = {
  // Os dois lados esticam juntos: a grade já iguala a altura das colunas, e o mapa ocupa o que
  // sobra da sua. Com altura fixa ele virava uma tira de 1031 por 260 numa tela larga, com um
  // palmo de branco embaixo, enquanto a foto ao lado descia três vezes mais.
  panel: {
    display: 'grid', gridTemplateColumns: 'minmax(0, 3fr) minmax(260px, 2fr)',
    gap: 14, padding: '4px 0 14px', alignItems: 'stretch',
  },
  mapColumn: { display: 'flex', flexDirection: 'column', minWidth: 0 },
  mapCard: { flex: 1, minHeight: 260 },
  card: {
    background: 'white', border: '1px solid var(--border)', borderRadius: 'var(--radius-md)',
    overflow: 'hidden',
  },
  frameBox: { padding: 12, display: 'flex', flexDirection: 'column', gap: 10 },
  strip: { display: 'flex', gap: 6, overflowX: 'auto', paddingBottom: 4 },
  thumb: (active) => ({
    width: 74, height: 50, flexShrink: 0, borderRadius: 6, objectFit: 'cover', cursor: 'pointer',
    border: `2px solid ${active ? 'var(--motiva)' : 'transparent'}`, background: 'var(--bg-secondary)',
  }),
  state: { padding: 24, fontSize: 12.5, color: 'var(--text-muted)', textAlign: 'center' },
  summary: {
    display: 'flex', gap: 16, padding: '10px 12px', background: 'var(--bg-secondary)',
    borderRadius: 'var(--radius-sm)', fontSize: 11.5, color: 'var(--text-secondary)',
    marginBottom: 10, flexWrap: 'wrap',
  },
  summaryValue: { fontFamily: 'var(--font-mono)', fontWeight: 700, color: 'var(--text-primary)' },
  // Empurrados para a direita da mesma faixa: são sobre este trecho, como o resto dela, mas são
  // ação e não leitura.
  downloads: { display: 'flex', gap: 6, marginLeft: 'auto' },
  download: (busy) => ({
    display: 'inline-flex', alignItems: 'center', gap: 5, padding: '4px 9px',
    fontSize: 11, fontWeight: 700, fontFamily: 'inherit', borderRadius: 'var(--radius-sm)',
    border: '1px solid var(--border)', background: 'white',
    color: busy ? 'var(--text-muted)' : 'var(--motiva)',
    cursor: busy ? 'progress' : 'pointer',
  }),
}

/**
 * Os dois arquivos que o serviço de profundidade deixa ao lado dos quadros, guardados desde que
 * o handler passou a não descartá-los — é deles que uma remedição parte sem acordar a GPU, e é
 * neles que alguém abre a nuvem de pontos por conta própria. Os tamanhos são a ordem de grandeza
 * de um trecho de 102 quadros de 13 de setembro de 2026, não uma promessa.
 */
const DEPTH_FILES = [
  { name: 'scene.glb', label: 'malha .glb', hint: 'A cena reconstruída, para abrir num visualizador 3D (~15 MB)' },
  { name: 'result.npz', label: 'nuvem .npz', hint: 'Os arrays de profundidade de onde a nuvem de pontos é remontada (~50 MB)' },
]

const trackCache = new Map()

function loadTrack(sessionId) {
  if (!trackCache.has(sessionId)) {
    trackCache.set(sessionId, sessions.track(sessionId).catch(() => null))
  }
  return trackCache.get(sessionId)
}

/**
 * @param focusFileName o quadro a abrir já selecionado, quando quem abriu o trecho veio de um
 *   ponto do mapa da sessão. Sem isso o painel escolheria o primeiro quadro e a pessoa teria de
 *   reencontrar no mapa pequeno o ponto que acabou de clicar no grande.
 */
export default function SegmentDetail({ segment, place, focusFileName }) {
  const [state, setState] = useState({ loading: true })
  const [selected, setSelected] = useState(null)
  const [downloading, setDownloading] = useState(null)
  const { addToast } = useToast()

  async function download(fileName) {
    setDownloading(fileName)
    try {
      const blob = await sessions.depthArtifact(segment.sessionId, segment.segmentIndex, fileName)
      saveBlob(blob, `${segment.sessionId.slice(0, 8)}-segmento-${segment.segmentIndex}-${fileName}`)
    } catch (failure) {
      addToast({
        type: 'danger',
        message: failure.status === 409
          ? 'Este trecho não guardou a reconstrução.'
          : failure.message || 'Não foi possível baixar o arquivo.',
      })
    } finally {
      setDownloading(null)
    }
  }

  useEffect(() => {
    let live = true
    setState({ loading: true })
    setSelected(null)
    Promise.all([
      loadTrack(segment.sessionId),
      // Um trecho sem manifesto responde 409, o que é uma resposta e não uma falha.
      sessions.frames(segment.sessionId, segment.segmentIndex).catch(() => []),
    ])
      .then(([track, frames]) => {
        if (!live) return
        const onlyThis = track
          ? { ...track, features: track.features.filter(f => f.properties?.segmentIndex === segment.segmentIndex) }
          : null
        setState({ loading: false, track: onlyThis, frames })
        setSelected(
          (focusFileName && frames.find(frame => frame.fileName === focusFileName))
            ?? frames.find(frame => frame.latitude != null)
            ?? frames[0]
            ?? null)
      })
      .catch(error => { if (live) setState({ loading: false, error }) })
    return () => { live = false }
  }, [segment.sessionId, segment.segmentIndex, focusFileName])

  const { track, frames, loading, error } = state
  const level = vegetationLevel(segment.measurementLevel)

  if (loading) return <div style={s.state}>Carregando trecho…</div>
  if (error) return <div style={{ ...s.state, color: LEVELS[3].color }}>{error.message}</div>

  return (
    <div style={s.panel}>
      <div style={s.mapColumn}>
        <div style={s.summary}>
          <span>altura p90 <span style={{ ...s.summaryValue, color: LEVELS[level].color }}>
            {segment.measurementExtent95P95M != null
              ? `${(segment.measurementExtent95P95M * 100).toFixed(0)} cm`
              : '—'}
          </span></span>
          <span>máxima <span style={s.summaryValue}>
            {segment.measurementExtent95MaxM != null
              ? `${(segment.measurementExtent95MaxM * 100).toFixed(0)} cm`
              : '—'}
          </span></span>
          <span>cobertura <span style={s.summaryValue}>
            {segment.measurementCoverage != null
              ? `${(segment.measurementCoverage * 100).toFixed(0)}%`
              : '—'}
          </span></span>
          <span>quadros <span style={s.summaryValue}>{frames?.length ?? 0}</span></span>
          <div style={s.downloads}>
            {DEPTH_FILES.map(file => (
              <button
                key={file.name}
                style={s.download(downloading === file.name)}
                disabled={downloading !== null}
                title={file.hint}
                onClick={() => download(file.name)}
              >
                <Download size={12} />
                {downloading === file.name ? 'baixando…' : file.label}
              </button>
            ))}
          </div>
        </div>
        <div style={{ ...s.card, ...s.mapCard }}>
          <SessionMap track={track} frames={frames} onFrameClick={setSelected} height="100%" />
        </div>
      </div>

      <div style={{ ...s.card, ...s.frameBox }}>
        {selected ? (
          <FramePanel frame={selected} sessionId={segment.sessionId}
            segmentIndex={segment.segmentIndex} />
        ) : (
          <div style={s.state}>Este trecho não publicou quadros.</div>
        )}

        {frames?.length > 1 && (
          <div style={s.strip}>
            {frames.map(frame => (
              <img
                key={frame.fileName}
                src={frame.imageUrl}
                alt={frame.fileName}
                loading="lazy"
                title={frame.fileName}
                style={s.thumb(selected?.fileName === frame.fileName)}
                onClick={() => setSelected(frame)}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
