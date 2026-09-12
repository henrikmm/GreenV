import { useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { motion } from 'framer-motion'
import { Eye, EyeOff, Mail, Lock, ArrowRight, Route, Ruler, Camera } from 'lucide-react'
import { useAuth, useToast, GrassHorizon } from '@greenv/web-core'

/**
 * Entrar de verdade.
 *
 * A tela é a da demonstração, com uma ausência deliberada: não há botões de acesso rápido, porque
 * não existe credencial para exibir. A senha nunca esteve no cliente, e a primeira conta é criada
 * com o token estático pela rota de identidade — não há usuário semeado no banco.
 */
const s = {
  page: {
    height: '100vh', width: '100vw', display: 'flex',
    background: 'var(--bg-secondary)', overflow: 'hidden',
  },
  brandPanel: {
    flex: '0 0 44%', position: 'relative', background: 'var(--motiva-gradient)',
    display: 'flex', flexDirection: 'column', justifyContent: 'space-between',
    padding: '48px 56px', color: 'white', overflow: 'hidden',
  },
  brandDots: {
    position: 'absolute', inset: 0, opacity: 0.15,
    backgroundImage: 'radial-gradient(rgba(255,255,255,0.9) 1.5px, transparent 1.5px)',
    backgroundSize: '28px 28px',
  },
  wordmark: { fontSize: 22, fontWeight: 800, letterSpacing: '-0.01em', position: 'relative' },
  heroText: { position: 'relative' },
  heroTitle: { fontSize: 34, fontWeight: 700, lineHeight: 1.25, marginBottom: 14, maxWidth: 420 },
  heroSubtitle: { fontSize: 14.5, lineHeight: 1.6, color: 'rgba(255,255,255,0.78)', maxWidth: 400 },
  featureList: { display: 'flex', flexDirection: 'column', gap: 14, marginTop: 32, position: 'relative' },
  featureItem: { display: 'flex', alignItems: 'center', gap: 12, fontSize: 13.5, color: 'rgba(255,255,255,0.88)' },
  featureIcon: {
    width: 30, height: 30, borderRadius: 9, background: 'rgba(255,255,255,0.14)',
    display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
  },
  road: { position: 'relative', fontSize: 11.5, color: 'rgba(255,255,255,0.55)', fontFamily: 'var(--font-mono)' },

  formPanel: { flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24 },
  formCard: { width: 380 },
  title: { fontSize: 24, fontWeight: 700, marginBottom: 6 },
  subtitle: { fontSize: 13.5, color: 'var(--text-secondary)', marginBottom: 32 },
  formGroup: { marginBottom: 16 },
  label: { display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--text-secondary)', marginBottom: 6 },
  inputWrap: { position: 'relative', display: 'flex', alignItems: 'center' },
  inputIcon: { position: 'absolute', left: 13, color: 'var(--text-muted)', pointerEvents: 'none' },
  input: {
    width: '100%', padding: '11px 12px 11px 38px', background: 'white',
    border: '1.5px solid var(--border)', borderRadius: 'var(--radius-sm)',
    fontSize: 13.5, outline: 'none', fontFamily: 'inherit',
  },
  eyeBtn: {
    position: 'absolute', right: 10, background: 'none', border: 'none',
    color: 'var(--text-muted)', cursor: 'pointer', display: 'flex', padding: 4,
  },
  error: {
    background: 'rgba(220,38,38,0.08)', color: '#b91c1c', fontSize: 12.5,
    padding: '9px 12px', borderRadius: 'var(--radius-sm)', marginBottom: 16,
  },
  submit: {
    width: '100%', padding: '12px 16px', background: 'var(--motiva)', color: 'white',
    border: 'none', borderRadius: 'var(--radius-sm)', fontSize: 14, fontWeight: 700,
    cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
    marginTop: 6, fontFamily: 'inherit',
  },
  note: {
    marginTop: 24, fontSize: 11.5, lineHeight: 1.6, color: 'var(--text-muted)',
    borderTop: '1px solid var(--border)', paddingTop: 14,
  },
}

export default function LoginPage() {
  const { user, login } = useAuth()
  const { addToast } = useToast()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  if (user) return <Navigate to="/sessoes" replace />

  async function handleSubmit(event) {
    event.preventDefault()
    setBusy(true)
    setError('')
    const result = await login(email, password)
    setBusy(false)
    if (!result?.ok) { setError(result?.error ?? 'Não foi possível entrar.'); return }
    navigate('/sessoes', { replace: true })
    addToast({ type: 'success', message: `Bem-vindo(a), ${result.user?.name?.split(' ')[0] ?? ''}!` })
  }

  return (
    <div style={s.page}>
      <motion.div
        style={s.brandPanel}
        initial={{ opacity: 0, x: -24 }}
        animate={{ opacity: 1, x: 0 }}
        transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
      >
        <div style={s.brandDots} />
        <div style={s.wordmark}>MOTIVA</div>

        <div style={s.heroText}>
          <div style={s.heroTitle}>Altura de vegetação medida a partir do vídeo de campo</div>
          <div style={s.heroSubtitle}>
            Cada sessão gravada vira uma trilha no mapa, com a altura medida trecho a trecho e a
            foto por trás de cada ponto.
          </div>

          <div style={s.featureList}>
            <div style={s.featureItem}>
              <div style={s.featureIcon}><Route size={15} /></div>
              O caminho de cada captura, onde quer que ela tenha sido feita
            </div>
            <div style={s.featureItem}>
              <div style={s.featureIcon}><Ruler size={15} /></div>
              Altura por trecho, com quantas células sustentam a leitura
            </div>
            <div style={s.featureItem}>
              <div style={s.featureIcon}><Camera size={15} /></div>
              O quadro tirado em cada ponto da trilha, com a precisão do GPS
            </div>
          </div>
        </div>

        <div style={s.road}>DADOS REAIS DA API</div>
        <GrassHorizon style={{ bottom: -8, opacity: 0.9 }} />
      </motion.div>

      <div style={s.formPanel}>
        <motion.div
          style={s.formCard}
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.45, delay: 0.1, ease: [0.16, 1, 0.3, 1] }}
        >
          <div style={s.title}>Entrar</div>
          <div style={s.subtitle}>Acesse o painel de gestão de vegetação.</div>

          {error && (
            <motion.div style={s.error} initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
              {error}
            </motion.div>
          )}

          <form onSubmit={handleSubmit}>
            <div style={s.formGroup}>
              <label style={s.label}>E-mail</label>
              <div style={s.inputWrap}>
                <Mail size={15} style={s.inputIcon} />
                <input
                  style={s.input} type="email" placeholder="voce@motiva.com.br" required
                  autoComplete="username" value={email} onChange={e => setEmail(e.target.value)}
                />
              </div>
            </div>

            <div style={s.formGroup}>
              <label style={s.label}>Senha</label>
              <div style={s.inputWrap}>
                <Lock size={15} style={s.inputIcon} />
                <input
                  style={s.input} type={showPassword ? 'text' : 'password'} placeholder="••••••••"
                  required autoComplete="current-password"
                  value={password} onChange={e => setPassword(e.target.value)}
                />
                <button type="button" style={s.eyeBtn} onClick={() => setShowPassword(v => !v)} tabIndex={-1}>
                  {showPassword ? <EyeOff size={15} /> : <Eye size={15} />}
                </button>
              </div>
            </div>

            <motion.button
              type="submit" style={{ ...s.submit, opacity: busy ? 0.65 : 1 }} disabled={busy}
              whileTap={{ scale: 0.98 }}
            >
              {busy ? 'Entrando…' : <>Entrar <ArrowRight size={15} /></>}
            </motion.button>
          </form>

          <div style={s.note}>
            Sem acesso rápido: este painel fala com a API de verdade, e nenhuma senha vive no
            navegador. As contas são criadas pela rota de identidade.
          </div>
        </motion.div>
      </div>
    </div>
  )
}
