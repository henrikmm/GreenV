# Technical brief para a banca / Motiva (Phase 13)

Objetivo: dar a qualquer integrante do time informação tecnicamente correta para responder
perguntas prováveis, com evidência do próprio projeto — sem inventar nenhum número. Cada resposta
tem ~20-30 segundos de fala, a evidência de onde vem, e o que **não** devemos afirmar.

---

**1. "Os dados são reais?"**
Não os de vegetação. Só o clima é real (Open-Meteo, checado contra NASA POWER). Toda altura de
vegetação, roçada, e os splits TRAIN/VALIDATION/TEST/OOD são sintéticos, gerados por um simulador
próprio (Fase 4). *Evidência:* `reports/data-dictionary.md`, `provenance=synthetic` em toda linha.
*Não afirmar:* que qualquer altura mostrada é uma medição real da SP-021.

**2. "Se os dados são sintéticos, por que a previsão tem valor?"**
Porque o valor demonstrado é **metodológico**: o pipeline inteiro (features causais, modelo,
avaliação honesta com TEST/OOD isolados, intervalo de incerteza, API, dashboard) está construído
e testado. O simulador imita mecanismos reais de crescimento (GDD, déficit hídrico, roçada) para
que a metodologia já esteja pronta para receber dados reais no lugar dos sintéticos. *Evidência:*
`reports/synthetic-model.md`. *Não afirmar:* que isso mede desempenho real de campo.

**3. "Como vocês chegaram aos 30 cm?"**
É a regra operacional adotada pelo projeto/Motiva para a SP-021, não um limiar botânico universal
extraído da literatura. *Evidência:* `docs/VEGETATION_PREDICTION_TASK.md` (regra de negócio dada
pela Motiva), `schema.sql`'s `height_observation.nivel`. *Não afirmar:* que 30 cm tem uma origem
científica além da decisão operacional do projeto.

**4. "Qual é o erro do modelo?"**
No TEST sintético: altura MAE 10,82 cm (+7d), 12,91 cm (+14d), 15,14 cm (+30d);
`days_until_30cm` MAE 11,17 dias (número atualizado — remediação B2, R01/R02/R04; ver
`reports/target-construction.md`). *Evidência:* `reports/model-comparison.md`, com IC 95% via
bootstrap. *Não afirmar:* que este é o erro esperado em campo real.

**5. "Por que Random Forest?"**
Em altura (+7/+14/+30d), RF teve o menor MAE de VALIDATION e TEST entre Ridge/OLS/Lasso/HistGB,
por margem grande sobre a família linear (ex.: MAE +7d ~9,5 no RF vs. ~14,7 no Ridge/OLS/Lasso) e
por margem pequena sobre HistGB (ex.: +7d TEST 10,82 vs 10,89 cm). Em `days_until_30cm`, RF também
teve o menor MAE em VALIDATION e TEST (13,12/11,17 dias) contra HistGB (13,19/11,48 — as únicas
duas alternativas reavaliadas em TEST pelo script da Fase 8). **Correção (revisão Codex):** a
família linear (Ridge/OLS/Lasso, 15,76 nos três) só foi reavaliada em VALIDATION para
`days_until_30cm` no B2 — não existe número de TEST para ela, então a comparação com RF em TEST
fica restrita a HistGB; em VALIDATION, RF (13,12) também vence a família linear (15,76). *Evidência:*
`reports/model-comparison.md`, `reports/hyperparameters.md`. *Não afirmar:* que RF é
necessariamente o melhor modelo possível — foi o melhor **entre os testados**, com um espaço de
busca pequeno e documentado (não uma busca exaustiva); a margem sobre HistGB é pequena e nunca foi
testada formalmente como estatisticamente significativa (só duas estimativas com intervalo de
confiança comparadas visualmente, não um teste pareado).

**6. "Por que não rede neural?"**
Fora do escopo explicitamente definido para este protótipo V1 (LSTM/Transformer/redes neurais
foram deliberadamente excluídos por instrução, dado o tamanho do dataset e a prioridade de manter
o pipeline interpretável e rápido de auditar). *Evidência:*
`docs/VEGETATION_PREDICTION_TASK.md` Fase 7. *Não afirmar:* que uma rede neural teria desempenho
pior — não foi testada.

