import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Card, Badge, LEVELS, vegetationLevel } from '@greenv/web-core'
import { sessions } from '../api/greenv'
import NotReadyNotice from '../components/NotReadyNotice'

/**
 * Por onde começar.
 *
 * Esta tela não existe na demo, e é a primeira coisa que a versão real precisa: até haver uma
 * rota de listagem na API, não havia como descobrir o que tinha sido capturado sem já ter o
 * identificador em mãos.
 */
export default function SessionsPage() {
  const [state, setState] = useState({ loading: true })
  const [measuredOnly, setMeasuredOnly] = useState(false)

  useEffect(() => {
    let live = true
    setState({ loading: true })
    sessions.list({ measuredOnly, limit: 50 })
      .then(page => { if (live) setState({ loading: false, page }) })
      .catch(error => { if (live) setState({ loading: false, error }) })
    return () => { live = false }
  }, [measuredOnly])

  return (
    <div style={{ padding: 24, display: 'grid', gap: 16 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <h1 style={{ fontSize: 22, margin: 0 }}>Sessões de captura</h1>
        <label style={{ fontSize: 13, display: 'flex', gap: 6, alignItems: 'center' }}>
          <input type="checkbox" checked={measuredOnly} onChange={e => setMeasuredOnly(e.target.checked)} />
          Só com medição
        </label>
      </div>

      <NotReadyNotice />

      {state.loading && <p style={{ color: 'var(--text-muted)' }}>Carregando…</p>}
      {state.error && <p style={{ color: '#dc2626' }}>{state.error.message}</p>}

      {state.page && (
        <Card>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ textAlign: 'left', color: 'var(--text-muted)' }}>
                <th style={th}>Início</th><th style={th}>Via</th><th style={th}>Dispositivo</th>
                <th style={th}>Segmentos</th><th style={th}>Medidos</th><th style={th}></th>
              </tr>
            </thead>
            <tbody>
              {state.page.items.map(session => (
                <tr key={session.sessionId} style={{ borderTop: '1px solid var(--border)' }}>
                  <td style={td}>{new Date(session.startedAt).toLocaleString('pt-BR')}</td>
                  <td style={td}>
                    {session.rodovia
                      ? `${session.rodovia}${session.sentido ? ' · ' + session.sentido : ''}`
                      : <span style={{ color: 'var(--text-muted)' }}>não identificada</span>}
                  </td>
                  <td style={{ ...td, fontFamily: 'monospace', fontSize: 11 }}>{session.deviceId}</td>
                  <td style={td}>{session.segmentCount}</td>
                  <td style={td}>
                    <Badge
                      color={session.measuredSegmentCount > 0 ? LEVELS[1].color : LEVELS[0].color}
                      bg={session.measuredSegmentCount > 0 ? LEVELS[1].bg : LEVELS[0].bg}>
                      {session.measuredSegmentCount} de {session.segmentCount}
                    </Badge>
                  </td>
                  <td style={td}>
                    <Link to={`/sessoes/${session.sessionId}`} style={{ color: 'var(--motiva)' }}>Abrir mapa</Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {state.page.items.length === 0 && (
            <p style={{ color: 'var(--text-muted)', fontSize: 13, marginTop: 12 }}>
              Nenhuma sessão {measuredOnly ? 'com medição ' : ''}encontrada.
            </p>
          )}
          <p style={{ color: 'var(--text-muted)', fontSize: 12, marginTop: 12 }}>
            {state.page.total} sessões no total.
          </p>
        </Card>
      )}
    </div>
  )
}

const th = { padding: '6px 8px', fontWeight: 500 }
const td = { padding: '8px' }
