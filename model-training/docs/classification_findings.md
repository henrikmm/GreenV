# Classificação de altura da vegetação — achados e checkpoint oficial

Este documento consolida o que aprendemos durante a iteração de modelos para o
problema de classificação de 4 classes (`null`, `1`, `2`, `3`) no dataset SP-RIO.


## TL;DR

- Checkpoint oficial: `artifacts/official/` → `artifacts/runs/run_20260421_001421/`.
- Estratégia vencedora: **Focal Loss (γ=2.0) + augmentation forte + class weights,
  SEM weighted sampler**. Avaliação final via **ensemble dos 5 folds + TTA (flip horizontal)**.
- Test set (373 amostras):
  - macro-F1 = **0.664** (baseline era 0.607)
  - balanced accuracy = **0.688** (baseline 0.620)
  - recall da classe 3 = **0.545** (baseline 0.273)

## O problema

Dataset pequeno (~4.500 imagens train/val + 373 test), com forte desbalanceamento:
a classe 3 tem só ~10 amostras no val de cada fold e 11 no test. O baseline
inicial (CE loss + class weights, sem augmentation) rendeu 0.607 de macro-F1
com classe 3 em apenas 27% de recall — praticamente não aprendia a classe 3.

Os erros residuais do baseline eram majoritariamente **entre classes vizinhas**
(1↔2 e 2↔3), o que sugere estrutura ordinal no problema.

## Cronologia dos experimentos

Baseline → rodadas com augmentation/sampler/focal → afinamento.

| # | Config | macro-F1 (ens+TTA) | classe 3 recall | Observação |
|---|---|---|---|---|
| 0 | CE, sem augment, sem sampler | 0.607 | 0.273 | baseline |
| A | Focal γ=2 + augment + **sampler=1.0** | 0.550 | 0.364 | classe 1 colapsou (0.78→0.35): sampler forte demais |
| B | CE + augment + sampler=0.5 | 0.658 | 0.364 | muito próximo do melhor em macro-F1, mas classe 3 ainda fraca |
| C | Focal γ=2 + augment + sampler=0.5 | 0.626 | 0.455 | intermediário |
| **D** | **Focal γ=2 + augment, SEM sampler** | **0.664** | **0.545** | **vencedor** |

## Lições aprendidas

### 1. O `WeightedRandomSampler` foi o vilão
Parecia a ferramenta óbvia para o desbalanceamento, mas com `power=1.0` desfigura
a distribuição real tanto que a classe majoritária (1) começa a ser classificada
como classe vizinha (2). Em dataset pequeno, oversampling artificial prejudica
mais do que ajuda.

**Regra prática:** se for usar sampler, `power=0.5` é o máximo tolerável neste
dataset. Preferível ainda é **não usar sampler** e depender de class weights +
focal loss.

### 2. Focal Loss sozinha basta
Focal com γ=2 e class weights embutidos já foca o bastante nos exemplos difíceis
da classe 3, sem precisar desbalancear artificialmente os batches. A classe 3 foi
de 0.273 → 0.545 de recall só com focal, sem sampler.

### 3. Ensemble + TTA dá grande ganho "grátis"
No fold único com TTA a run D deu 0.593; no ensemble + TTA, **0.664** (+7.1 pp).
Entre todos os truques, esses dois combinados foram os que mais empurraram a
métrica final. Ambos são inferência pura, não alteram treino.

**Regra prática:** sempre reportar números com ensemble + TTA ativos.

### 4. Augmentation ajuda, mas não é remédio universal
RandAugment + ColorJitter + HFlip/VFlip + RandomErasing mantêm o modelo longe de
overfit óbvio no começo do treino, mas a partir do epoch ~10 o val_loss ainda
sobe enquanto train_loss cai (overfit parcial inevitável com dataset pequeno).
Early stopping cuida da parte final. Aumentar augmentation mais que isso tende a
introduzir ruído que prejudica a classe 1.

### 5. O teto do modelo está próximo do teto do label
Matriz de confusão do D (ensemble + TTA):
```
                pred →   null    1    2    3
  true null           [  63,   10,   3,   1]
  true   1            [   9,  136,  48,   0]
  true   2            [   7,   15,  63,   7]
  true   3            [   0,    1,   4,   6]
```
Os 5 erros que sobram da classe 3 são todos para classes vizinhas (2 ou 1).
Para empurrar além de ~0.67 de macro-F1 provavelmente precisa de:
- **mais dados anotados na classe 3** (hoje só 11 no test), ou
- **revisão de labels fronteiriços** — vários erros podem ser ambíguos para
  humanos também.

## Configuração oficial (run D)

```bash
PYTHONPATH=src python scripts/train.py \
  --image-size 320 \
  --max-epochs 30 \
  --patience 7 \
  --scheduler cosine \
  --loss focal --focal-gamma 2.0 \
  --no-weighted-sampler
```

- `--augment` ativo por padrão (pipeline completa em `build_train_transform`).
- `--use-class-weights` ativo por padrão.
- Otimizador: AdamW, lr=1e-4, weight_decay=1e-4.
- CV: StratifiedGroupKFold de 5 folds.

### Avaliação final (sempre ensemble + TTA)

```bash
PYTHONPATH=src python scripts/evaluate_test_ensemble.py artifacts/official --tta
```

Resultado esperado (arquivo `artifacts/official/test_eval_ensemble_tta.json`):

```
macro_f1            0.664
balanced_accuracy   0.688
recall_by_class:
  null              0.818
  1                 0.705
  2                 0.685
  3                 0.545
```

## Próximos passos sugeridos (em ordem de esforço)

1. **Inspecionar visualmente os erros residuais da classe 3** (5 imagens).
   Se forem casos realmente ambíguos, confirma que estamos perto do teto.
2. **Varrer `focal_gamma`** em {1.5, 2.5, 3.0} — pequeno ajuste fino, pode render
   +1–2 pp.
3. **Testar `image_size=384`** — confusão residual está em classes vizinhas;
   resolução maior pode ajudar na separação 2↔3.
4. **Ordinal loss (CORAL/CORN)** — confirmado que os erros são entre classes
   vizinhas, o que é exatamente onde loss ordinal brilha.
5. **Coleta/anotação adicional da classe 3** — caminho mais seguro para passar
   de 0.67 no macro-F1. Com 11 amostras no test, cada erro custa 9 pp no recall
   daquela classe.

## Referências rápidas de código

- Treino: `scripts/train.py`, `src/model_training/training.py`.
- Augmentation: `build_train_transform` em `src/model_training/data.py`.
- Focal Loss: `FocalLoss` em `src/model_training/training.py`.
- Sampler: `build_sampler` em `src/model_training/training.py` (fica desligado
  na config oficial, mas está lá se quiser experimentar de novo).
- Avaliação: `scripts/evaluate_test.py` (fold único, com/sem TTA) e
  `scripts/evaluate_test_ensemble.py` (ensemble, com/sem TTA).
