# data-acquisition

Constrói um dataset de imagens de beira de estrada a partir da Google Street View Static API, incluindo o histórico (panoramas antigos capturados em passagens anteriores do carro do Google).

## Como usar

Pré-requisitos: Python 3.10+, chave do Google Maps Platform com **Directions API** e **Street View Static API** habilitadas.

```bash
pip install -r requirements.txt

# valide a cobertura num ponto antes de gastar cota
python scripts/check_coverage.py -23.5505 -46.6333

# dry-run: monta o metadata.csv sem baixar imagens (FREE)
python capture.py \
    --origin=-23.5505,-46.6333 \
    --destination=-22.9068,-43.1729 \
    --interval 100 \
    --dry-run

# execução real (consome cota: ~$7 a cada 1000 imagens)
python capture.py \
    --origin=-23.5505,-46.6333 \
    --destination=-22.9068,-43.1729 \
    --interval 100 \
    --max-downloads 500
```

Saída em `../dataset/`: subpastas `SP_RIO/` e `RIO_SP/` (uma por pista) mais `metadata.csv`.

> **Nota sobre lat/lon negativos:** use `--origin=<lat>,<lon>` (com `=`). Sem o `=`, argparse interpreta o `-` como início de flag.

## Estratégia de captura

O alvo é uma **rodovia duplicada** (pistas fisicamente separadas, uma por sentido). Para cada km queremos a vegetação dos **dois lados**, então precisamos de imagens das duas pistas.

### 1. Uma rota, uma amostragem

Traçamos a rota **uma vez só** no sentido SP→Rio (Directions API) e amostramos pontos equidistantes ao longo da polyline (`--interval`, default 100m). A volta não é roteada de novo; ela emerge naturalmente do histórico de panoramas.

### 2. Busca de panoramas com offset perpendicular

Em cada ponto amostrado, buscamos panoramas em **três coordenadas**: o ponto central + dois offsets perpendiculares de ±20m (lib `streetview`, gratuita). Isso é necessário porque, no canteiro central de uma pista duplicada, a busca a partir do centro só retorna panoramas da pista mais próxima — deslocar lateralmente faz surgir a cobertura da pista oposta. Depois deduplicamos por `pano_id`.

### 3. Classificação por pista via heading do carro

Cada panorama carrega o `heading` — a direção em que o carro do Google estava se movendo quando o capturou. Comparamos esse heading com o bearing do trecho de rodovia:

- dentro de ±60° do bearing → pista **SP_RIO**
- dentro de ±60° do bearing invertido → pista **RIO_SP**
- fora disso → descartado (cruzamento, retorno, ruído)

Assim separamos automaticamente os panoramas da ida e da volta sem precisar rotear as duas direções.

### 4. Câmera apontada para a vegetação

Queremos a câmera apontando perpendicular à via, na direção da mata à beira da estrada. Para cada pista aplicamos um offset fixo sobre o heading do panorama (`heading_offset` em [`config.py`](./roadcapture/config.py)):

| Pista | heading_offset | pitch | fov |
|---|---|---|---|
| SP_RIO | +89° | 0° | 90 |
| RIO_SP | +99° | -2° | 75 |

Esses números foram determinados empiricamente — escolhi um panorama de referência em cada pista, testei manualmente ângulos no Street View interativo, e calibrei os valores até a câmera enquadrar a vegetação lateral sem capturar o asfalto. `scripts/check_coverage.py` ajuda a explorar pontos antes de calibrar.

### 5. Histórico temporal

A lib `streetview` retorna **todos** os panoramas já capturados naquele local (em geral 1 por ano desde ~2011). Cada um vira uma linha no `metadata.csv` e uma imagem baixada. Isso dá uma série temporal da mesma vista — útil para treinar um modelo a distinguir crescimento sazonal de vegetação que realmente precisa de poda.

## Convenções

**Filename:** `{pass}_{side}_km{km}_{date}_h{heading}_p{pitch}_f{fov}.jpg`
Exemplo: `SP_RIO_L_km003.000_2021-09_h221_p0_f90.jpg`

**Colunas do metadata.csv:** `pass, direction, side, km, sample_lat, sample_lon, road_bearing, pano_id, pano_date, pano_lat, pano_lon, pano_heading, heading, heading_offset, pitch, fov, size, filename`.

## Custos e segurança

- Directions API: grátis nas primeiras 40k requisições/mês.
- Street View **search** (lib `streetview`): grátis, não consome cota.
- Street View **Static** (download de imagem): gratis nas primeiras 10k requisições/mês, depois ~$7 por 1000 imagens.
- Use `--dry-run` para prever volume e `--max-downloads` como teto rígido.

## Estrutura

```
data-acquisition/
├── capture.py              # entrypoint (CLI)
├── requirements.txt
├── roadcapture/
│   ├── config.py           # chave API, constantes de captura
│   ├── route.py            # Directions + amostragem equidistante
│   ├── streetview.py       # search + download
│   └── carriageway.py      # offset perpendicular + classificação + naming
└── scripts/
    └── check_coverage.py   # sonda coordenada, FREE
```
