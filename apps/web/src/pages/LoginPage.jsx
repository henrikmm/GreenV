import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { motion } from 'framer-motion'
import { Eye, EyeOff, Mail, Lock, ArrowRight, MapPin, Leaf, ClipboardCheck } from 'lucide-react'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { MOCK_USERS } from '../data/mockUsers'
import GrassHorizon from '../components/ui/GrassHorizon'

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
  heroSubtitle: { fontSize: 14.5, lineHeight: 1.6, color: 'rgba(255,255,255,0.78)', maxWidth: 380 },
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
    fontSize: 13.5, outline: 'none', transition: 'border-color 0.15s',
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
    marginTop: 6,
  },
  divider: {
    display: 'flex', alignItems: 'center', gap: 10, margin: '28px 0 16px',
    fontSize: 11, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em',
  },
  dividerLine: { flex: 1, height: 1, background: 'var(--border)' },
  demoRow: { display: 'flex', flexDirection: 'column', gap: 8 },
  demoBtn: {
    display: 'flex', alignItems: 'center', gap: 10, width: '100%', textAlign: 'left',
    padding: '10px 12px', background: 'white', border: '1px solid var(--border)',
    borderRadius: 'var(--radius-sm)', cursor: 'pointer', fontFamily: 'inherit',
  },
  demoAvatar: (c) => ({
    width: 30, height: 30, borderRadius: '50%', background: c, color: 'white',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    fontSize: 11.5, fontWeight: 700, flexShrink: 0,
  }),
  demoName: { fontSize: 12.5, fontWeight: 600 },
  demoRole: { fontSize: 11, color: 'var(--text-muted)' },
}

const DEMO_COLORS = ['#5e22f3', '#0ea5a0']

export default function LoginPage() {
  const { login } = useAuth()
  const { addToast } = useToast()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [error, setError] = useState('')

  const doLogin = (mail, pass) => {
    const result = login(mail, pass)
    if (!result.ok) { setError(result.error); return }
    navigate('/')
    addToast({ type: 'success', message: `Bem-vindo(a), ${result.user?.name?.split(' ')[0] || ''}!` })
  }

  const handleSubmit = (e) => {
    e.preventDefault()
    doLogin(email, password)
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
          <div style={s.heroTitle}>Gestão de vegetação do Rodoanel Oeste</div>
          <div style={s.heroSubtitle}>
            Monitore a altura da vegetação ao longo da SP-021, planeje rotas de roçada combinando
            trechos próximos e acompanhe a operação em tempo real.
          </div>

          <div style={s.featureList}>
            <div style={s.featureItem}>
              <div style={s.featureIcon}><MapPin size={15} /></div>
              642 trechos mapeados, atualizados por captura em vídeo
            </div>
            <div style={s.featureItem}>
              <div style={s.featureIcon}><Leaf size={15} /></div>
              Classificação automática por nível de altura
            </div>
            <div style={s.featureItem}>
              <div style={s.featureIcon}><ClipboardCheck size={15} /></div>
              Ordens de serviço combinadas, menos deslocamento
            </div>
          </div>
        </div>

        <div style={s.road}>SP-021 · RODOANEL OESTE · KM 0–29,3</div>
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
                  style={s.input} type="email" placeholder="voce@motiva.com.br"
                  value={email} onChange={e => setEmail(e.target.value)} required
                />
              </div>
            </div>

            <div style={s.formGroup}>
              <label style={s.label}>Senha</label>
              <div style={s.inputWrap}>
                <Lock size={15} style={s.inputIcon} />
                <input
                  style={s.input} type={showPassword ? 'text' : 'password'} placeholder="••••••••"
                  value={password} onChange={e => setPassword(e.target.value)} required
                />
                <button type="button" style={s.eyeBtn} onClick={() => setShowPassword(v => !v)} tabIndex={-1}>
                  {showPassword ? <EyeOff size={15} /> : <Eye size={15} />}
                </button>
              </div>
            </div>

            <motion.button
              type="submit" style={s.submit}
              whileHover={{ background: 'var(--motiva-hover)' }}
              whileTap={{ scale: 0.98 }}
            >
              Entrar <ArrowRight size={15} />
            </motion.button>
          </form>

          <div style={s.divider}>
            <div style={s.dividerLine} /> ou acesse como <div style={s.dividerLine} />
          </div>

          <div style={s.demoRow}>
            {MOCK_USERS.map((u, i) => (
              <motion.button
                key={u.email} style={s.demoBtn} onClick={() => doLogin(u.email, u.password)}
                whileHover={{ background: 'var(--bg-secondary)' }} whileTap={{ scale: 0.98 }}
              >
                <div style={s.demoAvatar(DEMO_COLORS[i % DEMO_COLORS.length])}>{u.initials}</div>
                <div>
                  <div style={s.demoName}>{u.name}</div>
                  <div style={s.demoRole}>{u.role}</div>
                </div>
              </motion.button>
            ))}
          </div>
        </motion.div>
      </div>
    </div>
  )
}
