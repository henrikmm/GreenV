# Challenge MOTIVA

Detecção de vegetação que precisa de poda em corredores rodoviários, a partir de imagens de Street View.

## Módulos

| Módulo | Propósito | Status |
|---|---|---|
| [`data-acquisition/`](./data-acquisition) | Coleta programática de imagens Street View ao longo de uma rodovia (current + histórico). | ✅ funcional |
| `dataset/` | Saída do `data-acquisition` — imagens por pista (`SP_RIO/`, `RIO_SP/`) + `metadata.csv`. Gitignored (apenas metadata versionado). | — |
| `model-training/` | Treino de modelo de detecção de vegetação sobre o dataset. |  |
| `backend/` | API de gestão (consultas, alertas de poda, etc). | |

## Setup

Copie `.env.example` para `.env` e preencha com sua chave do Google Maps Platform:

```
cp .env.example .env
```

pip install -r requirements.txt
