# `target_days_until_30cm` construction (R01/R02/R04 remediation, Etapas B1 + B1.1)

**Status: intermediate, technical.** This documents the *construction* fix only. It does not
retrain any model, recalibrate any interval, or update `reports/model-comparison.md`,
`reports/hyperparameters.md`, `reports/forecast-method.md`, `reports/model-card.md` or
`reports/motiva-technical-brief.md` — those depend on Phases 6-9, which this step (B1) does not
run. This report exists only because the module docstring of `src/greenv_vegpred/features/build.py`
points here, and the new semantics could not be made fully self-explanatory in code comments alone.

## What was wrong (Codex independent review)

- **R01 — label-window leakage across split boundaries.** The old code searched up to 120 days
  forward for a crossing, regardless of which split the anchor belonged to. A TRAIN-anchored row
  near the end of 2024 could consult a VALIDATION-period observation; a VALIDATION-anchored row
  near the end of its window could consult a TEST-period observation (measured at 90 rows and 407
  rows respectively, before this fix).
- **R02 — an intervening roçada was invisible to the search.** If a roçada occurred between the
  anchor and the eventual crossing, the old code still reported that (causally unrelated,
  post-cut) crossing as the answer to "how long from `as_of`?" — 1,601 rows across the dev dataset
  were affected (confirmed, matching the Codex review's own count exactly).
- **R04 — administrative censoring conflated with true 120-day censoring.** A row marked
  "censored" gave no way to tell "we watched 120 days and nothing happened" apart from "the
  dataset simply ended before 120 days passed" — the latter was 93.5% of TEST's censored
  population.

## The fix

`label_observation_cutoff(split)` (`build.py`) defines, per split, the last date a
`target_days_until_30cm` search is allowed to look at:

| Split | Cutoff | Rationale |
|---|---|---|
| `train` | day before `VALID_START` | all of `EMBARGO_1` is fair game (it is never itself a training/validation/test example); `VALIDATION`'s own dates are not |
| `validation` | day before `TEST_START` | all of `EMBARGO_2` is fair game; `TEST`'s own dates are not |
| `test` / `excluded` | none (real end of series) | last split (or never used downstream at all for `excluded`) — nothing to protect against |

`build_days_until_30cm()` (`build.py`) then races four **mutually exclusive** outcomes,
chronologically, from the anchor:

| Outcome | Meaning | `target_days_until_30cm` | `censoring_time_days` |
|---|---|---:|---:|
| `event` | a ready >30cm reading occurs before any new roçada, within the observable window | exact days | — |
| `censored_intervention` | a roçada occurs before any crossing is seen — the search **stops there**, never crosses it | `null` | days to the roçada |
| `censored_horizon` | the full 120 days were genuinely, observably cleared | `null` | `120` |
| `censored_end_of_followup` | the window closed early for a reason unrelated to the vegetation — `split_boundary` (R01) or `dataset_end` (R04) | `null` | days actually cleared (< 120) |

The legacy `target_days_until_30cm_censored` (binary 0/1) is kept for the Phase 6-9 scripts this
step does not touch yet — `0` iff `outcome == "event"`, `1` otherwise. This is a coarsening, never
a reclassification of a censored row as an event.

## B1.1 microfix — a roçada without a following observation

B1's first version only ever noticed a roçada while visiting an observation dated on/after it. A
roçada that fell inside the observable window but had no observation between it and
`observable_end_ord` (e.g. a VALIDATION anchor with a roçada at +28d, a cutoff at +30d, and the
next real observation only at +35d, past the cutoff) was invisible and fell through to
`censored_horizon`/`censored_end_of_followup` instead of the correct `censored_intervention`.

Fixed by comparing the roçada's own recorded date directly against the first observed crossing's
date — a chronological race between the two, independent of whether an observation happens to
exist right after the roçada. A same-day tie still resolves to `censored_intervention` (the
already-documented convention: an observation dated on/after the roçada is itself already
post-cut).

## Post-regeneration counts (`data/features/feature_row_dev.csv`, after B1.1)

| Split | n | event | censored_intervention | censored_horizon | censored_end_of_followup |
|---|---:|---:|---:|---:|---:|
| train | 18 486 | 16 673 | 1 755 | 40 | 18 |
| validation | 5 500 | 4 714 | 369 | 73 | 344 |
| test | 8 424 | 7 226 | 684 | 20 | 494 |
| excluded | 1 414 | 1 271 | 143 | 0 | 0 |

(B1, before the microfix, had `censored_intervention`/`censored_horizon`/`censored_end_of_followup`
= 1737/47/29 for train and 361/73/352 for validation — the microfix moves 18 train rows and 8
validation rows out of `horizon`/`end_of_followup` and correctly into `censored_intervention`.
`event` counts are unchanged, confirming the fix reclassifies only previously-mislabeled censored
rows, never a genuine event.)

`censored_end_of_followup` cause: TRAIN 18/18 `split_boundary`; VALIDATION 344/344
`split_boundary`; TEST 494/494 `dataset_end`. Row population is byte-identical to before this fix
(same 33 824 dev / 16 831 OOD rows, same split counts) — only the days-target's classification
changed; no row was added or removed.

Verified, all zero: TRAIN event-crossings landing inside VALIDATION; VALIDATION event-crossings
landing inside/after TEST; EVENT rows with an intervening roçada before the crossing;
`censored_end_of_followup` rows reporting `censoring_time_days >= 120`; `censored_horizon` rows
with `censoring_time_days != 120`; a known roçada inside the claimed-clear window of any
`censored_horizon`/`censored_end_of_followup` row (989 such rows checked, 0 misclassified).

`target_height_plus_{7,14,30}d_cm` are confirmed byte-for-byte unchanged (same per-split non-null
counts, same maximum actual offsets: 7/14/28 days) — this fix touches only
`target_days_until_30cm`.

## What this step did NOT do

No retraining, no recalibration, no Phase 6-9 script run, no `ranking_current`/`prediction_batch`
regeneration, no change to `reports/model-comparison.md` or any other frozen-number report. Those
follow in a later step, once B1 is reviewed and a retrain is authorized — the population available
for the days-model is now smaller for genuine "event" labels (16 673/18 486 train,
4 714/5 500 validation) than the old, contaminated count, and `ml_common.py`'s current
cap-censored-at-120 training convention itself still needs revisiting (out of scope here) before
that retrain, since it does not yet distinguish `censored_intervention` /
`censored_end_of_followup` from `censored_horizon`.
