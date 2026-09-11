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

> **Update (B2 — Codex independent review, R01/R02/R04 remediation).** Every `days_until_30cm`
> number in this document was recomputed after fixing the target's construction (label-window
> leakage across split boundaries; a future `roçada` silently crossed to find a later, unrelated
> crossing; right-censoring conflated with simply running out of dataset — see
> `reports/target-construction.md`). **All height numbers (`target_height_plus_{7,14,30}d_cm`) are
> confirmed unchanged** — re-verified byte-for-byte identical counts/offsets, not re-derived from
> scratch. `days_until_30cm` MAEs are generally higher than the pre-fix version reported (e.g. RF
> TEST 10.24→11.17 d) because the scored population is now smaller and honestly labelled (event
> rows only, height<30 only) — this is not evidence the model got worse; it is evidence the old
> population included rows whose "known" answer was not actually known. Sections below are marked
> "rewritten (B2)" wherever a number changed; unmarked days numbers were re-verified unchanged
> (none were, in this document — every days table needed at least one value updated).

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

**Rewritten (B2 — Codex R01/R02/R04 remediation).** The numbers below replace an earlier version
that scored a right-censored-and-capped-at-120 population; that construction had a real,
quantified label-window leak across split boundaries (R01), silently crossed a future `roçada` to
answer a question about the pre-cut trajectory (R02), and conflated three different kinds of
"we don't know the true time-to-event" into one fabricated `120` label (R03/R04). See
`reports/target-construction.md` for the corrected construction. **MAE/median/±3d/±7d are now
computed ONLY on `height_cm < 30` anchors whose `target_days_until_30cm_outcome == "event"`** — a
genuinely known, exact time-to-event — never on `censored_intervention`/`censored_horizon`/
`censored_end_of_followup` rows, whose true time-to-event is unknown and is never coerced into a
number (not even 120).

| Metric | `naive_days_until_30cm` | `mechanistic_days_until_30cm` |
|---|---:|---:|
| n event rows (known truth, height<30) | 2 889 | 2 889 |
| n scored (baseline also gave a number) | 2 012 | **2 550** |
| MAE (days) | 25.10 | **19.36** |
| median absolute error (days) | 16.17 | **13.16** |
| within ±3 days | 11.0 % | **14.5 %** |
| within ±7 days | 28.1 % | **31.9 %** |
| model predicted censored while event was known | 877 (30.4 %) | **339 (11.7 %)** |
| outcome counts below 30cm (n=3 524) | event 2 889 · intervention 218 · horizon 73 · end-of-followup 344 | (same population) |
| model also predicted censored on the 635 non-event rows | 230 (36.2 %) | **248 (39.1 %)** |
| fallback rate used | train median, 9.52 cm/wk, n=13 097 same-cycle rows | train global since-cut median, 0.68 cm/day (never triggered — 118/118 trechos had a local `rate_i`) |
| rows using the fallback rate | 553 | 0 |

**Censoring is handled explicitly, not dropped, for either baseline's population restriction — but
now the four-way taxonomy above replaces the old binary "censored".** MAE/median/±3d/±7d are
computed only on rows where *both* the true value and the baseline's prediction are real numbers;
every other row is counted in the outcome breakdown, not hidden.

**`mechanistic_days_until_30cm` remains the stronger of the two, on balance** — a substantially
lower MAE (19.36 vs 25.10 days), better median error (13.16 vs 16.17), more rows scored (2 550 vs
2 012), and better agreement on the non-event population (39.1 % vs 36.2 %). Both MAEs are higher
than the versions reported before this fix (17.40 / 14.39) — this is not evidence of a worse
model; it reflects a smaller, honestly-labelled population (2 889 known events vs the earlier
5 237, which included rows whose "known" answer had in fact crossed a future `roçada` or been
capped at 120). Both baselines still share the same structural weakness: **the most informative
failure mode is not seeing a future `roçada` coming** — a model-predicts-censored-when-event-known
rate of 30.4 % (naive) / 11.7 % (mechanistic) is largely explained by the same mechanism as before.

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

