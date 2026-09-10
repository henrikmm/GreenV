# Model Card — GreenV Vegetation Prediction (Phase 13)

Referencia os relatórios já existentes em vez de duplicá-los; os números aqui são os mesmos
números congelados de `reports/model-comparison.md` (Fase 8) e `reports/forecast-method.md`
(Fase 9), lidos diretamente desses arquivos, não recalculados.

## Modelo

**Random Forest Regressor** (scikit-learn), configuração congelada na Fase 7:
`feature_set = keep_plus_candidate` (14 features), `n_estimators=300`, `max_depth=None`,
`min_samples_leaf=20`, `random_state=42`. Um regressor por horizonte de altura (+7d/+14d/+30d) e
um regressor direto para `days_until_30cm` ("framing B"). Ver `reports/hyperparameters.md`.

## Finalidade

Estimar, a partir de uma observação atual de altura + histórico + clima da semana, (a) a altura
provável em +7/+14/+30 dias, e (b) quantos dias faltam até a vegetação atingir o limiar
operacional de 30 cm — para priorizar roçada **antes** do trecho ficar crítico. **Protótipo de
metodologia, não um sistema validado para decisão operacional real.**

## Dados

100% sintéticos (Fase 4, `provenance=synthetic`): 118 trechos de ~500 m no corredor SP-021
(km 0-29,3), duas realizações de seed (`main`=42, `seed2`=1337) para TRAIN/VALIDATION/TEST, e uma
terceira com mecanismo de crescimento diferente (`ood`, seed 2024) só para o teste de robustez.
Clima é a única entrada real (Open-Meteo, checado contra NASA POWER — ver
`reports/weather-eda.md`). Split temporal com embargo de 30 dias + bloqueio por trecho (Fase 5).

## Features

`KEEP` (9) + 5 `CANDIDATE` justificadas — congeladas em `src/greenv_vegpred/features/feature_spec.py`,
verificadas por teste automatizado (`tests/test_features.py`) contra vazamento de
`true_height_cm`/`generator_seed`/`provenance`/`dataset`/alvo-futuro. Nenhuma roçada futura é
usada — verificado por teste (`tests/test_features.py::TestRocadaCycleCausality`).

## Targets

`target_height_plus_{7,14,30}d_cm`; `target_days_until_30cm` (censurado em 120 dias — capado, não
descartado, durante o treino; ver `reports/hyperparameters.md` para a justificativa do
capped-regression em vez de um modelo de sobrevivência completo).

## Avaliação (TEST, uma única vez, Fase 8)

| Métrica | Random Forest | Melhor baseline | Melhoria |
|---|---:|---:|---:|
| Altura MAE +7d | **10.82 cm** | 12.40 cm (mechanistic) | −12.7% |
| Altura MAE +14d | **12.91 cm** | 17.70 cm (seasonal) | −27.1% |
| Altura MAE +30d | **15.14 cm** | 17.70 cm (seasonal) | −14.5% |
| `days_until_30cm` MAE | **10.24 dias** | 13.28 dias (mechanistic) | −22.9% |

IC 95% (bootstrap em bloco por trecho, TEST): +7d [10.43, 11.18]; +14d [12.47, 13.34]; +30d
[14.71, 15.62]; days [9.67, 10.76]. **Todos os números acima são de dados sintéticos e validam o
pipeline/metodologia, não a precisão real de campo.**

## Generalização (OOD, uma única vez, diagnóstico)

Sob mudança do mecanismo de crescimento sintético, a MAE de altura degrada **22-28%** em todos os
horizontes (o baseline mecanístico, ao contrário, *melhora* sob a mesma mudança — evidência de
que parte do sinal aprendido é específico do mecanismo do gerador, não uma relação universal).
**Isto é um stress test sintético, não uma "generalização comprovada para outras rodovias".**

## Incerteza

Intervalo por split conformal, calibrado em VALIDATION, medido em TEST: cobertura empírica 83,2%
(nominal 80%) e 90,8% (nominal 90%) — próxima do nominal, mas **larga**: largura média ≈47,9 dias
(80%) e ≈63,3 dias (90%). A garantia teórica de cobertura depende de exchangeability, que aqui é
só aproximada (VALIDATION também informou a seleção do modelo na Fase 7; há estrutura temporal
com deriva medida). Ver `reports/forecast-method.md` §2 e §5.

## Limitações

- Todos os dados de vegetação são sintéticos — nenhuma trena, nenhuma medição de campo real.
- Clima tem resolução espacial mais grosseira que os 500 m de um trecho (corridor-common).
- Degradação de 22-28% sob mudança de mecanismo (OOD).
- Instabilidade temporal observada no rolling-origin (MAE +7d variando 7,3-13,7 cm entre origens).
- Intervalos de incerteza largos — uma limitação real de precisão, não escondida.
- Uma roçada futura desconhecida invalida a projeção de `days_until_30cm` (estrutural, não um bug).
- 30 cm é a regra operacional do projeto/Motiva, não um limiar botânico universal da literatura.
- O RF grande não está versionado em git (>5MB); a suíte de testes padrão não depende dele.

## Usos apropriados

Demonstração de metodologia; prova de conceito de priorização preditiva; base para discussão
técnica com a banca/Motiva sobre o caminho para dados reais.

## Usos NÃO apropriados

Qualquer decisão operacional real sobre a SP-021; qualquer afirmação de que os números validam
desempenho em campo; qualquer uso em outra rodovia sem revalidação; qualquer apresentação das
alturas do dashboard como medições reais.

## Proveniência

`provenance=synthetic` em toda linha de todo split (TRAIN/VALIDATION/TEST/OOD). Rastreável via
`generator_version`/`generator_seed`/`generator_params_hash` (nunca expostos como feature ou pela
API — ver `tests/test_api.py::TestNoSensitiveDataLeaks`).

## Caminho de atualização

Ver `reports/motiva-technical-brief.md` §19 (a mesma sequência de 9 passos incremental para dados
reais) — resumo: coletar → validar sensor → comparar erro → retreinar/recalibrar com split
temporal real → recalibrar conformal → monitorar drift → só então produção.
