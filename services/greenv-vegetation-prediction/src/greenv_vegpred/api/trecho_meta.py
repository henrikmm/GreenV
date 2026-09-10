"""Phase 10 (closure) — deterministic trecho identity/classification helpers.

Both derivations here are read-only parses of information ALREADY PRESENT in the pipeline —
nothing is invented:

  * `parse_trecho_id`: `schema.sql`'s own comment on the `trecho` table documents the trecho_id
    format explicitly — "'SP-021:norte:000000' (rodovia:sentido:km_start_m, zero-padded)" — so
    rodovia/sentido/km_start are a direct, lossless parse, not a guess.
  * `km_end` is NOT assumed to be `km_start + 0.5` for every trecho. Verified directly against
    every one of the 118 trechos across BOTH `data/features/feature_row_dev.csv` and the OOD
    holdout (50,655 rows checked): 116 of them are exactly 500 m, but the corridor's final ~300 m
    segment in each direction (km 29.0-29.3, trecho_id suffix "029000") is shorter — so `km_end`
    is derived as `min(km_start + 0.5, CORRIDOR_END_KM)`, which reproduces the correct 29.3 end
    for those two trechos and the correct `+0.5` for all 116 others, matching the source data's
    own `km_mid` column exactly in every case checked. `CORRIDOR_END_KM = 29.3` is the corridor
    length already documented project-wide (`AGENTS.md`, `docs/VEGETATION_PREDICTION_TASK.md`:
    "SP-021/Rodoanel Oeste, km 0-29.3"), not a value invented for this file.
  * `vegetation_level` is NOT a new classification system. It is `schema.sql`'s own
    `height_observation.nivel` GENERATED column formula (Phase 2), confirmed — by reading
    `apps/web/src/utils/classification.js` (read-only; `apps/web` is not modified) — to use the
    identical thresholds as `LEVELS`/`vegetationLevel()` there: `h < 10 -> 1`,
    `10 <= h <= 30 -> 2`, `h > 30 -> 3`, and a `not-ready` source or missing height -> `0`
    ("missing or invalid evidence must never become the lowest mowing priority", per that file's
    own comment). One function, used everywhere this API needs a level — the API and the
    frozen forecast module never disagree about what a "level" means.
"""
from __future__ import annotations
import re
from typing import Optional

TRECHO_ID_PATTERN = re.compile(r"^(?P<rodovia>[A-Za-z0-9\-]+):(?P<sentido>[a-z]+):(?P<suffix>\d{6})$")
CORRIDOR_END_KM = 29.3   # SP-021/Rodoanel Oeste corridor length, documented project-wide
TRECHO_LENGTH_KM = 0.5   # ~500 m, the V1 trecho length used throughout Phase 4's synthetic corpus


def parse_trecho_id(trecho_id: Optional[str]) -> Optional[dict]:
    """{"rodovia", "sentido", "km_start", "km_end"}, or None if `trecho_id` doesn't match the
    documented format — never a guess, never a fabricated value for an unrecognised id."""
    if not trecho_id:
        return None
    m = TRECHO_ID_PATTERN.match(trecho_id)
    if not m:
        return None
    km_start = int(m.group("suffix")) / 1000.0
    km_end = min(round(km_start + TRECHO_LENGTH_KM, 3), CORRIDOR_END_KM)
    return {"rodovia": m.group("rodovia"), "sentido": m.group("sentido"),
           "km_start": km_start, "km_end": km_end}


def vegetation_level(height_cm: Optional[float], operational_status: Optional[str]) -> int:
    """schema.sql's height_observation.nivel formula, verbatim:
        not-ready or missing height -> 0; h<10 -> 1; 10<=h<=30 -> 2; h>30 -> 3.
    Confirmed compatible with apps/web/src/utils/classification.js (read-only check, not modified)."""
    if operational_status == "not-ready":
        return 0
    if height_cm is None:
        return 0
    if height_cm < 10:
        return 1
    if height_cm <= 30:
        return 2
    return 3
