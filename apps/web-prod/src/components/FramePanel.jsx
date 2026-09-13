import { useEffect, useState } from 'react'
import { AnimatePresence } from 'framer-motion'
import { Maximize2 } from 'lucide-react'
import { sessions } from '../api/greenv'
import Lightbox from './Lightbox'

/**
 * Um quadro, e o que ele mediu.
 *
 * Existia duas vezes: na linha aberta da lista de trechos, com teto de altura e as leituras ao
 * lado, e na tela da sessão, onde era só a foto crua em largura total. As duas respondem a mesma
 * pergunta sobre a mesma coisa, então divergirem era questão de tempo — e divergiram, uma
 * ganhando o bloco de leituras e a outra não. Aqui é um componente só, e a paridade passa a ser
 * de construção em vez de disciplina.
 *
 * A busca das leituras mora aqui junto, porque quem mostra o quadro é quem sabe qual quadro é.
 */
const s = {
  head: { display: 'flex', gap: 12, alignItems: 'flex-start', flexWrap: 'wrap' },
  plate: {
    background: 'var(--bg-secondary)', borderRadius: 'var(--radius-sm)',
    display: 'grid', placeItems: 'center', padding: 8, flex: '0 0 auto',
  },
  // Os quadros saem da câmera em pé, 576 por 1024. O teto é a altura e não a largura, para que
  // um quadro em pé estreite em vez de ganhar tarjas. Trezentos e quarenta pixels é um terço da
  // foto, o que basta para reconhecer o trecho e não para julgar o mato: daí o clique que amplia.
  image: {
    maxWidth: '100%', maxHeight: 340, borderRadius: 'var(--radius-sm)', display: 'block',
    cursor: 'zoom-in',
  },
  plateWrap: { position: 'relative', flex: '0 0 auto' },
  zoomHint: {
    position: 'absolute', right: 14, bottom: 14, width: 28, height: 28,
    display: 'grid', placeItems: 'center', borderRadius: 8, pointerEvents: 'none',
    background: 'rgba(16, 24, 20, 0.62)', color: 'white',
  },
  side: { flex: '1 1 190px', minWidth: 0, display: 'flex', flexDirection: 'column', gap: 8 },
  meta: { fontSize: 11.5, color: 'var(--text-muted)', lineHeight: 1.7 },
  name: { color: 'var(--text-primary)', fontFamily: 'var(--font-mono)' },
  readings: {
    borderTop: '1px solid var(--border)', paddingTop: 8,
    display: 'flex', flexDirection: 'column', gap: 6,
  },
  title: {
    fontSize: 10, fontWeight: 700, color: 'var(--text-muted)',
    textTransform: 'uppercase', letterSpacing: '0.06em',
  },
  row: {
    display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
    fontSize: 11.5, color: 'var(--text-secondary)',
  },
  value: { fontFamily: 'var(--font-mono)', fontWeight: 700, color: 'var(--text-primary)' },
  note: { fontSize: 10.5, color: 'var(--text-muted)', lineHeight: 1.5 },
}

const centimetres = (metres) => (metres != null ? `${(metres * 100).toFixed(0)} cm` : '—')

export default function FramePanel({ frame, sessionId, segmentIndex, showSegment = false }) {
  const [readings, setReadings] = useState(null)
  const [zoomed, setZoomed] = useState(false)

  // Na sessão o quadro traz o próprio trecho, porque a lista atravessa todos eles; no trecho
  // aberto o índice é fixo e vem por propriedade.
  const index = frame?.segmentIndex ?? segmentIndex

  useEffect(() => {
    if (!frame) return undefined
    let live = true
    setReadings(null)
    setZoomed(false)
    sessions.frameReadings(sessionId, index, frame.fileName)
      .then(answer => { if (live) setReadings(answer) })
      .catch(() => { if (live) setReadings({ cellsVoted: 0 }) })
    return () => { live = false }
  }, [frame, sessionId, index])

  if (!frame) return null

  return (
    <div style={s.head}>
      <div style={s.plateWrap}>
        <div style={s.plate}>
          <img
            src={frame.imageUrl}
            alt={frame.fileName}
            style={s.image}
            title="Clique para ampliar"
            onClick={() => setZoomed(true)}
          />
        </div>
        <div style={s.zoomHint}><Maximize2 size={14} /></div>
      </div>
      <div style={s.side}>
        <div style={s.meta}>
          <strong style={s.name}>{frame.fileName}</strong><br />
          {showSegment && <>trecho {index}<br /></>}
          {frame.capturedAtUtc && new Date(frame.capturedAtUtc).toLocaleString('pt-BR')}<br />
          precisão {frame.horizontalAccuracyMeters?.toFixed(1) ?? '?'} m ·{' '}
          {frame.locationQuality ?? 'sem posição'}
        </div>

        <div style={s.readings}>
          <div style={s.title}>O que este quadro mediu</div>
          {readings === null && <div style={s.note}>Carregando…</div>}
          {readings?.cellsVoted === 0 && (
            <div style={s.note}>
              Este quadro não votou em nenhuma célula. Ou não alcançou o piso de voxels em lugar
              nenhum da faixa, ou o pacote desta medição não guardou o detalhe.
            </div>
          )}
          {readings?.cellsVoted > 0 && (
            <>
              <div style={s.row}>
                <span>células votadas</span>
                <span style={s.value}>{readings.cellsVoted}</span>
              </div>
              <div style={s.row}>
                <span>altura mediana</span>
                <span style={s.value}>{centimetres(readings.extent95MedianM)}</span>
              </div>
              <div style={s.row}>
                <span>maior altura</span>
                <span style={s.value}>{centimetres(readings.extent95MaxM)}</span>
              </div>
              {readings.largestDisagreementM != null && (
                <div style={s.row}>
                  <span>maior discordância</span>
                  <span style={s.value}>{centimetres(readings.largestDisagreementM)}</span>
                </div>
              )}
              {readings.evidenceForCells > 0 && (
                <div style={s.note}>
                  Escolhido como evidência em {readings.evidenceForCells}{' '}
                  {readings.evidenceForCells === 1 ? 'célula' : 'células'}.
                </div>
              )}
              <div style={s.note}>
                Altura acima do solo local de cada célula. A discordância é a diferença entre o
                que este quadro votou e o que a célula concluiu com todos os quadros.
              </div>
            </>
          )}
        </div>
      </div>

      <AnimatePresence>
        {zoomed && (
          <Lightbox
            src={frame.imageUrl}
            caption={`${frame.fileName}${showSegment ? ` · trecho ${index}` : ''}`}
            onClose={() => setZoomed(false)}
          />
        )}
      </AnimatePresence>
    </div>
  )
}