**Rewritten (B2).** Nível 3 (`height_cm > 30`) no longer appears here at all — it is now excluded
by construction (`height_cm < 30` is part of the scored population's own definition), not shown as
a trivial `0.00` row.

| Nível | n (naive) | `naive` MAE | n (mechanistic) | `mechanistic` MAE |
|---|---:|---:|---:|---:|
| 0 (not-ready) | 348 | 25.84 | 450 | **19.01** |
| 1 | 447 | 29.85 | 567 | **20.61** |
| 2 | 1 217 | 23.15 | 1 533 | **19.00** |

`mechanistic` still scores **more rows** in every stratum (its trecho-level historical rate gives
an answer more often than `naive`'s single-week snapshot) and still has a **lower MAE in every
stratum** — the same qualitative pattern as before this fix, at higher MAE values (a smaller,
honestly-labelled population, not a worse model).

### `days_until_30cm` MAE (days), by season / by `roçada` proximity

**Rewritten (B2).**

| Season | n (naive) | naive MAE | n (mechanistic) | mechanistic MAE | | `days_since_rocada` | n (naive) | naive MAE | n (mechanistic) | mechanistic MAE |
|---|---:|---:|---:|---:|---|---|---:|---:|---:|---:|
| wet (Oct–Mar) | 677 | 12.81 | 768 | **11.64** | | recent (< 14 d) | 716 | 26.49 | 760 | **17.25** |
| dry (Apr–Sep) | 1 335 | 31.34 | 1 782 | **22.68** | | mid (14–60 d) | 1 000 | 24.72 | 1 315 | **17.15** |
| | | | | | | long (> 60 d) | 296 | **23.04** | 475 | 28.86 |

`mechanistic` is better in every stratum except `long (> 60 d)`, where `naive` edges ahead
(23.04 vs 28.86) — the same exception as before this fix: a trecho long past its last cut is close
to its own plateau, where recent observed growth (what `naive` uses) is already a good proxy,
while `mechanistic`'s historical average rate slightly overstates ongoing growth for a trecho that
has already slowed down.

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
| `target_days_until_30cm` not a clean event, of rows below 30cm (B2: `censored_intervention` + `censored_horizon` + `censored_end_of_followup`, n=3 524 below-30cm rows) | 635 (18.0 % of below-30cm rows; 11.5 % of all 5 500 validation rows) |

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
- **`days_until_30cm` (B2 — rewritten): `mechanistic_days_until_30cm`** is now the stronger of the
  two on **every** metric — lower MAE (19.36 vs 25.10 days), lower median error (13.16 vs 16.17),
  more rows scored (2 550 vs 2 012), better within-±3d/±7d shares (14.5%/31.9% vs 11.0%/28.1%), and
  better agreement on the non-event rows (39.1 % vs 36.2 %). Before this fix, `naive` had kept a
  narrow edge on median error and the ±3d/±7d shares — that edge is gone on the corrected,
  event-only population. Both share the same structural ceiling: **neither can see a future
  `roçada` coming.**

**Did the mechanistic baseline add real value?** **Yes, partially, and it is the honest kind of
partial.** It is not a strict improvement for height — it does not dominate `seasonal_climatology`
at longer horizons — but it is the single best height predictor at the horizon that matters most
for a weekly-capture operational cadence (+7 d), it now wins **every** `days_until_30cm` metric
against `naive` (B2 — see above, no exceptions remain), and every one of its stratified wins
(Nível, season, roçada-proximity, in §"Stratified metrics") lines up with what the formula was
built to capture (thermal response, the regrowth lag), not a coincidence of tuning — because the
formula was fixed before this evaluation ran once, not iterated against it.

**What Phase 7 must beat, concretely (best baseline per row):**

| | +7 d height MAE | +14 d height MAE | +30 d height MAE | `days_until_30cm` MAE | median | ±3d | ±7d |
|---|---:|---:|---:|---:|---:|---:|---:|
| Best baseline | `mechanistic`: **11.17 cm** | `seasonal_climatology`: **16.26 cm** | `seasonal_climatology`: **15.96 cm** | `mechanistic`: **19.36 d** | `mechanistic`: **13.16 d** | `mechanistic`: **14.5 %** | `mechanistic`: **31.9 %** |

All on the **same** validation split, never on `test` or `ood`. `days_until_30cm` columns rewritten
(B2) — `mechanistic` now wins every one of them (see above), not split with `naive`.

Reviewed: 2026-09-10 (originally); B2 remediation pass, same day.

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
`days_until_30cm` framing (B) (direct regression). **Current censoring policy (B2 — Codex
R01/R02/R04 remediation, superseding an earlier "right-censoring capped at 120 days" description
of this same frozen hyperparameter set):** fit and scored only on `height_cm < 30` anchors with a
genuine `event` outcome; `censored_intervention`/`censored_horizon`/`censored_end_of_followup`
rows are excluded, never coerced into 120 or any other number — see
`reports/target-construction.md`. Frozen comparators: `persistence`, `seasonal_climatology`,
`mechanistic`, `mechanistic_days_until_30cm`, Ridge, HistGradientBoosting, and framing (A)
(height-trajectory-derived `days_until_30cm`) as a secondary analysis only.

### Height MAE — Baseline vs. Ridge vs. Random Forest vs. HistGB, VALIDATION → TEST → OOD

**Correction (B2.1 — Codex independent review):** `scripts/evaluate_phase8.py`'s OOD section
(`ood_height`) has never computed a Ridge entry — only `mechanistic`/`random_forest`/
`gradient_boosting`. The Ridge OOD cells previously shown here (14.82 / 18.59 / 19.40) were not
reproducible from this script's output and are removed rather than re-presented as a current
result; VALIDATION/TEST Ridge cells are unaffected (both are computed by this script).

| | +7d VALIDATION | +7d TEST | +7d OOD | +14d VALIDATION | +14d TEST | +14d OOD | +30d VALIDATION | +30d TEST | +30d OOD |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Best baseline | mechanistic 11.17 | mechanistic 12.40 | mechanistic **10.72** | seasonal 16.26 | seasonal 17.70 | mechanistic 17.23 | seasonal 15.96 | seasonal 17.70 | mechanistic 28.38 |
| Ridge | 12.78 | 13.68 | *(not computed by Phase 8)* | 15.38 | 16.66 | *(not computed by Phase 8)* | 15.97 | 17.42 | *(not computed by Phase 8)* |
| **Random Forest (frozen)** | **9.47** | **10.82** | 13.34 | **11.14** | **12.91** | 16.57 | **13.50** | **15.14** | 19.03 |
| HistGB | 9.63 | 10.89 | 13.31 | 11.54 | 13.25 | 16.48 | 13.70 | 15.33 | 19.10 |

### `days_until_30cm` MAE (days) — VALIDATION → TEST → OOD

**Rewritten (B2).** Scored only on `height_cm < 30` anchors with a known `event` outcome (see
above). Ridge's TEST/OOD numbers are not computed by `scripts/evaluate_phase8.py` (only VALIDATION,
via the Phase 7 addendum) — reported as VALIDATION-only rather than left as an unverifiable
carry-over number.

| | VALIDATION | TEST | OOD |
|---|---:|---:|---:|
| `mechanistic_days_until_30cm` | 19.36 | 18.45 | 19.42 |
| Ridge (framing B) | 15.76 | *(not computed by Phase 8)* | *(not computed by Phase 8)* |
| **Random Forest (frozen, framing B)** | **13.12** | **11.17** | 11.20 |
| HistGB (framing B) | 13.19 | 11.48 | 11.12 |
| Framing (A), secondary analysis | 16.71 | 13.95 | — (not run) |

**The OOD `days_until_30cm` column no longer shows the large, artifact-driven "improvement" the
pre-fix version reported.** Under the old (R01/R02/R04-affected) construction, TEST→OOD looked
like a 27–32% *improvement* for every model — a composition artifact (below), not robustness.
With `height_cm >= 30` anchors now excluded from scoring entirely (not just flagged), that
artifact's main source is gone: TEST→OOD is now +0.3% for Random Forest and −3.1% for HistGB
(essentially flat, within noise), and +5.3% for the mechanistic baseline — small numbers, in a
mixed direction, not the dramatic one-sided "improvement" seen before.

### Rolling-origin backtest (frozen Random Forest, refit per origin — no hyperparameter changed)

5 origins, growing training window (19 181 → 27 893 rows), 30-day embargo, 120-day evaluation
window, walking from 2025-03-01 to 2026-03-01 (intentionally re-partitioning by calendar date, not
by the original train/validation/test labels — see the script's docstring for why that is the
correct design for a walk-forward backtest and not a TEST-leakage violation).

**`days_until_30cm` MAE column rewritten twice: B2, then corrected again in B2.1.** Height columns
are unchanged throughout (same code, same rows, Codex-confirmed offsets never exceed 28 days,
safely inside the existing 30-day embargo — no change was needed or made to the height rolling-
origin methodology).

**B2.1 correction (independent review):** the first rolling-origin implementation (used for B2's
numbers, now superseded) partitioned each origin's TRAIN rows by the anchor's own date
(`as_of_date <= origin`), but never limited how far a row's *label* could look forward — a
`target_days_until_30cm` label can point up to 120 days past its anchor, which could land inside
the very eval window that origin was about to be scored on, even for an anchor dated well before
`origin`. The backtest was re-run with each origin's days-model training set additionally
restricted to `event_date < eval_start` (`eval_start = origin + 30-day embargo`) — see
`reports/target-construction.md` and `src/greenv_vegpred/models/ml_common.py::rolling_origin_days_train_rows`.
This is the same category of fix as R01 (fixed-split), applied to the rolling-origin loop's own
per-origin calendar partitioning; it does not affect the fixed TRAIN/VALIDATION/TEST split's own
numbers (§ above), which are unchanged, as expected.

| Origin | n_train | n_eval | MAE +7d | MAE +14d | MAE +30d | `days_until_30cm` MAE |
|---|---:|---:|---:|---:|---:|---:|
| 2025-03-01 | 19 181 | 3 031 | 7.94 | 9.30 | 11.49 | 16.68 |
| 2025-06-01 | 21 664 | 2 320 | 7.32 | 9.44 | 12.35 | 10.44 |
| 2025-09-01 | 23 986 | 2 994 | 11.70 | 14.05 | 17.07 | 8.27 |
| 2025-12-01 | 25 587 | 3 091 | **13.73 (worst)** | **15.21 (worst)** | 17.06 | 9.06 |
| 2026-03-01 | 27 893 | 3 098 | 8.02 | 9.65 | 11.80 | **18.71 (worst)** |
| **Mean ± std** | | | 9.74 ± 2.52 | 11.53 ± 2.56 | 13.95 ± 2.56 | 12.63 ± 4.24 |
| Median | | | 8.02 | 9.65 | 12.35 | 10.44 |

Per-origin leakage audit (rows whose label reached into that origin's own eval window, after the
B2.1 fix): **0 in all five origins** — see §"Rolling-origin leakage audit" in
`reports/target-construction.md` for the full before/after counts.

**Reading it honestly:** the model is not temporally stable, and this got MORE visible for
`days_until_30cm` after the B2/B2.1 fixes, not less — its std nearly doubled (4.24 vs the original,
pre-B2 2.26) and its worst/best spread widened (8.27–18.71 vs the original 9.17–15.29). This is a
genuine, structural finding, not an artifact of the fix: the smaller, honestly-labelled event-only
population is more sensitive to which trechos happen to fall in each origin's window. The +7d
height MAE swings from 7.32 to 13.73 cm across origins — an 88% relative range — and the worst
origin for height (2025-12-01) is not the worst origin for `days_until_30cm` (2026-03-01, before
and after both fixes), so no single "bad period" explains every metric at once. The 2025-12-01
origin's eval window is mostly wet-season rows (2 353/3 091); its own wet-vs-dry split (14.04 vs
12.74 cm) shows the season split alone doesn't fully explain the spike either — some of this
instability is not
attributable to season, and is left unexplained rather than rationalised after the fact.

