# labeling/

Ferramenta de anotação usando Label Studio para classificar a altura da vegetação lateral à rodovia.

## Classes

| Label | Significado | Critério visual |
|-------|-------------|-----------------|
| `1` | Rasteira | Vegetação abaixo do meio-fio / rente ao chão. Grama recém-cortada. |
| `2` | Média | Vegetação visível acima do meio-fio mas abaixo da linha de guarda-corpo / sinalização. |
| `3` | Alta (precisa poda) | Vegetação invadindo a faixa de domínio, cobrindo placas, ou acima da linha do guard-rail. |
| `null` | N/A | Imagem sem vegetação (muro, concreto), com obstrução, blur, ou inutilizável. |



## Quickstart

```bash
# Terminal 1: inicia o servidor
python labeling/start.py serve

# No browser (http://localhost:8080):
#   - Crie uma conta (local, e-mail + senha fictícios)
#   - Vá em Account & Settings (canto sup. direito) e copie o Access Token

# Terminal 2: importa todas as imagens do dataset via API
python labeling/start.py import --token <SEU_TOKEN>

# Pronto — abra o projeto e comece a rotular!
```

### Opções

```bash
python labeling/start.py serve --images ./dataset/SP_RIO  # só uma subpasta
python labeling/start.py serve --port 9090                 # porta diferente
python labeling/start.py export --token <T> --format csv   # exporta labels
python labeling/start.py export --token <T> --format json  # exporta JSON
```

### Sobre o "Access Token"

No Label Studio 1.23+, o botão **"Access Token"** em *Account & Settings* na verdade copia um **refresh token** JWT (começa com `eyJ...`, vida ~200 anos). A API precisa de um *access token* de curta duração.

O `start.py` detecta JWT automaticamente e troca por access token via `POST /api/token/refresh` antes de cada comando. Você só precisa colar o token que o botão mostra — nada a mais.

Se o seu LS for mais antigo e mostrar um token curto (não-JWT), o script usa como está (formato legacy).

## Atalhos de teclado

| Tecla | Ação |
|-------|------|
| `1` | Marca **Rasteira** (verde) |
| `2` | Marca **Média** (laranja) |
| `3` | Marca **Alta/Poda** (vermelho) |
| `4` | Marca **N/A** (cinza) |
| `Ctrl+Enter` | **Submit** e avança para próxima |
| `Ctrl+Backspace` | Pula imagem (skip) |
| `+` / `-` | Zoom in / out na imagem |
| `Ctrl+Z` | Desfaz última ação |

**Fluxo rápido:** olha a imagem → tecla `1`/`2`/`3`/`4` → `Ctrl+Enter` → repete. ~3 segundos/imagem no ritmo.

## Como rotular (workflow)

1. A imagem aparece no centro. Olhe para a vegetação **lateral** (não o canteiro central).
2. Pressione a tecla da classe (`1`, `2`, `3`, `4`) — o botão colorido acende.
3. `Ctrl+Enter` submete e avança.
4. Na dúvida entre duas classes, prefira a **mais alta** (é mais seguro errar pra cima em contexto de poda).
5. Se a imagem é inutilizável, `4` + `Ctrl+Enter` — não perca tempo.
6. Use zoom (`+`) se precisar ver detalhe da vegetação.


## Estratégia de rotulagem para o dataset temporal

O dataset tem **séries temporais** 


## Exportação e uso no treinamento

Após rotular, exporte:

```bash
python labeling/start.py export --token <SEU_TOKEN> --format csv
```

O CSV exportado (`labeling/exports/labels.csv`) contém:

| Coluna | Conteúdo |
|--------|----------|
| `image` | Caminho/URL da imagem |
| `height_class` | Label atribuído (`1`, `2`, `3`, `null`) |

Este arquivo alimenta o pipeline de treinamento (`model-training/`):

```
dataset/images/  ──┐
                   ├──→  model-training/train.py  ──→  modelo.pth
labeling/exports/  ──┘
    labels.csv
```

O script de treinamento vai:
1. Ler `labels.csv`
2. Filtrar `null` (ou usar como classe separada, dependendo da abordagem)
3. Fazer split train/val/test (stratificado por classe)
4. Treinar classificador (ResNet/EfficientNet + fine-tuning)

## Estrutura

```
labeling/
├── start.py        # script único — instala, configura e lança o Label Studio
├── requirements.txt # dependências (label-studio)
├── exports/         # CSVs/JSONs exportados após rotulagem
│   └── labels.csv   # rotulagem de referência (commitada) — ao exportar você sobrescreve
└── README.md        # este arquivo
```

## Dicas

- **Atalhos de teclado** no Label Studio: `1`=classe 1, `2`=classe 2, etc. `Ctrl+Enter`=submit.
- **Filtragem**: use o filtro do LS para ver só imagens não-rotuladas.
- **Backup**: o Label Studio salva tudo em `~/.local/share/label-studio/`. Para backup, copie essa pasta.

