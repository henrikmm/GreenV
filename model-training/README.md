# model-training

Módulo de treinamento do classificador de estado da vegetação rodoviária.

## Decisão atual

- Backbone escolhido: `EfficientNet-B0`
- Framework: `PyTorch` + `TorchVision`
- Avaliação principal: `CV por grupos`
- Confirmação final: `holdout test`
- Política atual: sem `data augmentation`

## Estrutura

```text
model-training/
├── .envrc
├── .gitignore
├── README.md
├── plano.md
├── requirements.txt
├── src/model_training/
├── tests/
├── scripts/
│   ├── prepare_dataset.py
│   ├── train.py
│   └── smoke_test_efficientnet.py
└── data/
    ├── processed/
    └── splits/
```





Regeneração:

```bash
python scripts/prepare_dataset.py
```

## EfficientNet-B0

O baseline atual já foi preparado com:

- carregamento do `EfficientNet-B0` pré-treinado;
- troca da cabeça final para `4` classes;
- dataset PyTorch lendo os CSVs preparados;
- transforms oficiais do backbone;
- smoke test com forward real.

## Testes

```bash
python -m unittest discover -s tests
python scripts/smoke_test_efficientnet.py --batch-size 8 --max-samples 8
python scripts/smoke_test_efficientnet.py --image-size 320 --batch-size 8 --max-samples 8
```

Treino com validação cruzada:

```bash
python scripts/train.py --device auto
python scripts/train.py --device cuda --image-size 320 --lr 5e-5 --scheduler plateau
python scripts/select_best_cv_result.py artifacts/runs/<run_name>
python scripts/evaluate_test.py artifacts/runs/<run_name> --device cuda
```
