/**
 * A identidade de um trecho medido, agora que um segmento enviado rende vários.
 *
 * Um **segmento** é o que o telefone enviou: dez segundos de vídeo. O extrator o corta em janelas
 * de cerca de 25 m, cada uma é reconstruída e medida por conta própria, e o **trecho** que a tela
 * mostra — aquele para onde uma equipe é mandada — passou a ser a janela. Uma sessão tem muito
 * mais trechos do que tinha, cada um com a própria altura, o próprio nível e as próprias células.
 *
 * `windowIndex` nulo é a leitura anterior ao corte: o segmento foi medido inteiro. Essas linhas
 * continuam no banco e continuam aparecendo como sempre apareceram.
 */

/**
 * A chave de uma linha: sessão, segmento e janela.
 *
 * Sem a janela, oito trechos vizinhos compartilham chave — mesma sessão, mesmo segmento — e
 * escolher um marcaria todos, abrir um abriria todos, e o React reclamaria de chave repetida.
 */
export function stretchKey(segment) {
  return `${segment?.sessionId}:${segment?.segmentIndex}:${segment?.windowIndex ?? 'inteiro'}`
}

export function sameStretch(one, other) {
  return stretchKey(one) === stretchKey(other)
}

/**
 * Onde o trecho começa e termina ao longo do caminho da câmera, em metros: `0–25 m`.
 *
 * Nulo quando a leitura é do segmento inteiro, ou quando a API não registrou o intervalo — nesses
 * casos não há recorte nenhum a anunciar.
 */
export function stretchRange(segment) {
  if (segment?.windowIndex == null) return null
  const { windowStartMeters: start, windowEndMeters: end } = segment
  if (start == null || end == null) return null
  return `${Math.round(start)}–${Math.round(end)} m`
}

/** Os dois de uma vez, para quem precisa distinguir um do outro: `segmento 4 · 0–25 m`. */
export function stretchLabel(segment) {
  if (segment?.windowIndex == null) return null
  const range = stretchRange(segment)
  return `segmento ${segment.segmentIndex} · ${range ?? `trecho ${segment.windowIndex}`}`
}
