# Model comparison (Phase 6 baselines → Phase 8 final evaluation)

**Phase 6 artifact of [`docs/VEGETATION_PREDICTION_TASK.md`](../../../../docs/VEGETATION_PREDICTION_TASK.md),
extended by Phase 8's final evaluation (see the last section of this file).**
Everything here is trained and evaluated on **synthetic** data (`feature_row_dev.csv`, `main`+
`seed2`, `provenance=synthetic`; the OOD section uses the separate `ood`-generator holdout, also
synthetic). Numbers describe the Phase 4 generator, not the field — **this is not a validation of
real-world performance on the SP-021 corridor.** The body below (Phase 6 baselines on
VALIDATION) is unchanged from the original pass; Phase 8's TEST/OOD/rolling-origin/ablation
results are appended as a new section at the end, not interleaved with it.

> **Update (closing the two Phase 6 completion-criteria gaps).** Two items were added after the
> first pass: the **mechanistic baseline** (`mechanistic` / `mechanistic_days_until_30cm`,
> TASK.md's original "linear growth since last roçada at a per-trecho fitted rate; a GDD-scaled
> variant") and the **shared evaluation harness**
> (`src/greenv_vegpred/evaluate/harness.py`). The pre-existing baselines' logic was **not**
> changed — `persistence`, `last_growth`, `seasonal_climatology` and `naive_days_until_30cm` were
> migrated onto the harness and re-run; their numbers below are **byte-for-byte identical** to the
> first pass (verified by diffing the before/after `baseline_validation_metrics.json`). Only the
> mechanistic rows are new.

**Methodology guardrail, checked, not just promised:** `scripts/evaluate_baselines.py` fits every
baseline on `split == 'train'` rows only and scores on `split == 'validation'` rows only, via the
shared harness (`greenv_vegpred.evaluate.harness`, reusable as-is by Phases 7 and 8: it exposes
`evaluate_height_model`, `evaluate_days_until_30cm_model`, and `stratify_height`/`stratify_days`
against a duck-typed `.fit(train_rows)` / `.predict_height(row, horizon)` / `.predict(row)`
protocol — any future model that implements those three methods plugs in without touching the
harness). The script `assert`s no `'test'` row is ever in the fit or eval sets, and it never opens
`feature_row_ood_holdout.csv`. `test` is reserved for the Phase 8 final comparison; `ood` is
reserved for a one-shot check after every modelling choice (including these baselines' design) is
frozen.

`n_train = 18 486`, `n_validation = 5 500` (Phase 5 temporal split, 30-day embargo at each
boundary).

---

## Baselines implemented

| Baseline | Formula (plain language) | Fit on train? |
|---|---|---|
| `persistence` | Future height = today's height. | No parameters. |
| `last_growth` | Future height = today's height + (this cycle's most recent weekly growth, clamped to 0–35 cm/week) × weeks-ahead. Falls back to persistence when no same-cycle history exists. | No parameters (the clamp is a fixed physical cap, not fit). |
| `seasonal_climatology` | Future height = the **train-period mean height for the calendar month** the target date falls in, pooled across all trechos — ignores the row's own current state entirely. | Yes — the 12 monthly means, from train only. |
| `naive_days_until_30cm` | Days until >30 cm = (30 − today's height) ÷ today's cycle-safe growth rate (cm/day), capped at a 120-day horizon; falls back to the **train-median** growth rate when no local rate exists. | Yes — the fallback rate, from train only. |
| `mechanistic` | Future height = today's height + `rate_i × gdd_multiplier × lag_damping × weeks-ahead`. `rate_i` = the trecho's own median "growth since the cut" rate, fit on train (§ below). `gdd_multiplier` = this week's real GDD ÷ the train-period average daily GDD (persistence-of-climate; clipped [0.2, 3.0]). `lag_damping` = 0.6 while `days_since_rocada < 14`, else 1.0. | Yes — `rate_i` per trecho (118/118 reach the 5-sample floor) + one global average-GDD constant, train only. |
| `mechanistic_days_until_30cm` | Same `rate_i × gdd_multiplier × lag_damping` as `mechanistic`, inverted: days = (30 − height) ÷ rate, same 120-day censoring rule as `naive_days_until_30cm`. | Same fit as `mechanistic`. |

**The GDD-scaled mechanistic baseline (TASK.md's original wording) is now implemented** — see
"Mechanistic baseline" below for the full formula, the leakage guards, and why it does not include
a water-deficit term.

**A literal "same trecho, 52 weeks ago" seasonal-naive (`TASK.md`'s original wording) was
deliberately *not* implemented.** Roçada happens ~5.96 times per trecho per year (Phase 4 QC),
median inter-cut interval 51 days — so the value 52 weeks back almost always comes from an
unrelated growth cycle, not a genuine seasonal repeat. Implementing it would have been a baseline
invented to fill a checklist slot, which the instruction explicitly asked not to do.
`seasonal_climatology` is the defensible substitute: it keeps the "typical height for this time of
year" idea (real seasonality, DEC-001/DEC-003) without depending on any one trecho's cutting
history.

---

## Mechanistic baseline — full formula and leakage guards

**Formula:**

```
rate_i          = per-trecho median "growth since the cut" rate (cm/day), fit on TRAIN
gdd_now_daily   = this row's gdd_week_tb15_c / 7          (known at as_of_date)
gdd_train_daily = mean(gdd_week_tb15_c / 7) over ALL train rows   (one constant, fit on train)
gdd_multiplier  = clip(gdd_now_daily / gdd_train_daily, 0.2, 3.0)
lag_damping     = 0.6 if days_since_rocada < 14 else 1.0
adjusted_rate   = clip(rate_i * gdd_multiplier * lag_damping, 0, 5.0)      # cm/day
height(t+N)     = clip(height(t) + adjusted_rate * N, 0, 150)             # height, N days ahead
days_to_30cm    = clip((30 - height(t)) / adjusted_rate, 0, 120), or censored per the same rule
                  as naive_days_until_30cm
```

**What it uses:** `height_cm` (today), the trecho's own historical since-cut rate (`rate_i`,
fit on train only — see below), `days_since_rocada` (directly, via `lag_damping`), and
`gdd_week_tb15_c` (this week's real GDD at the Tb=15 °C **reference** value — Villa Nova et al.
2007, `literature_derived`; **not** asserted as SP-021's true base temperature, DEC-003). No
water-deficit term: TASK.md's Phase 6 wording ("linear growth since last roçada at a per-trecho
fitted rate; a GDD-scaled variant") does not call for one, and this instruction said to add it
only if the plan required it or there was a clear justification — neither applied, so the
baseline stays exactly as simple as asked.

**How `rate_i` is fit (train only, never crosses a roçada):** for each (dataset, trecho)'s sorted
train observations, the first row of a cycle (`rocada_in_new_cycle=1`) anchors that cycle's start
height and `days_since_rocada`; every *later* row in the *same* cycle yields one sample
`(height_now − height_at_cycle_start) / (days_since_rocada_now − days_since_rocada_at_start)`
(denominator ≥ 3 days, to avoid a near-zero-denominator blow-up). A negative sample (the anchor
read taller than a later same-cycle reading — measurement noise, since true height cannot shrink
without a roçada, Phase 4) is clipped to 0 — the explicit `growth ≤ 0` rule, stated once and
reused everywhere in this file. Samples are pooled **by trecho_id across `main`+`seed2`** (not
split by dataset) for the median — a real deployment has one physical trecho, not two stochastic
realisations of it, and pooling reached the 5-sample floor for **118 / 118 trechos** (15 006 rate
samples total; no trecho needed the global-median fallback). `gdd_multiplier`'s persistence-of-
climate assumption (recent real GDD continues) uses only information available at `as_of_date` —
never a future GDD value, which this V1 does not have (Phase 9 is where a real or climatological
forecast would enter).

**No formula tuning against validation.** The design above (which terms, which clip bounds, the
14-day/0.6 lag constant) was fixed and documented before running the evaluation once; the numbers
below are that single run, not a best-of-several.

---

## Height metrics — validation split

MAE and RMSE in cm; R² is auxiliary only, never used to rank a baseline on its own.

### +7 days

| Baseline | n scored | MAE | RMSE | R² |
|---|---:|---:|---:|---:|
| `persistence` | 4 622 | 12.17 | 20.32 | −0.033 |
| `last_growth` | 4 622 | 13.65 | 24.86 | −0.546 |
| `seasonal_climatology` | 4 622 | 16.42 | 19.80 | 0.020 |
| `mechanistic` | 4 622 | **11.17** | 21.04 | −0.108 |

### +14 days

| Baseline | n scored | MAE | RMSE | R² |
|---|---:|---:|---:|---:|
| `persistence` | 4 306 | 18.37 | 26.22 | −0.750 |
| `last_growth` | 4 306 | 23.98 | 38.48 | −2.770 |
| `seasonal_climatology` | 4 306 | **16.26** | 19.60 | 0.022 |
| `mechanistic` | 4 306 | 17.20 | 28.36 | −1.048 |

### +30 days

| Baseline | n scored | MAE | RMSE | R² |
|---|---:|---:|---:|---:|
| `persistence` | 4 172 | 22.50 | 28.73 | −1.208 |
| `last_growth` | 4 172 | 40.56 | 56.36 | −7.500 |
| `seasonal_climatology` | 4 172 | **15.96** | 19.23 | 0.010 |
| `mechanistic` | 4 172 | 25.63 | 36.50 | −2.564 |

`n scored` excludes rows whose target is missing (a real capture gap, never imputed) — 16.0 % /
21.7 % / 24.2 % of the 5 500 validation rows at +7 / +14 / +30 d respectively.
`last_growth` used the fallback (persistence, rate=0) on 600 / 554 / 551 rows (first observation of
a new roçada cycle) at each horizon; `mechanistic` never needed its (unused) fallback — every
trecho had a fitted `rate_i`.

**No baseline wins at every horizon, and adding `mechanistic` does not change that.**
`mechanistic` is the **best of all four at +7 d** (11.17 cm, beating `persistence`'s 12.17) — the
GDD/lag-aware historical rate captures near-term dynamics slightly better than either a bare
snapshot or a one-week extrapolation. But it does **not** hold that lead: `seasonal_climatology`
remains best at +14 d and +30 d, and `mechanistic` is *worse than plain persistence* at +30 d
(25.63 vs 22.50) — its `gdd_multiplier`'s persistence-of-climate assumption (today's weather
continues for a full month) and its rate-based extrapolation both degrade over a longer horizon,
for the same underlying reason `last_growth` does (linear extrapolation ignores the logistic
deceleration near the plateau, `synthetic-model.md` §2), just less severely because the rate is a
stabler historical median rather than one noisy week. `last_growth` remains the **worst baseline
at every horizon**.

---

## `days_until_30cm` metrics — validation split

| Metric | `naive_days_until_30cm` | `mechanistic_days_until_30cm` |
|---|---:|---:|
| n true not censored | 5 237 | 5 237 |
| n scored (baseline also gave a number) | 4 213 | **4 755** |
| MAE (days) | 17.40 | **14.39** |
| median absolute error (days) | **6.47** | 6.54 |
| within ±3 days | **42.9 %** | 41.3 % |
| within ±7 days | **55.5 %** | 55.0 % |
| predicted censored while truth had an answer | 1 024 (19.6 %) | **482 (9.2 %)** |
| n true censored (never reaches 30 cm within 120 d) | 263 (4.78 % of validation) | 263 |
| also predicted censored, when true was censored | 83 / 263 (31.6 %) | **105 / 263 (39.9 %)** |
| fallback rate used | train median, 9.52 cm/wk, n=13 097 same-cycle rows | train global since-cut median, 0.68 cm/day (never triggered — 118/118 trechos had a local `rate_i`) |
| rows using the fallback rate | 655 | 0 |

**Censoring is handled explicitly, not dropped, for both.** MAE/median/±3d/±7d are computed only
on rows where *both* the true value and the baseline's prediction are real numbers; the other two
groups are reported, not hidden, for each baseline separately.

**`mechanistic_days_until_30cm` is the stronger of the two, on balance.** It scores 542 more rows
(9.2 % vs 19.6 % "gave up too early" — the trecho-level historical rate is more often able to
commit to an answer than one noisy week's own growth), a substantially lower MAE (14.39 vs 17.40
days), and better censoring agreement (39.9 % vs 31.6 % when the truth genuinely never reaches
30 cm). It is marginally worse on median error and the ±3d/±7d shares — its extra confidence comes
with a few more moderately-wrong answers, not more very-wrong ones (MAE, sensitive to large
errors, improves more than the median does). Both still share the same structural weakness: the
**most informative failure mode is not seeing a future `roçada` coming** — `naive`'s 68.4 % and
`mechanistic`'s 60.1 % rate of missing a true "never reaches 30 cm" case are both because the
trecho gets cut before arriving, an event neither baseline can know about in advance.

Median error (≈6.5 d) is noticeably better than MAE (14–17 d) for both — the error distribution is
right-skewed: most predictions land close, a smaller set of badly-missed trechos (the
"gave-up-too-early" and "missed-the-future-cut" groups above) pull the mean up.

---

## Stratified metrics

### Height +7d MAE, by current Nível (anchor row)

| Nível | n | `persistence` | `last_growth` | `seasonal_climatology` | `mechanistic` |
|---|---:|---:|---:|---:|---:|
| 0 (not-ready) | 855 | 12.71 | 14.32 | 16.67 | **11.66** |
| 1 (< 10 cm) | 876 | 6.19 | 6.08 | 17.52 | **4.95** |
| 2 (10–30 cm) | 1 581 | 7.23 | 7.68 | 9.28 | **5.98** |
| 3 (> 30 cm) | 1 310 | 21.76 | 25.49 | 24.15 | **21.29** |

`mechanistic` wins in **every** Nível stratum at +7 d — its largest margin is at Nível 1 (4.95 vs
6.08–17.52), where the per-trecho historical rate is a much better guide than either a global
seasonal mean or one noisy week's growth. `seasonal_climatology` is markedly worse than the other
three at low Nível — the calendar-month mean height is dominated by taller trechos and has no way
to represent "this one is currently short."

### Height +7d MAE, by season

| Season | n | `persistence` | `last_growth` | `seasonal_climatology` | `mechanistic` |
|---|---:|---:|---:|---:|---:|
| wet (Oct–Mar) | 1 309 | 20.20 | 20.60 | 20.72 | **17.88** |
| dry (Apr–Sep) | 3 313 | 8.99 | 10.91 | 14.72 | **8.53** |

Wet-season error is roughly double dry-season error for every baseline — faster real growth
(Phase 5 §5: ~6× seasonal swing) means more absolute change per week, which none of these
baselines fully anticipates. `mechanistic` is best in both seasons, with its largest relative gain
in the wet season, where its GDD-multiplier has the most signal to work with.

### Height +7d MAE, by proximity to the last `roçada`

| `days_since_rocada` | n | `persistence` | `last_growth` | `seasonal_climatology` | `mechanistic` |
|---|---:|---:|---:|---:|---:|
| recent (< 14 d) | 884 | 7.13 | 6.59 | 17.34 | **5.74** |
| mid (14–60 d) | 2 571 | 15.17 | 17.01 | 17.07 | **14.00** |
| long (> 60 d) | 1 167 | 9.35 | 11.61 | 14.28 | **9.06** |

The lag phase right after a cut (`< 14 d`) is the easiest to predict for every baseline except
`seasonal_climatology` — growth is suppressed and slow by construction
(`synthetic-model.md` §2, `phi_lag`), and `mechanistic`'s `lag_damping` term is built for exactly
this regime. The 14–60 day window remains the hardest for everyone — the acceleration/near-linear
phase where a week makes the largest absolute difference.

### `days_until_30cm` MAE (days), by current Nível

| Nível | n (naive) | `naive` MAE | n (mechanistic) | `mechanistic` MAE |
|---|---:|---:|---:|---:|
| 0 (not-ready) | 759 | 27.90 | 867 | **23.67** |
| 1 | 539 | 32.63 | 644 | **21.41** |
| 2 | 1 333 | 25.91 | 1 662 | **20.53** |
| 3 | 1 582 | 0.00 | 1 582 | 0.00 |

Nível 3's `0.00` is **not skill** for either baseline — if the trecho is already above 30 cm and
ready *today*, both the true and predicted "days until 30 cm" are trivially 0 by definition.
`mechanistic` scores **more rows** in every non-trivial stratum (its trecho-level historical rate
gives an answer more often than `naive`'s single-week snapshot) and has a **lower MAE in every
stratum** — its largest relative improvement is at Nível 1 (32.6 → 21.4 days), furthest from the
threshold and where a stable historical rate matters most.

### `days_until_30cm` MAE (days), by season / by `roçada` proximity

| Season | naive MAE | mechanistic MAE | | `days_since_rocada` | naive MAE | mechanistic MAE |
|---|---:|---:|---|---|---:|---:|
| wet | 11.09 | **10.37** | | recent (< 14 d) | 29.51 | **18.76** |
| dry | 20.66 | **16.31** | | mid (14–60 d) | 15.81 | **12.72** |
| | | | | long (> 60 d) | **11.53** | 15.04 |

`mechanistic` is better in every stratum except `long (> 60 d)`, where `naive` edges ahead
(11.53 vs 15.04) — a trecho long past its last cut is close to its own plateau, where recent
observed growth (what `naive` uses) is already a good proxy, while `mechanistic`'s historical
average rate slightly overstates ongoing growth for a trecho that has already slowed down.

---

## Hard cases — not hidden

| Case | Count / share (of 5 500 validation rows) |
|---|---:|
| Negative observed `weekly_growth_cm` (measurement noise, clipped to 0 for extrapolation) | 1 144 (20.8 %) |
| Anchor row `operational_status = not-ready` | 1 025 (18.6 %) |
| New-`roçada`-cycle rows (no local growth rate; fallback used) | 717 (13.0 %) |
| Missing `target_height_plus_7d_cm` | 15.96 % |
| Missing `target_height_plus_14d_cm` | 21.71 % |
| Missing `target_height_plus_30d_cm` | 24.15 % |
| `target_days_until_30cm` right-censored (true) | 4.78 % |

None of these rows were dropped from evaluation by choice — missing targets are excluded from a
metric *because there is nothing to compare against*, not because they were filtered out for being
inconvenient; `not-ready` and new-cycle rows are scored exactly like every other row (see the
stratified tables above, which show they are measurably harder, not swept aside).

---

## Which baseline is strongest?

**Still no single winner — `mechanistic` shifts the picture but does not settle it:**

- **Height, +7 d: `mechanistic`** (11.17 cm MAE) — now the best of all four, and the only one that
  wins in **every** stratum (Nível, season, roçada-proximity) at this horizon, not just on
  average.
- **Height, +14 d and +30 d: `seasonal_climatology`** remains best (16.26 / 15.96 cm) —
  `mechanistic` (17.20 / 25.63 cm) improves on `persistence` at +14 d but falls behind it at
  +30 d, because its persistence-of-climate and linear-rate assumptions both degrade over a
  month, the same failure mode as `last_growth`, just damped by using a historical rate instead
  of one week's noisy sample.
- **`last_growth` is still the weakest height baseline at every horizon.**
- **`days_until_30cm`: `mechanistic_days_until_30cm`** is the stronger of the two implemented —
  lower MAE (14.39 vs 17.40 days), more rows scored (4 755 vs 4 213), better censoring agreement
  (39.9 % vs 31.6 %) — though `naive` keeps a narrow edge on median error and the ±3d/±7d shares.
  Both share the same structural ceiling: **neither can see a future `roçada` coming.**

**Did the mechanistic baseline add real value?** **Yes, partially, and it is the honest kind of
partial.** It is not a strict improvement — it does not dominate `seasonal_climatology` at longer
horizons, and it loses to `naive` on two of four `days_until_30cm` metrics — but it is the single
best height predictor at the horizon that matters most for a weekly-capture operational cadence
(+7 d), it is the more informative `days_until_30cm` baseline overall, and every one of its
stratified wins (Nível, season, roçada-proximity, in §"Stratified metrics") lines up with what the
formula was built to capture (thermal response, the regrowth lag), not a coincidence of tuning —
because the formula was fixed before this evaluation ran once, not iterated against it.

**What Phase 7 must beat, concretely (best baseline per row):**

| | +7 d height MAE | +14 d height MAE | +30 d height MAE | `days_until_30cm` MAE | median | ±3d | ±7d |
|---|---:|---:|---:|---:|---:|---:|---:|
| Best baseline | `mechanistic`: **11.17 cm** | `seasonal_climatology`: **16.26 cm** | `seasonal_climatology`: **15.96 cm** | `mechanistic`: **14.39 d** | `naive`: **6.47 d** | `naive`: **42.9 %** | `naive`: **55.5 %** |

All on the **same** validation split, never on `test` or `ood`.

Reviewed: 2026-09-10.

---

## Phase 8 — final evaluation (TEST, once; OOD, once; rolling-origin; ablation)

**Phase 8 artifact.** Reuses the shared harness and the Phase 7 frozen artifacts, **without any
retraining or reselection**. Reproducing entry point: `scripts/evaluate_phase8.py`; full numbers:
`data/models/phase8_final_evaluation.json`.

**What "validation was used for selection, test was used once, OOD measures a mechanism change"
means here, concretely:** every hyperparameter and every feature-set decision in this section was
frozen in Phase 7 using VALIDATION only. `split == 'test'` (n=8 424) was opened for the first time
by this phase, scored exactly once, and nothing below was changed in response to what it showed.
`feature_row_ood_holdout.csv` (n=16 831, a **different growth mechanism** — Phase 4's
monomolecular-vs-logistic regrowth curve, seed 2024 — not a different sample of the same
mechanism) was opened once, after every decision above was already frozen, and nothing was
adjusted after seeing it either. **All three datasets — train, validation, test, and OOD — are
synthetic.** Nothing in this section is a claim about the SP-021 corridor, real vegetation, or
real weather; it is a claim about how well the frozen model reproduces the Phase 4 generator's own
held-out behaviour, and how much that degrades when the generator's own mechanism changes.

### The frozen configuration (registered before TEST/OOD were opened)

Random Forest, feature set `keep_plus_candidate` (14 features), `n_estimators=300`,
`max_depth=None`, `min_samples_leaf=20`, `random_state=42` — for height +7d/+14d/+30d and for
`days_until_30cm` framing (B) (direct regression, right-censoring capped at 120 days). Frozen
comparators: `persistence`, `seasonal_climatology`, `mechanistic`, `mechanistic_days_until_30cm`,
Ridge, HistGradientBoosting, and framing (A) (height-trajectory-derived `days_until_30cm`) as a
secondary analysis only.

### Height MAE — Baseline vs. Ridge vs. Random Forest vs. HistGB, VALIDATION → TEST → OOD

| | +7d VALIDATION | +7d TEST | +7d OOD | +14d VALIDATION | +14d TEST | +14d OOD | +30d VALIDATION | +30d TEST | +30d OOD |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Best baseline | mechanistic 11.17 | mechanistic 12.40 | mechanistic **10.72** | seasonal 16.26 | seasonal 17.70 | mechanistic 17.23 | seasonal 15.96 | seasonal 17.70 | mechanistic 28.38 |
| Ridge | 12.78 | 13.68 | 14.82 | 15.38 | 16.66 | 18.59 | 15.97 | 17.42 | 19.40 |
| **Random Forest (frozen)** | **9.47** | **10.82** | 13.34 | **11.14** | **12.91** | 16.57 | **13.50** | **15.14** | 19.03 |
| HistGB | 9.63 | 10.89 | 13.31 | 11.54 | 13.25 | 16.48 | 13.70 | 15.33 | 19.10 |

### `days_until_30cm` MAE (days) — VALIDATION → TEST → OOD

| | VALIDATION | TEST | OOD |
|---|---:|---:|---:|
| `mechanistic_days_until_30cm` | 14.39 | 13.28 | 8.99 |
| Ridge (framing B) | 18.48 | 14.25 | 12.09 |
| **Random Forest (frozen, framing B)** | **11.80** | **10.24** | 7.32 |
| HistGB (framing B) | 12.13 | 10.38 | 7.55 |
| Framing (A), secondary analysis | — (not run in Phase 7) | 11.76 | — (not run) |

**Read the OOD `days_until_30cm` column with the caveat below before treating it as an
improvement** — every model's OOD number here looks *better* than its TEST number, height's does
not, and that gap is diagnosed just below, not glossed over.

### Rolling-origin backtest (frozen Random Forest, refit per origin — no hyperparameter changed)

5 origins, growing training window (19 181 → 27 893 rows), 30-day embargo, 120-day evaluation
window, walking from 2025-03-01 to 2026-03-01 (intentionally re-partitioning by calendar date, not
by the original train/validation/test labels — see the script's docstring for why that is the
correct design for a walk-forward backtest and not a TEST-leakage violation).

| Origin | n_train | n_eval | MAE +7d | MAE +14d | MAE +30d | `days_until_30cm` MAE |
|---|---:|---:|---:|---:|---:|---:|
| 2025-03-01 | 19 181 | 3 031 | 7.94 | 9.30 | 11.49 | 13.54 |
| 2025-06-01 | 21 664 | 2 320 | 7.32 | 9.44 | 12.35 | 11.57 |
| 2025-09-01 | 23 986 | 2 994 | 11.70 | 14.05 | 17.07 | 9.98 |
| 2025-12-01 | 25 587 | 3 091 | **13.73 (worst)** | **15.21 (worst)** | 17.06 | 9.17 |
| 2026-03-01 | 27 893 | 3 098 | 8.02 | 9.65 | 11.80 | **15.29 (worst)** |
| **Mean ± std** | | | 9.74 ± 2.52 | 11.53 ± 2.56 | 13.95 ± 2.56 | 11.91 ± 2.26 |
| Median | | | 8.02 | 9.65 | 12.35 | 11.57 |

**Reading it honestly:** the model is not temporally stable. The +7d MAE swings from 7.32 to 13.73
cm across origins — an 88% relative range — and the worst origin for height (2025-12-01) is not
the worst origin for `days_until_30cm` (2026-03-01), so no single "bad period" explains every
metric at once. The 2025-12-01 origin's eval window is mostly wet-season rows (2 353/3 091); its
own wet-vs-dry split (14.04 vs 12.74 cm) shows the season split alone doesn't fully explain the
spike either — some of this instability is not attributable to season, and is left unexplained
rather than rationalised after the fact.

### OOD one-shot: how much does the frozen model degrade under a different growth mechanism?

| | Random Forest TEST→OOD | HistGB TEST→OOD | mechanistic TEST→OOD |
|---|---:|---:|---:|
| +7d height | 10.82→13.34 (**+23.3%**) | 10.89→13.31 (+22.2%) | 12.40→10.72 (−13.5%) |
| +14d height | 12.91→16.57 (**+28.4%**) | 13.25→16.48 (+24.4%) | 20.18→17.23 (−14.6%) |
| +30d height | 15.14→19.03 (**+25.7%**) | 15.33→19.10 (+24.5%) | 31.27→28.38 (−9.3%) |
| `days_until_30cm` | 10.24→7.32 (−28.5%, see caveat) | 10.38→7.55 (−27.3%, see caveat) | 13.28→8.99 (−32.3%, see caveat) |

**The height row is the trustworthy signal, and it says the learned models degrade 22–28% under
a mechanism change while the physics-based `mechanistic` baseline actually *improves*.** The
`days_until_30cm` row's apparent improvement for every model (including the baseline) is a
composition artifact, not a robustness result: **62.7%** of OOD rows already have `height_cm ≥
30cm` at the anchor (vs **38.7%** on TEST), and **51.9%** of OOD's non-censored
`days_until_30cm` targets are exactly `0` (vs **34.2%** on TEST) — the OOD holdout's task is
mechanically easier because more of it is already-answered "0 days", not because any model
transfers better to the new mechanism. This is exactly the kind of number this report is obligated
to flag rather than present at face value.

### Ablation (VALIDATION, frozen Random Forest, one feature group removed at a time)

Reference (full `keep_plus_candidate`): height MAE 9.47 / 11.14 / 13.50 cm; `days_until_30cm` MAE
11.80 d.

| Group removed | Δ height MAE +7d | Δ +14d | Δ +30d | Δ `days_until_30cm` MAE |
|---|---:|---:|---:|---:|
| A. height/history (`height_cm`, `height_prev_cm`, `weekly_growth_cm`, `height_lag2_cm`) | **+3.24** | **+2.83** | **+1.50** | **+5.42** |
| B. `days_since_rocada` / cycle (+`rocada_in_new_cycle`, `operational_status`) | +0.13 | +0.11 | +0.05 | +1.89 |
| C. temperature/GDD (`gdd_week_tb15_c`, `tmin_week_c`) | +0.16 | +0.31 | +0.47 | +0.76 |
| D. rain/water deficit (`rain_30d_mm`, `water_deficit_30d`, `water_deficit_90d`) | +0.01 | +0.04 | −0.06 | +0.72 |
| E. additional candidates (`dry_season_flag`, `vegetation_type`) | +0.01 | +0.16 | +0.26 | +0.25 |

**The model is almost entirely anchored on the current-height/history group** — removing it costs
2–7× more MAE than removing any other single group, at every horizon, confirming the same
conclusion Phase 7's permutation importance already reached. The cycle/roçada group is the clear
second-most-important, especially for `days_until_30cm` (+1.89 d — the largest non-height
contributor there). Weather (temperature and rain/water-deficit) and the additional candidates
each contribute small, real, but individually minor amounts. **This ranking does not change
`keep_plus_candidate` as the frozen feature set** — it explains it.

### 95% confidence intervals on the TEST metrics (block bootstrap by `trecho_id`, 500 resamples)

| Metric | Point estimate | 95% CI |
|---|---:|---:|
| Height MAE +7d | 10.82 | [10.43, 11.18] |
| Height MAE +14d | 12.91 | [12.47, 13.34] |
| Height MAE +30d | 15.14 | [14.71, 15.62] |
| `days_until_30cm` MAE | 10.24 | [9.67, 10.76] |

These intervals describe uncertainty in the **metric itself** (would a different sample of the
same 118 trechos give a noticeably different MAE?) — not a prediction interval for any individual
`trecho`'s forecast, which is Phase 9's scope.

### TEST stratification (frozen Random Forest, height +7d)

| Breakdown | Worst bucket | Best bucket |
|---|---|---|
| Nível | **Nível 3, MAE 19.97 cm** (n=2 190) | Nível 1, MAE 4.13 cm (n=1 284) |
| Season | Wet (Oct–Mar), MAE 12.70 cm (n=3 914) | Dry (Apr–Sep), MAE 8.42 cm (n=3 075) |
| Roçada proximity | Long (>60d), MAE 13.16 cm (n=1 363) | Recent (<14d), MAE 4.64 cm (n=1 457) |
| `operational_status` | `not-ready`, MAE 10.86 cm (n=1 326) | `ready`, MAE 10.81 cm (n=5 663) — negligible difference |
| History completeness | Sparse (≤5 weeks), MAE 11.40 cm (n=1 041) | Dense (≥6 weeks), MAE 10.72 cm (n=5 948) |

**The model fails most on already-tall vegetation (Nível 3) that has gone a long time since its
last roçada, during the wet season** — the exact combination where growth dynamics are least
constrained by a fresh anchor height and most exposed to accumulated, harder-to-track hydric and
thermal effects. `operational_status` barely matters once height and roçada timing are already in
the model; history completeness matters, but only mildly.

### Leakage / sanity — re-checked independently in this phase

`true_height_cm` absent from the frozen feature list ✓; Phase 5's `leakage_audit.json` still
reports `overall_pass: true` (re-read, not re-derived) ✓; no TEST or OOD row was ever passed to a
`.fit()` call anywhere in `scripts/evaluate_phase8.py` ✓; 0 negative and 0 >150cm height
predictions across every horizon on both TEST and OOD ✓; the operational threshold is still 30 cm
everywhere (`ml_common.py`, `height_trajectory_days.py`) ✓.

### Final decision (Phase 8)

- **Random Forest (`keep_plus_candidate`, the Phase 7 frozen configuration) remains the
  recommended model.** It wins every height horizon and `days_until_30cm` on TEST, by a
  comfortable and stable margin over the best baseline (mechanistic/seasonal_climatology) and over
  Ridge, and is statistically indistinguishable from HistGB (their 95%-CI-scale differences are
  smaller than either model's own bootstrap width).
- **Margin over the best baseline on TEST:** +7d 12.40→10.82 cm (**−12.7%**), +14d 17.70→12.91 cm
  (**−27.1%**), +30d 17.70→15.14 cm (**−14.5%**), `days_until_30cm` 13.28→10.24 d (**−22.9%**).
- **Degradation under OOD (mechanism shift):** height MAE worsens by **22–28%** across horizons.
  `days_until_30cm`'s apparent OOD improvement is a composition artifact (see above), not a real
  gain — the height numbers are the ones to trust here.
- **Is that degradation acceptable for a prototype?** For a self-contained, explicitly-labelled
  synthetic prototype whose stated purpose is methodology, not field deployment — yes, with the
  caveat stated loudly: it is evidence the model has learned some mechanism-specific structure
  from `main`+`seed2`'s shared regrowth curve family, not only universal, mechanism-agnostic
  relationships. It would **not** be an acceptable, unremarked degradation for a model being
  represented as field-ready.
- **Main failure mode:** already-tall (Nível 3), long-since-roçada vegetation in the wet season —
  precisely the regime where the anchor height carries the least information and hydric/thermal
  history the most, and where the OOD mechanism shift also bites hardest (structurally, the same
  regime: growth dynamics far from the last reset).
- **Evidence of overfitting?** No, in the standard train-vs-validation sense — Phase 7 found a
  *negative* gap (VALIDATION MAE slightly lower than TRAIN's) for every configuration. But the
  rolling-origin instability (7.3–13.7 cm MAE swing across origins) and the OOD degradation
  together are evidence of a **narrower** kind of overfitting: fitting to the specific
  `main`+`seed2` mechanism family rather than to universal growth relationships, which is a
  different failure than the classic train/validation gap and would not have been visible from
  that gap alone.
- **Evidence of excessive dependence on the synthetic generator?** Yes — the 22–28% OOD height
  degradation, concentrated exactly where the generator's own hidden hydric/thermal mechanisms
  matter most, is direct evidence that some of the model's learned signal is generator-mechanism-
  specific rather than a general height-growth relationship. The mechanistic baseline's own
  *improvement* under the same mechanism shift reinforces this: a model built from the domain's
  physical structure (thermal accumulation, decay-since-cut) transfers better than a model that
  learned the shape of one particular generator's regrowth curves.
- **Claims this evaluation supports:** "on this synthetic TEST split, the frozen Random Forest
  configuration beats every implemented baseline and the Ridge/HistGB alternatives, at every
  height horizon and for `days_until_30cm`, with a stable ranking across a 500-resample block
  bootstrap"; "the same model degrades materially (22–28% height MAE) when the underlying growth
  mechanism changes, while a mechanistic baseline does not"; "the model is not temporally stable
  across rolling-origin windows even while never crossing physically absurd bounds"; "current
  height and roçada-cycle timing account for the large majority of the model's predictive power,
  by ablation".
- **Claims this evaluation does NOT support:** anything about real vegetation, real weather, or
  the actual SP-021/Rodoanel Oeste corridor — every row scored above, in VALIDATION, TEST, and
  OOD alike, is `provenance=synthetic`; a claim that the model "generalises well" (OOD height MAE
  is worse, not better, than TEST, by a double-digit percentage); a claim that
  `days_until_30cm`'s apparent OOD improvement reflects genuine robustness (it is a target-
  composition artifact, quantified above); a claim of full temporal stability (rolling-origin
  disagrees); a prediction interval for any individual `trecho` (that is Phase 9's scope — the
  bootstrap CIs here describe the evaluation metric's own uncertainty, not a per-`trecho` forecast
  band).

Reviewed: 2026-09-10 (Phase 8 section).
