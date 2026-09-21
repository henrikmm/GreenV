import motivaWordmark from '../assets/motiva-wordmark.png'
import motivaMarkWhite from '../assets/motiva-mark-white.png'
import greenvWordmark from '../assets/greenv-wordmark.png'

/**
 * As marcas de verdade, vindas do aplicativo de campo.
 *
 * Até aqui a barra do topo desenhava um broto genérico e escrevia MOTIVA ao lado, que é uma
 * imitação de marca — parecida o bastante para passar e diferente o bastante para não ser a
 * marca. Estes três arquivos vêm do `sprint-cross-plataform`, onde já eram os oficiais.
 *
 * O arquivo roxo é o único horizontal, então a versão branca sai dele por filtro em vez de um
 * segundo arquivo: `brightness(0)` achata tudo para preto e `invert(1)` leva para branco, sem
 * tocar na transparência. O PNG branco empilhado existe separado porque tem outra proporção e
 * serve a outra coisa — uma tela inteira, não uma barra de 52 pixels.
 */
export function MotivaWordmark({ height = 20, tone = 'purple', style }) {
  return (
    <img
      src={motivaWordmark}
      alt="Motiva"
      style={{
        height,
        width: 'auto',
        display: 'block',
        filter: tone === 'white' ? 'brightness(0) invert(1)' : undefined,
        ...style,
      }}
    />
  )
}

/** A marca empilhada, branca. Feita para ocupar espaço: abertura, espera, tela cheia. */
export function MotivaMark({ size = 120, style }) {
  return <img src={motivaMarkWhite} alt="Motiva" style={{ width: size, height: 'auto', display: 'block', ...style }} />
}

/** A assinatura do produto. Verde, e por isso nunca sobre o roxo. */
export function GreenVWordmark({ height = 26, style }) {
  return <img src={greenvWordmark} alt="GreenV" style={{ height, width: 'auto', display: 'block', ...style }} />
}
