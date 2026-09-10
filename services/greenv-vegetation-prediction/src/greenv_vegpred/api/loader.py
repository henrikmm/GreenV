"""Phase 10 — artifact and snapshot loading, with EXPLICIT, NON-FATAL handling of the one
artifact this repository does not version in git: the frozen Random Forest `.joblib` files.

They are excluded from git because they are 13-45 MB each -- over AGENTS.md's 5 MB commit limit
(see `services/greenv-vegetation-prediction/.gitignore`'s `*random_forest*.joblib` rule, added in
the Phase 7/8 checkpoint). This module never downloads them, never trains a substitute, and never
fails silently: absence is detected once, recorded, exposed on `/health`, and turned into a clean
503 by `service.py` for the one endpoint that actually needs the model at request time (dynamic
inference). Every snapshot-backed endpoint (forecast-by-trecho, ranking, list, summary) keeps
working with the model completely absent, because they only ever read the Phase 9 JSON snapshot.

To regenerate the missing artifact locally (deterministic, seeded, no network access):
    cd services/greenv-vegetation-prediction
    python scripts/train_and_evaluate_ml.py
"""
from __future__ import annotations
import json, logging
from functools import lru_cache
from pathlib import Path
from typing import Optional

logger = logging.getLogger("greenv_vegpred.api")

MODULE_ROOT = Path(__file__).resolve().parents[3]
ARTIFACTS = MODULE_ROOT / "models" / "artifacts"
DAYS_MODEL_PATH = ARTIFACTS / "days_until_30cm__random_forest__keep_plus_candidate.joblib"
CALIB_PATH = MODULE_ROOT / "data" / "models" / "interval_calibration.json"
SNAPSHOT_PATH = MODULE_ROOT / "data" / "forecast" / "ranking_current.json"

MODEL_NAME = "random_forest__keep_plus_candidate__framing_b"
REGEN_COMMAND = "python scripts/train_and_evaluate_ml.py"


class ArtifactStore:
    """Loads each artifact ONCE, lazily, on first access, and caches the result (or the reason it
    failed). No retries, no network, no training -- see module docstring."""

    def __init__(self) -> None:
        self._days_model = None
        self._days_model_error: Optional[str] = None
        self._days_model_attempted = False

        self._calibration: Optional[dict] = None
        self._calibration_error: Optional[str] = None

        self._snapshot: Optional[list] = None
        self._snapshot_error: Optional[str] = None

    # ---------------------------------------------------------------- Random Forest days model
    def days_model(self):
        if not self._days_model_attempted:
            self._days_model_attempted = True
            if not DAYS_MODEL_PATH.exists():
                self._days_model_error = (
                    f"{DAYS_MODEL_PATH.name} not found. Large model weights are intentionally "
                    f"not committed to git (AGENTS.md's 5MB limit). Regenerate with: {REGEN_COMMAND}"
                )
                logger.warning(self._days_model_error)
            else:
                try:
                    from joblib import load
                    self._days_model = load(DAYS_MODEL_PATH)
                except Exception as exc:  # corrupt file, incompatible sklearn/joblib version, ...
                    # Public-facing message stays filename-only (never `str(exc)`, which for some
                    # exception types embeds the full absolute local path) -- the real exception,
                    # with its path, is only ever logged server-side. See loader's F-01 fix note.
                    self._days_model_error = f"failed to load {DAYS_MODEL_PATH.name} (corrupt or incompatible file)"
                    logger.warning("failed to load %s: %s", DAYS_MODEL_PATH, exc)
        return self._days_model

    def model_available(self) -> bool:
        return self.days_model() is not None

    def model_status_message(self) -> Optional[str]:
        self.days_model()
        return self._days_model_error

    # ---------------------------------------------------------------- interval calibration
    def calibration(self) -> Optional[dict]:
        if self._calibration is None and self._calibration_error is None:
            # Existence is checked explicitly, before the read, so a missing file never reaches
            # `except Exception` -- that block's `str(exc)` (e.g. FileNotFoundError's message)
            # embeds the full absolute local path, which must never reach a client (F-01).
            if not CALIB_PATH.exists():
                self._calibration_error = f"{CALIB_PATH.name} not found"
                logger.warning("calibration file missing: %s", CALIB_PATH)
            else:
                try:
                    raw = json.loads(CALIB_PATH.read_text(encoding="utf-8"))
                    self._calibration = {float(k): v for k, v in raw["days_until_30cm"]["q_by_confidence"].items()}
                except Exception as exc:  # malformed JSON, unexpected shape, ...
                    self._calibration_error = f"{CALIB_PATH.name} unavailable (invalid or corrupt)"
                    logger.warning("failed to load %s: %s", CALIB_PATH, exc)
        return self._calibration

    def calibration_available(self) -> bool:
        return self.calibration() is not None

    # ---------------------------------------------------------------- Phase 9 forecast snapshot
    def snapshot(self) -> Optional[list]:
        if self._snapshot is None and self._snapshot_error is None:
            # Same F-01 reasoning as `calibration()` above: check existence up front so the
            # public-facing message never inherits a raw exception's embedded absolute path.
            if not SNAPSHOT_PATH.exists():
                self._snapshot_error = f"{SNAPSHOT_PATH.name} not found"
                logger.warning("forecast snapshot file missing: %s", SNAPSHOT_PATH)
            else:
                try:
                    raw = json.loads(SNAPSHOT_PATH.read_text(encoding="utf-8"))
                    for entry in raw:
                        entry.pop("rank", None)  # re-derived below, never trusted from disk forever
                    from ..forecast import rank_forecasts
                    self._snapshot = rank_forecasts(raw)
                except Exception as exc:  # malformed JSON, unexpected shape, ...
                    self._snapshot_error = f"{SNAPSHOT_PATH.name} unavailable (invalid or corrupt)"
                    logger.warning("failed to load %s: %s", SNAPSHOT_PATH, exc)
        return self._snapshot

    def snapshot_available(self) -> bool:
        return self.snapshot() is not None

    def snapshot_status_message(self) -> Optional[str]:
        self.snapshot()
        return self._snapshot_error


@lru_cache
def get_store() -> ArtifactStore:
    """Process-wide singleton -- artifacts are loaded at most once per process, not per request."""
    return ArtifactStore()
