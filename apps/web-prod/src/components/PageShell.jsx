import { NavBar, LayoutDashboard, MapIcon } from '@greenv/web-core'

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
  { key: 'sessions', label: 'Sessões', path: '/sessoes', icon: LayoutDashboard },
  { key: 'map', label: 'Mapa', path: '/mapa', icon: MapIcon },
]

const s = {
  page: {
    display: 'flex', flexDirection: 'column', height: '100vh',
    background: 'var(--bg-secondary)', overflow: 'hidden',
  },
  document: { flex: 1, overflowY: 'auto', padding: '28px 32px 40px' },
  fill: { flex: 1, display: 'flex', overflow: 'hidden', minHeight: 0 },
}

export default function PageShell({ currentPage, roadTag, variant = 'document', children }) {
  return (
    <div style={s.page}>
      <NavBar currentPage={currentPage} tabs={TABS} roadTag={roadTag ?? 'DADOS REAIS DA API'} />
      <div style={variant === 'fill' ? s.fill : s.document}>{children}</div>
    </div>
  )
}
