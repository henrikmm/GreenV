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
