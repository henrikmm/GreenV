import { Polyline } from 'react-leaflet'

/**
 * O caminho inteiro da sessão, costurado a partir dos trechos.
 *
 * Cada trecho é desenhado como uma linha sua, e entre um e o seguinte fica um buraco — o carro
 * andou, o vídeo foi cortado no upload, e nada liga a ponta de um à ponta do outro. Vistos no
 * mapa, sete trechos de dez segundos viram sete riscos soltos e a sessão parece interrompida.
 *
 * Esta linha liga tudo em ordem de captura. Ela é desenhada fina, cinza e tracejada de
 * propósito: **não é medição**. As faixas coloridas continuam sendo o que a grade mediu; isto é
 * só por onde o carro passou. Interpolar a cor de um trecho sobre o vão entre dois seria afirmar
 * uma altura em terreno que ninguém observou.
 */
export default function SessionRoute({ track }) {
  const points = routeOf(track)
  if (points.length < 2) return null
  return (
    <Polyline
      positions={points}
      pathOptions={{ color: '#6b6b80', weight: 2, opacity: 0.55, dashArray: '5 6' }}
      interactive={false}
    />
  )
}

/**
 * Os vértices de todos os trechos, em ordem de captura.
 *
 * Ordena por `segmentIndex` porque a ordem das `Feature` na coleção é a do banco e não a do
 * percurso; ligar fora de ordem desenharia um ziguezague que o carro nunca fez.
 */
export function routeOf(track) {
  const lines = (track?.features ?? [])
    .filter(feature => feature.properties?.kind === 'track')
    .sort((a, b) => (a.properties?.segmentIndex ?? 0) - (b.properties?.segmentIndex ?? 0))
  const points = []
  for (const line of lines) {
    for (const [longitude, latitude] of line.geometry?.coordinates ?? []) {
      points.push([latitude, longitude])
    }
  }
  return points
}
