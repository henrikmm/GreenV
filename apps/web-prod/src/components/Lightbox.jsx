import { useEffect } from 'react'
import { createPortal } from 'react-dom'
import { motion } from 'framer-motion'
import { X } from 'lucide-react'

/**
 * Um quadro em tamanho de tela.
 *
 * O painel mostra a foto com teto de 340 pixels, que é o certo lá: a leitura ao lado é tão
 * importante quanto a imagem, e uma foto de celular em pé com altura livre empurrava tudo para
 * fora da dobra. Mas 340 pixels de uma captura 576 por 1024 é um terço dela, e o mato na margem
 * é justamente o detalhe que se quer olhar de perto antes de mandar uma equipe.
 *
 * Então a foto pequena continua sendo a resposta padrão, e esta é a resposta a um pedido: clicar.
 *
 * Renderizado por portal no `body`. Dentro da árvore, a linha aberta da tabela tem `overflow`
 * e transform de animação, e um overlay `position: fixed` ali dentro se ancora no ancestral
 * transformado em vez da janela — apareceria cortado dentro da própria linha.
 */
const s = {
  backdrop: {
    position: 'fixed', inset: 0, zIndex: 1000,
    background: 'rgba(12, 20, 16, 0.88)',
    display: 'grid', placeItems: 'center', padding: 24,
    cursor: 'zoom-out',
  },
  figure: {
    display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10,
    maxHeight: '100%', maxWidth: '100%',
  },
  // `contain` e não `cover`: cortar uma captura em pé para preencher a tela jogaria fora a
  // margem que ela foi tirada para mostrar.
  image: {
    maxWidth: '100%', maxHeight: 'calc(100vh - 110px)', objectFit: 'contain',
    borderRadius: 'var(--radius-md)', display: 'block',
    boxShadow: '0 24px 60px rgba(0,0,0,0.45)',
  },
  caption: {
    color: 'rgba(255,255,255,0.72)', fontSize: 12, textAlign: 'center',
    fontFamily: 'var(--font-mono)',
  },
  close: {
    position: 'fixed', top: 16, right: 18, width: 40, height: 40,
    display: 'grid', placeItems: 'center', cursor: 'pointer',
    borderRadius: '50%', border: '1px solid rgba(255,255,255,0.25)',
    background: 'rgba(255,255,255,0.10)', color: 'white',
  },
}

export default function Lightbox({ src, caption, onClose }) {
  // Esc fecha, e o fundo da página não rola por trás do overlay.
  useEffect(() => {
    const onKey = (event) => { if (event.key === 'Escape') onClose() }
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    window.addEventListener('keydown', onKey)
    return () => {
      document.body.style.overflow = previous
      window.removeEventListener('keydown', onKey)
    }
  }, [onClose])

  return createPortal(
    <motion.div
      key="lightbox"
      style={s.backdrop}
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{ duration: 0.14 }}
      onClick={onClose}
      role="dialog"
      aria-modal="true"
      aria-label={caption ?? 'Quadro ampliado'}
    >
      <button style={s.close} onClick={onClose} aria-label="Fechar">
        <X size={18} />
      </button>
      {/* O clique no fundo fecha; no meio da figura, não. Sem isto, arrastar para olhar um
          detalhe e soltar sobre a foto fecharia a janela. */}
      <figure style={s.figure} onClick={(event) => event.stopPropagation()}>
        <img src={src} alt={caption ?? ''} style={s.image} />
        {caption && <figcaption style={s.caption}>{caption}</figcaption>}
      </figure>
    </motion.div>,
    document.body,
  )
}
