import { useEffect, useState } from 'react'

/**
 * Contagem animada simples (easing próprio, sem depender de motion values) — usada nos KPIs.
 *
 * O atalho para a aba em segundo plano não é enfeite. A contagem começa em zero e só chega ao
 * valor por `requestAnimationFrame`, que o navegador não dispara numa aba que não está visível.
 * Sem isto, abrir o painel numa aba de fundo — um Ctrl+clique, uma sessão restaurada com várias
 * abas — deixa todo indicador parado em 0 até alguém focar a aba. E 0 não é um número neutro
 * aqui: lê-se como "nenhuma sessão capturada" e "nenhum trecho acima de 30 cm", ao lado de uma
 * tabela que mostra o contrário.
 */
export default function AnimatedNumber({ value, decimals = 0, suffix = '' }) {
  const [display, setDisplay] = useState(0)

  useEffect(() => {
    if (typeof document !== 'undefined' && document.hidden) {
      setDisplay(value)
      return undefined
    }

    let raf
    const start = performance.now()
    const duration = 900
    const tick = (t) => {
      const p = Math.min((t - start) / duration, 1)
      const eased = 1 - Math.pow(1 - p, 3)
      setDisplay(value * eased)
      if (p < 1) raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)

    // Esconder a aba no meio da contagem congela o rAF onde ele estava, num valor intermediário
    // que é simplesmente errado. Pular para o fim é a única leitura honesta.
    const onVisibilityChange = () => {
      if (document.hidden) {
        cancelAnimationFrame(raf)
        setDisplay(value)
      }
    }
    document.addEventListener('visibilitychange', onVisibilityChange)

    return () => {
      cancelAnimationFrame(raf)
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }, [value])

  return <>{display.toLocaleString('pt-BR', { minimumFractionDigits: decimals, maximumFractionDigits: decimals })}{suffix}</>
}