**7. "Como funciona a previsão de dias até 30 cm?"**
Regressão direta (não uma simulação passo-a-passo) a partir da observação atual: "se não houver
uma nova roçada desconhecida, em quantos dias este trecho deve atingir 30 cm?". Estados
explícitos: já crítico (0 dias), estimativa finita com intervalo, sem previsão defensável em 120
dias (`beyond_horizon`), ou dados insuficientes. *Evidência:* `reports/forecast-method.md` §1.
*Não afirmar:* que é uma data garantida — é uma estimativa condicional a "sem nova intervenção";
e não afirmar que `beyond_horizon` é uma capacidade validada do modelo — é um estado de contrato,
não uma probabilidade comprovada de "não vai cruzar em 120 dias" (ver `reports/model-card.md`,
limitação R03).

**8. "Por que o intervalo é tão grande?"**
Porque essa é a incerteza real que o modelo e os dados atuais sustentam — largura média ≈40 dias
(80%) e ≈55 dias (90%) (número atualizado, remediação B2). Após a remediação e o retreino, os
intervalos observados ficaram menores que a versão anterior (≈48/≈63 dias) — mas a população de
calibração mudou de tamanho e o modelo foi retreinado ao mesmo tempo, então não isolamos qual das
duas mudanças explica quanto da redução; não afirmamos uma causa específica. Não estreitamos
artificialmente à custa de cobertura. *Evidência:* `reports/forecast-method.md` §4/§5/§9. *Não
afirmar:* que um intervalo mais estreito seria fácil de conseguir sem sacrificar honestidade
estatística.

**9. "Como vocês sabem se a incerteza funciona?"**
Medimos a cobertura empírica no TEST: 84,8% para o nível nominal de 80%, e 93,7% para 90%
(número atualizado, remediação B2) — acima do nominal, do lado conservador. Mas a garantia
teórica do método (split conformal) depende de hipóteses (exchangeability) que aqui são só
aproximadas, por causa da estrutura temporal dos dados e por VALIDATION também ter informado a
escolha do modelo. *Evidência:* `reports/forecast-method.md` §2/§4/§5. *Não afirmar:* uma
garantia matemática exata e incondicional.

**10. "E se houver uma roçada amanhã?"**
A previsão de `days_until_30cm` fica inválida retroativamente — o modelo nunca usa uma roçada
futura (verificado por teste automatizado que planta uma roçada futura e confirma que ela não
muda nenhuma feature construída). Isso é uma limitação estrutural, documentada, não um bug.
*Evidência:* `reports/forecast-method.md` §1, `tests/test_features.py`.

**11. "Como o clima entra?"**
GDD (graus-dia de crescimento), chuva acumulada e déficit hídrico das últimas semanas, do clima
real (Open-Meteo), comum a todo o corredor (não por trecho individual — resolução mais grosseira
que os 500m de um trecho). *Evidência:* `reports/weather-eda.md`, `reports/methodology-decisions.md`.

**12. "Isso funciona em outra rodovia?"**
Não testado. O holdout OOD (mecanismo de crescimento diferente, mesmo corredor) já mostra
degradação de 22-28% na MAE de altura — evidência de dependência parcial do mecanismo específico
do gerador sintético. Extrapolar para outra rodovia real seria uma afirmação sem base nos dados
atuais. *Evidência:* `reports/model-comparison.md` §"OOD". *Não afirmar:* generalização
comprovada para qualquer outro contexto.

**13. "Como dados reais entrariam?"**
Caminho incremental de 9 passos, do começo de coleta até a discussão de produção — ver §19 abaixo
e `reports/model-card.md`. Nenhum passo foi executado ainda; é um plano, não uma implementação.

**14. "Por que ainda não aparece no mapa?"**
Verificamos diretamente: o GeoJSON do mapa atual (642 polígonos) não tem `trecho_id`, `rodovia`,
`sentido` ou `km` — só nome do equipamento e centroide. Não existe chave estável para ligar aos
118 trechos da API de previsão. Preferimos não inventar uma correspondência aproximada.
*Evidência:* `reports/integration.md` §1E/§11. A solução correta é dar identidade linear
consistente ao GeoJSON (ou ao contrato de medição), não um matching por proximidade geográfica.

**15. "Como a medição atual conversa com a previsão?"**
Ainda não conversa automaticamente. O envelope de medição atual (`measurement-result-v1.json`)
não tem um contrato estável de `rodovia`/`sentido`/`km`/altura agregada de vegetação — ele foi
construído para reconstrução 3D + segmentação, não para essa finalidade. Documentamos o consumer
futuro necessário (`reports/integration.md` §14) sem implementá-lo. *Não afirmar:* que existe
hoje uma integração automática measurement→previsão.

**16. "Isso está pronto para produção?"**
Não. É um protótipo V1 com metodologia completa e testada, mas sem dado real, sem consumer de
medição, sem deployment, e com intervalos largos demais para uma decisão operacional automática
sem supervisão humana. Ver §21 (Implementado vs. Demonstrado vs. Futuro) para o corte exato.

