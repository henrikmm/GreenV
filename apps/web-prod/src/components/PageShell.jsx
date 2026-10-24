import { NavBar, LayoutDashboard, MapIcon, ClipboardList, Users, useAuth } from '@greenv/web-core'
import { Eye, Ruler } from 'lucide-react'
import { isReadOnly } from '../api/greenv'

/**
 * O casco de altura total que toda tela precisa ter.
 *
 * `global.css` põe `overflow: hidden` em `html`, `body` e `#root`: a aplicação é uma janela, não
 * um documento. Uma página que só empilha conteúdo com padding parece funcionar até o conteúdo
 * passar da dobra, e aí o que sobra fica inalcançável, sem barra de rolagem e sem erro nenhum.
 * Foi exatamente assim que o painel nasceu quebrado.
 *
 * Então a rolagem é aqui dentro, no `content`, como nas telas da demonstração. Uma tela de mapa
 * não quer rolagem nem padding, e por isso existe a variante `fill`.
 */
export const TABS = [
  { key: 'sessions', label: 'Visão Geral', path: '/sessoes', icon: LayoutDashboard },
  { key: 'segments', label: 'Trechos', path: '/trechos', icon: Ruler },
  { key: 'map', label: 'Mapa', path: '/mapa', icon: MapIcon },
  { key: 'orders', label: 'Ordens de Serviço', path: '/ordens', icon: ClipboardList },
  { key: 'teams', label: 'Equipes', path: '/equipes', icon: Users },
]

const s = {
  page: {
    display: 'flex', flexDirection: 'column', height: '100vh',
    background: 'var(--bg-secondary)', overflow: 'hidden',
  },
  document: { flex: 1, overflowY: 'auto', padding: '28px 32px 40px' },
  fill: { flex: 1, display: 'flex', overflow: 'hidden', minHeight: 0 },
  // Uma faixa fina e fixa, fora do casco de rolagem, para que o visitante saiba em qualquer tela
  // por que não há botão de criar nem de alterar.
  demoBanner: {
    flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
    padding: '5px 16px', fontSize: 12, fontWeight: 600, color: 'var(--motiva)',
    background: 'var(--motiva-subtle)', borderBottom: '1px solid var(--border)',
  },
}

export default function PageShell({ currentPage, roadTag, variant = 'document', children }) {
  const { user } = useAuth()
  return (
    <div style={s.page}>
      <NavBar currentPage={currentPage} tabs={TABS} roadTag={roadTag ?? ''} />
      {isReadOnly(user) && (
        <div style={s.demoBanner} role="status">
          <Eye size={13} /> Modo demonstração · somente leitura
        </div>
      )}
      <div style={variant === 'fill' ? s.fill : s.document}>{children}</div>
    </div>
  )
}
