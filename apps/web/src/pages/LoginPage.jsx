import { useState } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'

const s = {
  screen: {
    height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center',
    background: 'var(--bg-secondary)', fontFamily: 'var(--font-sans)', padding: 24,
  },
  card: {
    width: '100%', maxWidth: 400, background: 'white',
    borderRadius: 'var(--radius-lg)', boxShadow: 'var(--shadow-modal)',
    border: '1px solid var(--border)', overflow: 'hidden',
  },
  header: { padding: '28px 28px 0' },
  brand: {
    fontSize: 18, fontWeight: 700, color: '#5e22f3',
    letterSpacing: '-0.01em', marginBottom: 4,
  },
  subtitle: { fontSize: 12, color: 'var(--text-muted)', marginBottom: 24 },
  body: { padding: '0 28px 28px' },
  formGroup: { marginBottom: 16 },
  label: {
    display: 'block', fontSize: 12, fontWeight: 600,
    color: 'var(--text-secondary)', marginBottom: 6,
  },
  input: (invalid) => ({
    width: '100%', padding: '10px 12px', background: 'var(--bg-secondary)',
    border: `1px solid ${invalid ? '#d64545' : 'var(--border)'}`,
    borderRadius: 'var(--radius-sm)', color: 'var(--text-primary)',
    fontSize: 13, fontFamily: 'inherit', outline: 'none', boxSizing: 'border-box',
  }),
  error: {
    padding: '10px 12px', marginBottom: 16, fontSize: 12,
    color: '#8c2020', background: '#fdeaea',
    border: '1px solid #f5c6c6', borderRadius: 'var(--radius-sm)',
  },
  btnPrimary: (busy) => ({
    width: '100%', padding: '12px 20px', background: busy ? '#8b62f6' : '#5e22f3',
    color: 'white', border: 'none', borderRadius: 'var(--radius-sm)',
    fontSize: 13, fontWeight: 700, cursor: busy ? 'default' : 'pointer',
    fontFamily: 'inherit',
  }),
}

export default function LoginPage() {
  const { status, login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  const [form, setForm] = useState({ email: '', password: '' })
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  // Someone already signed in who lands here goes where they were headed, not back to a form.
  if (status === 'authenticated') {
    return <Navigate to={location.state?.from?.pathname ?? '/'} replace />
  }

  const submit = async (event) => {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await login(form.email, form.password)
      navigate(location.state?.from?.pathname ?? '/', { replace: true })
    } catch (failure) {
      // Never says which of the two was wrong: the server does not tell us, on purpose, because a
      // form that distinguishes them is a way to find out which accounts exist.
      setError(
        failure.status === 503
          ? 'O serviço de autenticação está indisponível. Tente novamente em instantes.'
          : 'E-mail ou senha inválidos.',
      )
      setBusy(false)
    }
  }

  return (
    <div style={s.screen}>
      <div style={s.card}>
        <div style={s.header}>
          <div style={s.brand}>MOTIVA</div>
          <div style={s.subtitle}>Gestão de vegetação rodoviária</div>
        </div>

        <form style={s.body} onSubmit={submit}>
          {error && <div style={s.error} role="alert">{error}</div>}

          <div style={s.formGroup}>
            <label style={s.label} htmlFor="email">E-mail</label>
            <input
              id="email"
              type="email"
              autoComplete="email"
              autoFocus
              required
              style={s.input(Boolean(error))}
              value={form.email}
              onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))}
            />
          </div>

          <div style={s.formGroup}>
            <label style={s.label} htmlFor="password">Senha</label>
            <input
              id="password"
              type="password"
              autoComplete="current-password"
              required
              style={s.input(Boolean(error))}
              value={form.password}
              onChange={(e) => setForm((f) => ({ ...f, password: e.target.value }))}
            />
          </div>

          <button type="submit" style={s.btnPrimary(busy)} disabled={busy}>
            {busy ? 'Entrando…' : 'Entrar'}
          </button>
        </form>
      </div>
    </div>
  )
}