**17. "Quantos testes automatizados existem e o que eles cobrem?"**
197 testes `pytest` (146 na Fase 11; mais testes de hardening pré-push e das remediações B1/B1.1/B2/B2.1 do
target `days_until_30cm`, ver `reports/tests.md`), cobrindo schema SQL (banco temporário real), classificação, parsing de
identidade de trecho, vazamento de features, causalidade de roçada, o forecast e o conformal, o
ranking, a API completa (incluindo o cenário sem o modelo grande), e um smoke test ponta-a-ponta.
*Evidência:* `reports/tests.md`. O Random Forest grande é opcional na suíte (não versionado em
git, >5MB) — a suíte padrão passa sem ele.

**18. "Por que Random Forest e HistGradientBoosting empataram tanto?"**
Porque ambos são modelos de árvore com capacidade parecida sobre o mesmo feature set pequeno (14
colunas); a diferença entre eles no TEST é de décimos de cm em altura e de décimos de dia em
`days_until_30cm` (11,17 vs 11,48 dias). Random Forest foi mantido como principal porque teve o
menor MAE em toda métrica checada, por uma margem pequena. *Correção (revisão Codex):* essa
proximidade nunca foi testada como significância estatística formal (nenhum teste pareado foi
feito) — dizer que os dois são "estatisticamente indistinguíveis" seria uma afirmação mais forte
do que o que foi checado; o correto é dizer que a diferença é pequena frente à própria incerteza
de amostragem do RF (IC 95% de `days_until_30cm` no TEST: [10,61, 11,73], meia-largura ≈0,56 dia,
maior que a diferença de 0,31 dia para o HistGB). *Evidência:* `reports/model-comparison.md`.

**19. "Qual é o passo a passo até dados reais?"**
1. Começar a armazenar medições reais com `trecho_id`, timestamp e qualidade.
2. Garantir que cada medição carregue `trecho_id + observed_at + measurement_quality`.
3. Validar o sensor/medição contra trena/medição manual numa amostra.
4. Rodar o modelo sintético atual sobre os dados reais e medir o erro real (sem retreinar ainda).
5. Retreinar/recalibrar o modelo já com dados reais disponíveis.
6. Usar um split temporal real (não mais só sintético) para treino/validação/teste.
7. Recalibrar o intervalo conformal com resíduos reais.
8. Monitorar drift (mudança de distribuição) continuamente.
9. Só então discutir produção — não antes dos passos 1-8.

**20. "O que exatamente esse projeto NÃO promete?"**
Não promete economia financeira quantificada (nunca medida); não promete precisão de campo
(métricas são 100% sintéticas); não promete funcionar em outra rodovia; não promete um deployment
pronto; não promete que a integração automática measurement→previsão já existe.

**21. Implementado vs. Demonstrado vs. Futuro**
Três níveis de maturidade, para não confundir "existe e roda" com "foi provado" ou "foi planejado":

- **Implementado (código real, roda hoje):** gerador sintético (Fase 4); construção de features
  com isolamento temporal entre splits corrigido (Fase 5, R01/R02/R04); Random Forest congelado
  para altura e para `days_until_30cm`; intervalo por split conformal (Fase 9); API FastAPI
  (Fase 10, com o hardening pré-push da Fase 13); painel no dashboard (Fase 12); suíte de testes
  automatizados (Fase 11).
- **Demonstrado (a metodologia foi executada e medida, só que sobre dado sintético):** a
  comparação de modelos (RF vence os baselines/linear/HistGB em VALIDATION e TEST sintéticos); a
  cobertura empírica do conformal em TEST sintético; a degradação sob OOD sintético. Isso prova que
  o **pipeline funciona e é honesto em como avalia a si mesmo** — não prova precisão em campo real.
- **Futuro (documentado como plano, nada disso existe hoje):** ingestão de medição real com
  `trecho_id` estável; retreino/recalibração com dados reais; integração automática
  measurement→previsão; identidade linear estável no GeoJSON do mapa; autenticação/rate
  limiting/monitoramento de produção; uma extensão de sobrevivência/censura estatística para
  `days_until_30cm` além do que a Fase 7 (V1) faz hoje (ver `reports/model-card.md` "R03" para o
  limite exato do que o regressor direto atual sabe e não sabe responder).

---

*Nenhuma resposta acima usa um número que não esteja em `reports/model-comparison.md`,
`reports/forecast-method.md`, `reports/tests.md` ou `reports/integration.md` — todos re-lidos
diretamente para escrever este documento, não citados de memória.*