### OOD one-shot: how much does the frozen model degrade under a different growth mechanism?

| | Random Forest TEST→OOD | HistGB TEST→OOD | mechanistic TEST→OOD |
|---|---:|---:|---:|
| +7d height | 10.82→13.34 (**+23.3%**) | 10.89→13.31 (+22.2%) | 12.40→10.72 (−13.5%) |
| +14d height | 12.91→16.57 (**+28.4%**) | 13.25→16.48 (+24.4%) | 20.18→17.23 (−14.6%) |
| +30d height | 15.14→19.03 (**+25.7%**) | 15.33→19.10 (+24.5%) | 31.27→28.38 (−9.3%) |
| `days_until_30cm` (rewritten, B2) | 11.17→11.20 (+0.3%) | 11.48→11.12 (−3.1%) | 18.45→19.42 (+5.3%) |

**The height row is the trustworthy signal, and it says the learned models degrade 22–28% under
a mechanism change while the physics-based `mechanistic` baseline actually *improves*.** The
`days_until_30cm` row **no longer shows the large, one-sided "improvement" the pre-fix version
reported** (previously −27% to −32%). That earlier number was a composition artifact: **62.7%** of
OOD rows already have `height_cm ≥ 30cm` at the anchor (vs **38.7%** on TEST) — a fact about the
raw data, unaffected by this fix — and under the OLD scoring, those already-critical rows'
trivially-easy "0 days" answers were still counted, making OOD's task mechanically easier. **B2's
fix removes that artifact at the source** (height≥30 anchors are now excluded from scoring
entirely, not merely flagged), so what remains is small and in a mixed direction (+0.3% / −3.1% /
+5.3%) — closer to noise than to a real robustness signal either way, and no longer something this
report needs to caveat as misleading.

