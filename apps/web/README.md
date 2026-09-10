# apps/web — Dashboard de Gestão de Vegetação

Interface web interativa para visualização e gestão da vegetação ao longo do Rodoanel Oeste (SP-021).

> **Nada nesta interface vem do pipeline de captura.** O app não tem cliente de API: as duas únicas
> chamadas de rede são `fetch('/rocada_polygons.geojson')` e `fetch('/marco_km.geojson')` em
> `src/App.jsx`. Os 642 polígonos vêm do KMZ da Motiva e seus níveis de vegetação de uma planilha —
> nenhum deles foi medido por este sistema. Login, equipes, tendências e ordens de serviço são
> `src/data/mock*.js` e `localStorage`. Ligar o dashboard a uma medição real é uma das lacunas
> listadas em [`docs/STATE-OF-THE-SYSTEM.md`](../../docs/STATE-OF-THE-SYSTEM.md).

## Stack

- **React 18** + **Vite** + **React Router**
- **Leaflet** + **react-leaflet** (mapa interativo, com toggle claro/satélite)
- **Framer Motion** (animações e modais) + **lucide-react** (ícones)
- **Recharts** (gráficos do dashboard)
- **OpenStreetMap** (basemap claro) / **Esri World Imagery** (satélite) — sem API key necessária

## Features

- 🔐 **Login mockado** com sessão em `localStorage` e usuários de demonstração
- 📊 **Visão Geral** — dashboard com KPIs, progresso semanal, distribuição por nível e produtividade por equipe
- 🗺️ **Mapa interativo** com 642 polígonos de áreas de roçada (do KMZ da Motiva)
- 🟢🟡🔴 **Grid de classificação** por nível de altura da vegetação (dados do Excel)
- 🔧 **Ordem de Serviço** — clique em qualquer polígono → modal com formulário pré-preenchido
- 🧭 **Planejar Rota** — sugestões automáticas de trechos vizinhos para combinar numa OS só, e um
  modo manual de seleção no mapa com cálculo de ordem ideal de visita
- 🎛️ **Filtros** por nível de vegetação + toggle de camadas + toggle de basemap
- 📋 **Ordens de Serviço** — tabela com filtros, status e OS combinadas (múltiplos trechos)

## Setup

```bash
cd apps/web
npm ci
npm run dev
```

Acesse `http://localhost:5173`

## Dados

- `public/rocada_polygons.geojson` — 642 polígonos convertidos do KMZ original (`classificacao_rocada.kmz`)
- `public/rocada_grid.json` — Grid de classificação extraído do Excel (`RA-RET-ROÇ-LIMP-2026-03-20.xlsx`)

### Classificação de Altura

| Nível | Altura         | Cor      | Prioridade |
|-------|----------------|----------|------------|
| 1     | h < 10 cm      | 🟢 Verde | Baixa      |
| 2     | 10 ≤ h ≤ 30 cm | 🟡 Amarelo | Média    |
| 3     | h > 30 cm      | 🔴 Vermelho | Alta    |

### Tipos de Equipamento (polígonos)

| Tipo | Quantidade |
|------|-----------|
| Apenas manual | 342 |
| Spider/Giro-Zero/Trator trincheira | 180 |
| Trator com braço articulado | 106 |
| Spider com ancoragem | 14 |

## Estrutura

```
apps/web/
├── public/
│   ├── rocada_polygons.geojson   # polígonos do KMZ
│   ├── marco_km.geojson          # marcos quilométricos
│   └── rocada_grid.json          # grid do Excel
├── src/
│   ├── components/
│   │   ├── MapView.jsx           # mapa Leaflet (polígonos, rota, basemap)
│   │   ├── Sidebar.jsx           # painel lateral do mapa
│   │   ├── RoutePlannerPanel.jsx # painel de sugestões/planejamento de rota
│   │   ├── OrderModal.jsx        # modal de OS (1 ou N trechos)
│   │   ├── NavBar.jsx / UserMenu.jsx / ProtectedRoute.jsx / Legend.jsx
│   │   ├── charts/                # gráficos do dashboard (recharts)
│   │   └── ui/                    # Card, Badge, AnimatedNumber
│   ├── context/AuthContext.jsx   # login mockado
│   ├── data/                     # mockUsers, mockTeams, mockOrders, mockTrends
│   ├── pages/                    # LoginPage, DashboardPage, MapPage, OrdersPage
│   ├── utils/
│   │   ├── classification.js     # cores, níveis, helpers de feature
│   │   ├── routePlanner.js       # agrupamento e ordenação de rota
│   │   └── orders.js             # construção de ordens de serviço
│   ├── styles/global.css         # design tokens
│   ├── App.jsx
│   └── main.jsx
├── index.html
├── package.json
└── vite.config.js
```

## Próximos Passos

- [ ] Conectar com backend API (CRUD de ordens de serviço, autenticação real)
- [ ] Integrar modelo de detecção (module `model-training/`)
- [ ] Adicionar imagens Street View no popup dos polígonos
- [ ] Relatórios de pontos críticos exportáveis
