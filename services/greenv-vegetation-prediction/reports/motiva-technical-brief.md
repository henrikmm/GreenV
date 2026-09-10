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
`days_until_30cm` MAE 10,24 dias. *Evidência:* `reports/model-comparison.md`, com IC 95% via
bootstrap. *Não afirmar:* que este é o erro esperado em campo real.

**5. "Por que Random Forest?"**
Venceu Ridge/OLS/Lasso/HistGradientBoosting em toda métrica de VALIDATION e depois em TEST, por
margem grande (ex.: MAE altura +7d ~9,5 no RF vs. ~14,7 na família linear). *Evidência:*
`reports/model-comparison.md`, `reports/hyperparameters.md`. *Não afirmar:* que RF é
necessariamente o melhor modelo possível — foi o melhor **entre os testados**, com um espaço de
busca pequeno e documentado (não uma busca exaustiva).

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
*Não afirmar:* que é uma data garantida — é uma estimativa condicional a "sem nova intervenção".

**8. "Por que o intervalo é tão grande?"**
Porque essa é a incerteza real que o modelo e os dados atuais sustentam — largura média ≈48 dias
(80%) e ≈63 dias (90%). Não estreitamos artificialmente à custa de cobertura. *Evidência:*
`reports/forecast-method.md` §5/§9. *Não afirmar:* que um intervalo mais estreito seria fácil de
conseguir sem sacrificar honestidade estatística.

**9. "Como vocês sabem se a incerteza funciona?"**
Medimos a cobertura empírica no TEST: 83,2% para o nível nominal de 80%, e 90,8% para 90% — perto
do nominal. Mas a garantia teórica do método (split conformal) depende de hipóteses
(exchangeability) que aqui são só aproximadas, por causa da estrutura temporal dos dados e por
VALIDATION também ter informado a escolha do modelo. *Evidência:* `reports/forecast-method.md`
§2/§5. *Não afirmar:* uma garantia matemática exata e incondicional.

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
sem supervisão humana. Ver §22 (Implementado vs. Demonstrado vs. Futuro) para o corte exato.

**17. "Quantos testes automatizados existem e o que eles cobrem?"**
146 testes `pytest`, cobrindo schema SQL (banco temporário real), classificação, parsing de
identidade de trecho, vazamento de features, causalidade de roçada, o forecast e o conformal, o
ranking, a API completa (incluindo o cenário sem o modelo grande), e um smoke test ponta-a-ponta.
*Evidência:* `reports/tests.md`. O Random Forest grande é opcional na suíte (não versionado em
git, >5MB) — a suíte padrão passa sem ele.

**18. "Por que Random Forest e HistGradientBoosting empataram tanto?"**
Porque ambos são modelos de árvore com capacidade parecida sobre o mesmo feature set pequeno (14
colunas); a diferença entre eles no TEST é de décimos de cm, dentro da largura do IC 95%
bootstrap — estatisticamente indistinguíveis nesta escala. Random Forest foi mantido como
principal porque venceu por uma margem (ainda que pequena) em toda métrica. *Evidência:*
`reports/model-comparison.md`.

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

---

*Nenhuma resposta acima usa um número que não esteja em `reports/model-comparison.md`,
`reports/forecast-method.md`, `reports/tests.md` ou `reports/integration.md` — todos re-lidos
diretamente para escrever este documento, não citados de memória.*
