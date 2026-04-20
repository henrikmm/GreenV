# Challenge MOTIVA

Detecção de vegetação que precisa de poda em corredores rodoviários, a partir de imagens de Street View.

## Módulos

| Módulo | Propósito | Status |
|---|---|---|
| [`data-acquisition/`](./data-acquisition) | Coleta programática de imagens Street View ao longo de uma rodovia (current + histórico). | ✅ funcional |
| `dataset/` | Saída do `data-acquisition` — imagens por pista (`SP_RIO/`, `RIO_SP/`) | — |
| [`labeling/`](./labeling) | Rotulagem das imagens em classes de altura de vegetação (Label Studio). | ✅ funcional |
| `model-training/` | Treino de modelo de detecção de vegetação sobre o dataset. |  |
| `backend/` | API de gestão (consultas, alertas de poda, etc). | |

## Setup

Cada módulo tem seu próprio `requirements.txt` — instale só o que for usar:

```bash
pip install -r data-acquisition/requirements.txt   # coleta de imagens
pip install -r labeling/requirements.txt           # rotulagem
```

Para `data-acquisition`, copie `.env.example` para `.env` e preencha com sua chave do Google Maps Platform:

```bash
cp .env.example .env
```
