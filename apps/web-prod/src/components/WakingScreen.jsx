import { useEffect, useState } from 'react'

/**
 * A espera enquanto a API acorda.
 *
 * A API escala para zero e uma partida a frio leva de dez a vinte e cinco segundos, medidos. O
 * que aparecia nesse intervalo era a palavra "Verificando sessão" em cinza sobre branco, imóvel,
 * sem nada dizendo que algo ainda estava acontecendo. Uma tela parada por vinte segundos não lê
 * como espera, lê como defeito — e foi assim que foi reportada.
 *
 * Por isso a mensagem muda com o tempo em vez de girar no mesmo lugar. Os primeiros dois segundos
 * cobrem o caso comum, em que a resposta chega antes de valer a pena explicar qualquer coisa. Só
 * depois disso é que a tela admite que está esperando o servidor subir, e aí conta quanto tempo
 * faz, porque um número que anda é a diferença entre esperar e achar que travou.
 */
const s = {
  wrap: {
    minHeight: '100vh', display: 'grid', placeItems: 'center',
    background: 'var(--bg-secondary)', padding: 24,
  },
  card: { textAlign: 'center', maxWidth: 340 },
  bar: {
    width: 180, height: 3, margin: '0 auto 16px', borderRadius: 2,
    background: 'var(--border)', overflow: 'hidden', position: 'relative',
  },
  fill: {
    position: 'absolute', inset: 0, width: '40%', borderRadius: 2,
    background: 'var(--motiva)', animation: 'greenv-waking 1.1s ease-in-out infinite',
  },
  title: { fontSize: 13.5, color: 'var(--text-secondary)', fontWeight: 600 },
  note: { fontSize: 11.5, color: 'var(--text-muted)', marginTop: 6, lineHeight: 1.6 },
  seconds: { fontFamily: 'var(--font-mono)' },
}

const KEYFRAMES = `@keyframes greenv-waking {
  0% { transform: translateX(-100%); }
  100% { transform: translateX(250%); }
}`

export default function WakingScreen() {
  const [seconds, setSeconds] = useState(0)

  useEffect(() => {
    const started = Date.now()
    const timer = setInterval(() => setSeconds(Math.floor((Date.now() - started) / 1000)), 500)
    return () => clearInterval(timer)
  }, [])

  // Antes disso não há nada a explicar: a resposta quente chega em menos de um segundo.
  const waking = seconds >= 2

  return (
    <div style={s.wrap}>
      <style>{KEYFRAMES}</style>
      <div style={s.card}>
        <div style={s.bar}><div style={s.fill} /></div>
        <div style={s.title}>
          {waking ? 'Acordando o servidor…' : 'Verificando sessão…'}
        </div>
        {waking && (
          <div style={s.note}>
            A API desliga quando ninguém a usa e leva alguns segundos para subir.
            <br />
            <span style={s.seconds}>{seconds}s</span> esperando.
          </div>
        )}
      </div>
    </div>
  )
}
