// Horizonte de grama decorativo — balançando via CSS, sem custo de layout Leaflet.
// Duas camadas (fundo mais baixo/apagado, frente mais alta/opaca) pra parecer campo denso.
function makeBlades(count, { hMin, hMax, opacity, seedOffset }) {
  return Array.from({ length: count }, (_, i) => {
    const jitter = ((i * 53 + seedOffset * 17) % 11) / 11 // 0..1 determinístico
    return {
      x: (i / (count - 1)) * 100 + (jitter - 0.5) * (100 / count) * 0.8,
      h: hMin + ((i * 37 + seedOffset) % (hMax - hMin + 1)),
      lean: ((i * 29 + seedOffset) % 9) - 4, // -4..4, inclinação de repouso
      delay: (i % 9) * 0.14,
      dur: 2.2 + (i % 6) * 0.25,
      width: 0.55 + ((i * 13) % 5) * 0.08,
      opacity,
      color: i % 5 === 0 ? '#5ee6c0' : i % 3 === 0 ? '#c7b8f5' : '#9c7ef0',
    }
  })
}

const BACK = makeBlades(34, { hMin: 5, hMax: 9, opacity: 0.32, seedOffset: 3 })
const FRONT = makeBlades(46, { hMin: 8, hMax: 15, opacity: 0.55, seedOffset: 11 })

function Blade({ b }) {
  return (
    <path
      d={`M ${b.x} 16 Q ${b.x - 0.7 + b.lean * 0.1} ${16 - b.h * 0.6}, ${b.x + 0.35 + b.lean * 0.15} ${16 - b.h}`}
      stroke={b.color} strokeWidth={b.width} strokeLinecap="round" fill="none" opacity={b.opacity}
      className="grass-blade"
      style={{
        animationDelay: `${b.delay}s`, animationDuration: `${b.dur}s`,
        transformOrigin: '50% 100%', transformBox: 'fill-box',
      }}
    />
  )
}

export default function GrassHorizon({ style }) {
  return (
    <svg
      viewBox="0 0 100 16" preserveAspectRatio="none"
      style={{ position: 'absolute', left: 0, right: 0, bottom: 0, width: '100%', height: 44, ...style }}
    >
      {BACK.map((b, i) => <Blade key={`b${i}`} b={b} />)}
      {FRONT.map((b, i) => <Blade key={`f${i}`} b={b} />)}
    </svg>
  )
}
