# GreenV Vegetation Prediction

Um protótipo, ponta-a-ponta, de **previsão** de altura de vegetação para o corredor SP-021
(Rodoanel Oeste), construído inteiramente sobre **dados sintéticos**, documentado e testado.

## Problema

O GreenV hoje responde "qual é a altura da vegetação **agora**?" — uma foto do estado atual, que
só vira ação depois que um trecho já está crítico. Não existe, na cadeia atual, nenhuma resposta
para "quando este trecho **vai** precisar de atenção?" — a manutenção é reativa por construção.

## Solução

Um módulo de previsão que, a partir da altura atual e do histórico de um trecho, estima **em
quantos dias ele deve atingir o limite crítico de 30 cm**, com um intervalo de incerteza e uma
prioridade operacional — permitindo agendar a roçada **antes** de o trecho virar crítico, não só
depois.

## Arquitetura

**V1, real, hoje:**

```
Dashboard (React/Vite, apps/web)
        │  HTTP
        ▼
Prediction API (FastAPI, src/greenv_vegpred/api/)
        │
        ▼
forecast / ranking layer (src/greenv_vegpred/forecast/)
        │
        ▼
snapshot (data/forecast/ranking_current.json) + calibração (data/models/interval_calibration.json)
        │
        ▼
Random Forest congelado (models/artifacts/*.joblib)
```

**Futura, ainda não construída** (ver `reports/integration.md` §14 para os detalhes e a lacuna de
contrato já identificada):

```
captura (vídeo/GPS) → frame extractor → measurement worker → segment.measured.v1
        → [consumer de previsão — NÃO EXISTE HOJE] → materialização de features
        → forecast → Prediction API → Dashboard
```

## Dados

Ver `reports/data-dictionary.md` e a seção "Dados" abaixo para o detalhe completo. Resumo:
**real** é só o clima (Open-Meteo, checado contra NASA POWER); **tudo relativo à altura da
vegetação — séries de crescimento, roçadas, os splits TRAIN/VALIDATION/TEST/OOD, e o snapshot que
o dashboard consome — é sintético**, gerado pelo simulador da Fase 4. Nunca apresente uma altura
mostrada por este módulo como uma medição real da SP-021.

## Modelagem

**Random Forest** (`keep_plus_candidate`, `n_estimators=300`, `max_depth=None`,
`min_samples_leaf=20`, `random_state=42`) para altura em +7/+14/+30 dias e para
`days_until_30cm` (regressão direta, "framing B"). Escolhido entre Ridge/OLS/Lasso/HistGB/Random
Forest — ver `reports/model-comparison.md` e `reports/hyperparameters.md` para a comparação
completa e o porquê.

## Previsão de 30 cm

A pergunta operacional: *"se não houver uma nova roçada desconhecida, em quantos dias este trecho
deve atingir 30 cm?"* — com estados explícitos para "já crítico" (`critical`), "fora do horizonte
de 120 dias" (`beyond_horizon`) e "sem altura suficiente para responder" (`insufficient_data`).
Ver `reports/forecast-method.md` §1.

## Incerteza

Um intervalo por **split conformal**, calibrado uma vez em VALIDATION, medido uma vez em TEST —
**largo** (80%: ~40 dias de largura média; 90%: ~55 dias) e isso é uma limitação real da precisão
atual, não escondida. Ver `reports/forecast-method.md`.

## API

FastAPI local, `GET /health`, `GET /api/v1/summary`, `GET /api/v1/forecasts`,
`GET /api/v1/forecasts/ranking`, `GET /api/v1/forecasts/{trecho_id}`,
`POST /api/v1/forecasts/predict`. Ver `reports/api.md` e `api/openapi.v1.yaml`.

## Dashboard

Um painel novo ("Previsão de vegetação") integrado ao GreenV Dashboard existente
(`apps/web`), mostrando resumo, prioridade atual e os próximos trechos a atingir 30 cm — sem
redesenhar o dashboard. Ver `reports/integration.md`.

## Como executar

```
# Backend
cd services/greenv-vegetation-prediction
pip install fastapi "uvicorn[standard]" pydantic scikit-learn joblib numpy   # ou: pip install -e .
python -m uvicorn greenv_vegpred.api.app:app --app-dir src --port 8000
# http://127.0.0.1:8000/health

# Frontend (outro terminal)
cd apps/web
npm ci
npm run dev
# http://localhost:5173
```

O Random Forest grande (`models/artifacts/*random_forest*.joblib`) **não está no git** (>5MB,
`AGENTS.md`). Sem ele, os endpoints de snapshot (summary/ranking/forecast-por-id) continuam
funcionando; só `POST /predict` para `height_cm < 30` responde `503`. Para regenerá-lo:
`python scripts/train_and_evaluate_ml.py` (determinístico, seed fixa, sem rede).

## Como verificar

```
cd services/greenv-vegetation-prediction && bash scripts/verify.sh   # pytest + OpenAPI em sincronia
cd apps/web && npm run build
```

## Estrutura do projeto

```
src/greenv_vegpred/
  synth/        Fase 4 — gerador sintético
  features/     Fase 5 — feature_spec.py (congelado) + build.py
  evaluate/     Fase 6 — harness compartilhado de avaliação
  models/       Fase 7 — baseline, linear, random_forest, gradient_boosting, ml_common
  forecast/     Fase 9 — interval.py (conformal + forecast object), ranking.py
  api/          Fase 10 — FastAPI (app, routes, service, loader, schemas, trecho_meta)
  schema.sql    Fase 2 — schema canônico (SQLite V1, portável para Postgres)
scripts/        Um script por etapa reproduzível (build/train/evaluate/calibrate/verify)
tests/          Fase 11 — suíte pytest (197 testes após o hardening pré-push e as remediações B1/B1.1/B2/B2.1)
data/           Sintéticos, features, modelos, forecasts — a maioria regenerável (ver .gitignore)
reports/        Toda a documentação com evidência (metodologia, métricas, testes, integração)
```

## Limitações

Ver a seção completa em `reports/model-card.md` e `reports/motiva-technical-brief.md`. As mais
importantes: **todos os dados de vegetação são sintéticos**; o intervalo de incerteza é largo; o
holdout OOD mostrou degradação de 22-28% sob mudança de mecanismo; uma roçada futura desconhecida
invalida a projeção; o mapa do dashboard não tem chave estável com os trechos da API ainda.

## Caminho para produção

Ver `reports/motiva-technical-brief.md` §19 e `reports/model-card.md` — resumidamente: coletar
dados reais com `trecho_id` estável → validar contra medição manual → comparar erro do modelo
sintético em dados reais → retreinar/recalibrar com split temporal real → monitorar drift → só
então discutir produção.
