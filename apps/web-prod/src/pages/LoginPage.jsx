import { useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { useAuth, GrassHorizon } from '@greenv/web-core'

/**
 * Entrar de verdade.
 *
 * A diferença visível em relação à demo é a ausência dos botões de acesso rápido: não há
 * credencial para exibir, porque a senha nunca esteve no cliente. A primeira conta é criada com
 * o token estático pela rota de identidade — não existe usuário semeado no banco.
 */
export default function LoginPage() {
  const { user, login } = useAuth()
  const navigate = useNavigate()
  const [form, setForm] = useState({ email: '', password: '' })
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  if (user) return <Navigate to="/sessoes" replace />

  async function submit(event) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    const result = await login(form.email, form.password)
    setBusy(false)
    if (result?.ok) navigate('/sessoes', { replace: true })
    else setError(result?.error ?? 'Não foi possível entrar.')
  }

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', minHeight: '100vh' }}>
      <div style={{ background: 'var(--motiva)', color: 'white', padding: 48, display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
        <div>
          <h1 style={{ fontSize: 30, margin: 0 }}>GreenV</h1>
          <p style={{ opacity: 0.85, lineHeight: 1.6, marginTop: 12, maxWidth: 380 }}>
            Altura de vegetação medida a partir do vídeo capturado em campo. Cada leitura carrega
            de onde veio, com que precisão de GPS, e o que ainda não foi validado.
          </p>
        </div>
        <GrassHorizon />
      </div>

      <div style={{ display: 'grid', placeItems: 'center', padding: 48 }}>
        <form onSubmit={submit} style={{ width: 320, display: 'grid', gap: 12 }}>
          <h2 style={{ fontSize: 18, margin: 0 }}>Entrar</h2>
          <input
            type="email" required placeholder="E-mail" value={form.email} autoComplete="username"
            onChange={e => setForm(f => ({ ...f, email: e.target.value }))}
            style={field}
          />
          <input
            type="password" required placeholder="Senha" value={form.password} autoComplete="current-password"
            onChange={e => setForm(f => ({ ...f, password: e.target.value }))}
            style={field}
          />
          {error && <p style={{ color: '#dc2626', fontSize: 13, margin: 0 }}>{error}</p>}
          <button type="submit" disabled={busy} style={{
            padding: '10px 16px', borderRadius: 'var(--radius-md)', border: 'none',
            background: 'var(--motiva)', color: 'white', fontWeight: 600, cursor: 'pointer',
            opacity: busy ? 0.6 : 1,
          }}>
            {busy ? 'Entrando…' : 'Entrar'}
          </button>
        </form>
      </div>
    </div>
  )
}

const field = {
  padding: '10px 12px', borderRadius: 'var(--radius-md)',
  border: '1px solid var(--border)', fontSize: 14,
}