### Ablation (VALIDATION, frozen Random Forest, one feature group removed at a time)

Reference (full `keep_plus_candidate`): height MAE 9.47 / 11.14 / 13.50 cm; `days_until_30cm` MAE
**13.12 d (rewritten, B2 — was 11.80 d before the fix)**.

**`days_until_30cm` column rewritten (B2).** Height columns unchanged.

| Group removed | Δ height MAE +7d | Δ +14d | Δ +30d | Δ `days_until_30cm` MAE |
|---|---:|---:|---:|---:|
| A. height/history (`height_cm`, `height_prev_cm`, `weekly_growth_cm`, `height_lag2_cm`) | **+3.24** | **+2.83** | **+1.50** | **+2.70** |
| B. `days_since_rocada` / cycle (+`rocada_in_new_cycle`, `operational_status`) | +0.13 | +0.11 | +0.05 | +0.03 |
| C. temperature/GDD (`gdd_week_tb15_c`, `tmin_week_c`) | +0.16 | +0.31 | +0.47 | +1.64 |
| D. rain/water deficit (`rain_30d_mm`, `water_deficit_30d`, `water_deficit_90d`) | +0.01 | +0.04 | −0.06 | +1.77 |
| E. additional candidates (`dry_season_flag`, `vegetation_type`) | +0.01 | +0.16 | +0.26 | +0.51 |

