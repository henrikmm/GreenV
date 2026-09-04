# frontend/ — Dashboard de Gestão de Vegetação

Interface web interativa para visualização e gestão da vegetação ao longo do Rodoanel Oeste (SP-021).

## Stack

- **React 18** + **Vite**
- **Leaflet** + **react-leaflet** (mapa interativo)
- **OpenStreetMap** / CARTO dark tiles (sem API key necessária)

## Features

- 🗺️ **Mapa interativo** com 642 polígonos de áreas de roçada (do KMZ da Motiva)
- 🟢🟡🔴 **Grid de classificação** por nível de altura da vegetação (dados do Excel)
- 🔧 **Ordem de Serviço** — clique em qualquer polígono → modal com formulário pré-preenchido
- 🎛️ **Filtros** por nível de vegetação + toggle de camadas
- 📊 **Resumo** com contagem por nível no sidebar

## Setup

```bash
cd frontend
npm install
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
frontend/
├── public/
│   ├── rocada_polygons.geojson   # polígonos do KMZ
│   └── rocada_grid.json          # grid do Excel
├── src/
│   ├── components/
│   │   ├── MapView.jsx           # mapa Leaflet
│   │   ├── Sidebar.jsx           # painel lateral
│   │   ├── OrderModal.jsx        # modal de OS
│   │   └── Legend.jsx             # legenda do mapa
│   ├── utils/
│   │   └── classification.js     # cores, níveis, helpers
│   ├── styles/
│   │   └── global.css            # design system
│   ├── App.jsx
│   └── main.jsx
├── index.html
├── package.json
└── vite.config.js
```

## Próximos Passos

- [ ] Conectar com backend API (CRUD de ordens de serviço)
- [ ] Integrar modelo de detecção (module `model-training/`)
- [ ] Adicionar imagens Street View no popup dos polígonos
- [ ] Módulo de planejamento operacional (cronograma semanal)
- [ ] Relatórios de pontos críticos

## Autenticação

A tela de login fica em `/entrar`. As rotas `/` e `/ordens` ficam atrás de `RequireAuth`.

A sessão vive em cookies `HttpOnly`, então o JavaScript não consegue lê-la — e por isso também não
consegue saber sozinho se está autenticado. Quem responde isso é `GET /v2/auth/me`, chamado uma vez
no boot pelo `AuthProvider`. Nada de sessão é guardado em `localStorage`: o servidor é a única fonte
de verdade, e uma cópia local só poderia estar desatualizada. (As ordens de serviço continuam em
`localStorage` sob `motiva_orders`, como antes.)

### Rodando localmente

O `vite.config.js` encaminha `/api` para a API, então o navegador conversa com uma única origem.
Isso não é conveniência: servido direto de `:5173` para outro host, o cookie de sessão seria um
cookie de terceiros, que Safari e Firefox bloqueiam.

```bash
npm ci
npm run dev                                             # contra o stack local (127.0.0.1:8080)
GREENV_API_TARGET=https://greenvapi.matomomitsu.com npm run dev   # contra a nuvem
```

Crie um usuário com o token operacional antes do primeiro login:

```bash
curl -X POST http://localhost:8080/v2/identity/users \
  -H "Authorization: Bearer $GREENV_API_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"email":"operador@motiva.com.br","password":"uma-senha-bem-longa-123","displayName":"Ana","role":"OPERATOR"}'
```

`VITE_GREENV_API_URL` (veja `.env.example`) só é necessário quando o dashboard for publicado no
mesmo domínio registrável da API. Vazio significa "mesma origem, via `/api`".

**Chrome ou Firefox para desenvolvimento local.** Os cookies são `Secure`, e esses dois tratam
`http://localhost` como origem confiável; o Safari não, e descarta o cookie de sessão.