**The model is almost entirely anchored on the current-height/history group for height** —
removing it costs 2–7× more MAE than removing any other single group, at every horizon, confirming
the same conclusion Phase 7's permutation importance already reached. **For `days_until_30cm`,
the ranking changed materially after the fix:** the `days_since_rocada`/cycle group's contribution
collapsed from +1.89 d (pre-fix) to essentially zero (+0.03 d) — an expected, honest consequence
of B2 itself, not a coincidence: the population the model is now trained and scored on excludes
every trajectory a `roçada` interrupted, so the model no longer needs cycle-timing information to
compensate for post-intervention crossings it can no longer see. With that confound removed, the
climate groups (temperature/GDD, rain/water-deficit) show up as **more** important than before
(+0.76→+1.64 d and +0.72→+1.77 d respectively) — a genuinely different, and arguably more
interpretable, picture of what actually drives the corrected `days_until_30cm` regressor.
**This ranking does not change `keep_plus_candidate` as the frozen feature set** — it explains it
differently than before.

### 95% confidence intervals on the TEST metrics (block bootstrap by `trecho_id`, 500 resamples)

| Metric | Point estimate | 95% CI |
|---|---:|---:|
| Height MAE +7d | 10.82 | [10.43, 11.18] |
| Height MAE +14d | 12.91 | [12.47, 13.34] |
| Height MAE +30d | 15.14 | [14.71, 15.62] |
| `days_until_30cm` MAE (rewritten, B2) | 11.17 | [10.61, 11.73] |

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
  Ridge. **Corrected claim about HistGB (Codex review finding):** RF's point estimate is close to
  HistGB's (`days_until_30cm` 11.17 vs 11.48 d on TEST; height differences are similarly small at
  every horizon) — but this was never a formal paired significance test, only two separate
  bootstrap intervals compared by eye, so "statistically indistinguishable" overstated what was
  actually checked. The honest claim: **the two models are close relative to RF's own sampling
  uncertainty** (RF's `days_until_30cm` 95% CI is [10.61, 11.73], a half-width of ≈0.56 d — larger
  than the 0.31 d gap to HistGB's point estimate), not a demonstrated equivalence.
- **Margin over the best baseline on TEST (B2 — `days_until_30cm` rewritten):** +7d 12.40→10.82 cm
  (**−12.7%**), +14d 17.70→12.91 cm (**−27.1%**), +30d 17.70→15.14 cm (**−14.5%**),
  `days_until_30cm` 18.45→11.17 d (**−39.4%**, was −22.9% before this fix — a larger margin now,
  against a correspondingly harder, honestly-labelled baseline comparison, not a claim that the
  model itself improved).
- **Degradation under OOD (mechanism shift):** height MAE worsens by **22–28%** across horizons.
  `days_until_30cm` now shows a small, near-flat TEST→OOD change (+0.3% RF, −3.1% HistGB — see
  above); the earlier large *apparent improvement* was a composition artifact that this fix
  removes at the source, not something to still read as "not a real gain" — there is no longer a
  large number to explain away here.
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
  configuration beats every implemented baseline and Ridge (`days_until_30cm` was not evaluated
  for Ridge on TEST/OOD by this script) at every height horizon and for `days_until_30cm`, with a
  stable ranking across a 500-resample block bootstrap"; "the same model degrades materially
  (22–28% height MAE) when the underlying growth mechanism changes, while a mechanistic baseline
  does not"; "the model is not temporally stable across rolling-origin windows even while never
  crossing physically absurd bounds"; "current height accounts for the large majority of the
  model's predictive power for height, by ablation; for `days_until_30cm`, current height is also
  the single largest driver, but roçada-cycle timing is no longer a major one post-fix — see the
  ablation section's explanation".
- **Claims this evaluation does NOT support:** anything about real vegetation, real weather, or
  the actual SP-021/Rodoanel Oeste corridor — every row scored above, in VALIDATION, TEST, and
  OOD alike, is `provenance=synthetic`; a claim that the model "generalises well" (OOD height MAE
  is worse, not better, than TEST, by a double-digit percentage; `days_until_30cm`'s near-flat
  TEST→OOD change is small and mixed-direction, not evidence of robustness either); a claim of
  full temporal stability (rolling-origin disagrees, and got more visible for `days_until_30cm`
  after this fix, not less); a prediction interval for any individual `trecho` (that is Phase 9's
  scope — the bootstrap CIs here describe the evaluation metric's own uncertainty, not a
  per-`trecho` forecast band); a claim that RF and HistGB were shown statistically indistinguishable
  (never formally tested — see the corrected wording above).

Reviewed: 2026-09-10 (Phase 8 section, originally; B2 remediation pass, same day).
