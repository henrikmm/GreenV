# Exploratory analysis and feature_row (Phase 5)

**Phase 5 artifact of [`docs/VEGETATION_PREDICTION_TASK.md`](../../../../docs/VEGETATION_PREDICTION_TASK.md).**
Everything here is built from **synthetic** height data (Phase 4: `main`, `seed2`, `ood`,
`provenance = synthetic`) driven by **real** weather (Phase 3, `provenance = weather_real`). This
document describes the generator's behaviour and the feature matrix built from it — **not** the
real SP-021 verge. No claim here is a claim about the field.

Companion artifacts: [`data/features/feature_row_dev.csv`](../data/features/feature_row_dev.csv)
(git-ignored, regenerate with `scripts/build_features.py`), `feature_row_ood_holdout.csv`,
`leakage_audit.json`, `build_stats.json`, `eda_stats.json`, and the frozen classification in
[`src/greenv_vegpred/features/feature_spec.py`](../src/greenv_vegpred/features/feature_spec.py).

---

## 1. What was combined, and how

`feature_row` joins, per `(dataset, trecho_id, as_of_date)`:

- **Phase 4 synthetic observations** (`main`, `seed2` → the *dev* set; `ood` → a fully separate
  *holdout*): `observed_height_cm`, the weekly climate block the generator already attached
  (`gdd_week_tb{10,15,17}`, `tmin/tmean/tmax_week`, `rain_7/14/30d`, `shortwave_7d`, `et0_7d`,
  `water_deficit_30d`, seasonality, `vegetation_type`, `operational_status`, `blockers`,
  `days_since_rocada`), and the roçada events (`rocada_events.csv`) used to detect cycle
  boundaries.
- **Phase 3 real weather, corridor cell** (`open-meteo:era5_seamless:-23.5:-46.8`, DEC-001):
  `water_deficit_90d` (new in Phase 5 — trailing 90-day `P − ET0`, per DEC-002) and
  `relative_humidity_7d_pct` (from `weather_weekly_features.csv`; the generator did not carry
  humidity forward, so it is joined fresh here).

`true_height_cm` and `nivel_true` are carried into the output **only** under the column names
`true_height_cm_DIAGNOSTIC_ONLY` / `nivel_true_DIAGNOSTIC_ONLY`, used below for EDA context and
nowhere else. `feature_spec.py` does not list them under `KEEP` or `CANDIDATE`, and
`leakage_audit.json` asserts they are absent from the model-ready column list.

`main` and `seed2` are **stacked** into one dev table (a `dataset` column distinguishes them) —
two independent realisations of the same weather-driven mechanism over the same calendar period,
which is a defensible way to get more training signal for a V1 prototype (see §12 for how close
they actually are). `ood` is **never merged** into the dev file; it lives in a physically separate
CSV (`feature_row_ood_holdout.csv`), with `split = 'excluded'` / `split_scheme = 'ood_holdout'` on
every row, so it cannot be accidentally read as train/validation/test data.

---

## 2. Height distribution

| | min | median | mean | p95 | max |
|---|---:|---:|---:|---:|---:|
| `height_cm` (dev, usable rows) | 0.0 | 22.7 | 28.6 | 70.4 | 121.3 |

Figure: `reports/figures/eda/01_height_hist.svg`. Right-skewed, bounded — matches the Phase 4
generator's physical caps (0–150 cm) and the roçada cycle (most weeks are early-to-mid regrowth,
a long tail of un-cut trechos climbing toward the 150 cm hard ceiling).

## 3. Weekly growth

| | n | median | mean | p95 | negative % |
|---|---:|---:|---:|---:|---:|
| `weekly_growth_cm` (same-cycle rows only) | 27 584 | 7.09 | 8.53 | 26.9 | 17.9 % |

Figure: `02_growth_hist.svg`. Growth is only defined **within one roçada cycle** (§8) — 14.9 % of
usable rows are the *first* observation of a new cycle and correctly carry `weekly_growth_cm =
NULL` (§8), so this distribution excludes them by construction, not by accident. The 17.9 %
negative share is measurement noise on top of real non-negative growth (Phase 4: true growth is
never negative; only the *observed* signal can dip).

## 4. Nível 1/2/3 distribution

| Nível | 0 (not-ready) | 1 | 2 | 3 |
|---|---:|---:|---:|---:|
| % of usable rows | 18.6 | 18.3 | 30.9 | 32.2 |

Figure: `03_nivel_dist.svg`. Close to the Phase 4 dataset-level QC (main ≈ 39.7 % Nível 3
unconditional on data-source mix; here, feature-row-level and combined with `seed2`, ≈ 32 % —
the difference is `feature_row` only counts *usable* rows, i.e. excludes embargo/DEC-004 rows,
and mixes in `seed2`).

## 5. Seasonality

Mean height and mean weekly growth by month (dev, usable rows):

| Month | J | F | M | A | M | J | J | A | S | O | N | D |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| mean height (cm) | 32.3 | 34.2 | 32.0 | 29.7 | 26.1 | 21.9 | 22.9 | 28.3 | 29.2 | 27.2 | 30.4 | 31.0 |
| mean growth (cm/wk) | 11.9 | 14.9 | 13.2 | 10.0 | 6.6 | 2.8 | 2.5 | 5.9 | 8.9 | 8.5 | 9.9 | 12.4 |

Figure: `04_seasonality.svg`. **~6× swing** in growth between July and February, **emergent from
the real Phase 3 weather** (DEC-001/DEC-003) — no seasonal term was hard-coded into what produced
this table (the tiny `A_S≈0.03` photoperiod residual in the generator cannot explain a 6× swing on
its own). This confirms the Phase 4 finding at the feature-row level, post-leakage-filtering.

## 6. Growth × GDD

Binned means (decile bins of `gdd_week_tb15_c`, same-cycle rows): growth rises **monotonically**
from 1.2 cm/week (lowest-GDD decile) to 14.9 cm/week (highest) — figure `05_growth_vs_gdd.svg`.
Pearson r against `target_height_plus_7d_cm` = **0.157** (thermal features cluster 0.14–0.16;
see §11). The relationship is real and visible even after the leakage/embargo filtering.

## 7. Growth × water deficit

Binned means (decile bins of `water_deficit_30d`, negative = deficit): 7.9 cm/week in the driest
decile, 14.4 in the wettest, but **not monotone in the middle** (5.7–8.1 across the four driest-to-
middle deciles) — figure `06_growth_vs_deficit.svg`. This matches the Phase 1 hidden-sigmoid design
(`synthetic-model.md` §2) and the Phase 4 QC finding: the water effect is real but **confounded
with season/GDD** (dry season is also cold season in SP), so the raw bivariate relationship is
noisier than the GDD one. Pearson r with `target_height_plus_7d_cm` = 0.096 — real but weaker than
temperature, as Phase 1's literature review anticipated.

## 8. `days_since_rocada`

| | median | p95 |
|---|---:|---:|
| `days_since_rocada` (usable rows) | 32 | 101 |

Figure: `07_regrowth_by_tau.svg` — mean height rises with `days_since_rocada`, tracing the lag →
acceleration → deceleration shape from `synthetic-model.md` §2 (visible at the feature-row level,
not just in the Phase 4 diagnostic). Correlation with `target_height_plus_7d_cm` = 0.271 — one of
the strongest single features, and it costs nothing to compute (it is derived purely from past
roçada events).

**14.89 %** of usable rows are `rocada_in_new_cycle = 1` — the first observation after a cut,
where `height_prev_cm` / `height_lag2_cm` / `weekly_growth_cm` are `NULL` by construction (§8).

## 9. Between-`trecho` differences

| | CV |
|---|---:|
| per-trecho mean `height_cm` | 0.110 |
| per-trecho mean `weekly_growth_cm` | 0.233 |

Figure: `08_between_trecho.svg`. Persistent heterogeneity survives into the feature matrix (it is
the hidden `m_i`/`s_i`/`Hmax_i` from Phase 4, never exposed directly — §11's weak `km_mid`
correlation, ≈ −0.01, shows position along the road explains almost none of it; the heterogeneity
is per-trecho, not spatially smooth).

## 10. Missingness — how it is treated

**No value in `feature_row` is imputed.** Two distinct, separately-tracked forms of missingness:

| Source | What happens | Size |
|---|---:|---|
| A missed capture week (Phase 4 §7, `missing_week_probability`) | No `height_observation` row exists for that trecho-week → **no `feature_row` is built for it**. Not a gap-filled row. | ≈ 24–25 % of trecho-weeks (Phase 4 QC) |
| DEC-004 partial ISO week (anchor or target) | Row is **not materialised at all** (`split` is never assigned; the row does not appear in the output file) | 1.4–1.6 % of observations per dataset (`build_stats.json: excluded_dec004_or_anchor`) |
| A target horizon's own observation is missing/absent | The **row is kept**, that one `target_*` column is `NULL` | +7d 16.2 %, +14d 22.2 %, +30d 25.7 %, `days_until_30cm` censored 3.5 % |
| First observation of a new roçada cycle | The row is kept; `height_prev_cm`, `height_lag2_cm`, `weekly_growth_cm` are `NULL` (§8) | 14.9 % of usable rows |
| Temporal-split embargo (§7 of this doc) | Row is kept in the file with `split = 'excluded'` | 1 414 of 33 824 dev rows (4.2 %) |

Every one of these is a **documented absence**, never a filled-in value.

## 11. Correlations (dev, usable rows)

Pearson r of every `KEEP`/`CANDIDATE` numeric feature against `target_height_plus_7d_cm` and
`target_days_until_30cm` (negative r on the latter is expected — taller / faster-growing now means
fewer days to the 30 cm mark):

| Feature | r vs +7d height | r vs days-to-30cm |
|---|---:|---:|
| `height_cm` | **0.463** | **−0.540** |
| `weekly_growth_cm` | 0.299 | −0.334 |
| `height_prev_cm` | 0.295 | −0.433 |
| `days_since_rocada` | 0.271 | −0.310 |
| `height_lag2_cm` | 0.155 | −0.339 |
| `gdd_week_tb15_c` | 0.157 | −0.257 |
| `tmean_week_c` | 0.156 | −0.255 |
| `gdd_week_tb10_c` / `tb17_c` | 0.155 / 0.153 | −0.256 / −0.248 |
| `tmin_week_c` | 0.152 | −0.243 |
| `season_cos` | 0.147 | −0.247 |
| `tmax_week_c` | 0.140 | −0.238 |
| `dry_season_flag` | −0.126 | 0.216 |
| `rain_30d_mm` | 0.124 | −0.184 |
| `et0_7d_mm` | 0.117 | −0.210 |
| `shortwave_7d_mj_m2` | 0.114 | −0.202 |
| `rain_14d_mm` | 0.107 | −0.156 |
| `water_deficit_30d` | 0.096 | −0.122 |
| `rain_7d_mm` | 0.082 | −0.131 |
| `water_deficit_90d` | 0.059 | −0.059 |
| `week_of_year` | −0.035 | 0.010 |
| `relative_humidity_7d_pct` | 0.026 | −0.025 |
| `season_sin` | 0.017 | 0.037 |
| `weeks_observed_last_8` | −0.014 | 0.019 |
| `km_mid` | −0.011 | −0.004 |
| `cell_coverage` | 0.005 | −0.028 |

**Reading it:**
- Height/growth history dominates (as expected — persistence is a genuinely hard baseline, per
  Phase 1's Teagasc ARIMA finding). `days_since_rocada` is the strongest *non-history* feature and
  is free to compute.
- The thermal block (GDD, Tmin/Tmean/Tmax, `season_cos`) clusters at 0.14–0.16 — a real, moderate,
  consistent signal, matching Phase 1's "Tmin dominant" finding (Tonato 2010) at a coarser weekly
  grain.
- Water/rain features are real but weaker in raw correlation (masked by their covariance with
  season/GDD, §7) — exactly Phase 1's expectation.
- `relative_humidity_7d_pct`, `week_of_year`, `weeks_observed_last_8`, `km_mid`, `cell_coverage`
  are essentially uncorrelated (|r| < 0.04) — this is the numeric confirmation behind their
  `CANDIDATE` (not `KEEP`) classification (§13); it is *consistent with*, not a repeat of, Phase 1's
  "no confirmed humidity effect."
- `season_sin`/`season_cos`/`dry_season_flag` carry real signal but risk double-counting the
  thermal block per DEC-001 — flagged `CANDIDATE`, to be added one at a time in Phase 7 with the
  delta measured, not assumed.

## 12. `main` vs `seed2`

| | n | mean height | mean growth | Nível 0/1/2/3 % |
|---|---:|---:|---:|---|
| `main` | 16 194 | 28.60 | 8.50 | 18.4 / 18.4 / 30.7 / 32.6 |
| `seed2` | 16 216 | 28.55 | 8.55 | 18.8 / 18.2 / 31.1 / 31.9 |

Figure: `09_main_vs_seed2_nivel.svg`. Near-identical at the feature-row level (as at the Phase 4
dataset level) — the generator's **mechanism**, not the random seed, drives the aggregate
behaviour. This is reassuring for stacking them as one dev set (§1).

## 13. Outliers

| Check | Count |
|---|---:|
| `height_cm`, `\|z\| > 5` | **0** |
| `weekly_growth_cm`, `\|z\| > 5` | 59 (0.21 % of same-cycle rows) |

No height outliers survive (Phase 4's physical caps hold). The 59 growth outliers are the known
measurement-noise mechanism (2 % gross-outlier draws × long observation gaps, `synthetic-model.md`
§6) — left in, not removed, since removing them would be removing real (if noisy) observations
without a principled rule; Phase 7 model choice (e.g. robust loss, or Winsorising in the
`CANDIDATE` feature-transform step) can address it explicitly later.

---

## 14. Roçada handling (cycle-safe lags)

A roçada **starts a new growth cycle**. `feature_row` tracks, per `(dataset, trecho)`, a
`cycle_index` = the count of roçada events with `occurred_on ≤ as_of_date` (recomputed
independently from `rocada_events.csv` here in Phase 5, not trusted blindly from Phase 4's own
per-observation bookkeeping — a deliberate audit re-derivation).

- `rocada_in_new_cycle = 1` marks the **first observation after a cut** — the row whose
  `cycle_index` differs from the previous observation's.
- On such a row: `height_prev_cm`, `height_lag2_cm`, `weekly_growth_cm` are **all `NULL`** — there
  is no valid "same-cycle" history yet, so no lag is fabricated across the cut.
- On every other row, lags reference only observations from the **same cycle** — `height_lag2_cm`
  additionally checks that *both* steps back share the current cycle index, not just the immediate
  predecessor.
- `days_since_rocada` needs no such care: the generator already counts only *past* roçadas, so it
  is valid on every row, including the first of a new cycle (where it is small).
- Targets (`target_height_plus_*`) are **not** cycle-filtered — a roçada landing inside the +7/+14/
  +30-day window is exactly the kind of event the forecast should reflect; hiding it would remove
  the operationally interesting signal, not a leak.
- `leakage_audit.json` asserts this invariant programmatically (`cycle_reset_nulls_lag_and_growth`)
  over the built table — see §15.

---

## 15. Data-leakage audit

Full machine-checked results: `data/features/leakage_audit.json`. **`overall_pass: true`.**

| Check | Result |
|---|---|
| `model_ready_list_is_clean` — no `EXCLUDE_LEAKAGE`/`TARGET` name in `KEEP+CANDIDATE` | ✅ pass |
| `all_keep_candidate_columns_present` — no name drift between the spec and the built table | ✅ pass |
| `diagnostic_columns_excluded_from_model_ready` — `*_DIAGNOSTIC_ONLY` columns absent from `KEEP`/`CANDIDATE` | ✅ pass |
| `administrative_identity_excluded` — `generator_*`, `data_source`, `provenance`, `dataset`, `scenario` absent from `KEEP`/`CANDIDATE` | ✅ pass |
| `cycle_reset_nulls_lag_and_growth` — every `rocada_in_new_cycle=1` row has null lag/growth | ✅ pass |
| `dev_ood_disjoint` — no dataset value shared between the dev and OOD files | ✅ pass |
| `temporal_splits_date_disjoint` — no `as_of_date` in two temporal splits at once | ✅ pass |

**Explicit `EXCLUDE_LEAKAGE` list** (never a feature, enforced by `feature_spec.assert_no_leakage`):

- `true_height_cm` (as `true_height_cm_DIAGNOSTIC_ONLY`) and `nivel_true` — the simulator's latent
  state; per the Phase 5 instruction, **never a target basis and never a feature**. Every
  `target_*` column here is built purely from `observed_height_cm` sequences (§16), not from
  `true_height_cm`.
- `generator_version`, `generator_seed`, `generator_params_hash`, `data_source`, `provenance`,
  `dataset`, `scenario` — administrative identifiers. If ever a `measured` row joins this table, a
  model that saw these could trivially learn "which population a row came from" instead of the
  vegetation signal. Excluded now, on principle, even though every row today is `synthetic`.

**Future-information check:** every weather feature in `KEEP`/`CANDIDATE` is a *trailing* window
ending at `as_of_date` (Phase 3/DEC-001 built them that way; Phase 5 adds nothing forward-looking).
Every `target_*` column is built from observations strictly at or after `as_of_date` (§16). No
column in `KEEP`/`CANDIDATE` is computed from a date after `as_of_date`.

---

## 16. Targets — how they are built

- `target_height_plus_7d_cm` / `_14d_cm` / `_30d_cm`: the **nearest observation** to
  `as_of_date + N days`, **within a ±3-day tolerance**. This is not cosmetic: observations sit on a
  fixed 7-day (ISO-week) grid, so `+7d`/`+14d` land exactly on a grid date, but **`+30d` never
  does** (30 is not a multiple of 7) — an exact-date lookup would silently null 100 % of
  `target_height_plus_30d_cm` (caught during this build; see `target_height_plus_30d_actual_offset_days`,
  which records the true offset used, typically 28 days). If no observation falls in the tolerance
  window, the target is `NULL` — never imputed.
- `target_days_until_30cm`: a **forward search**, starting at `as_of_date` itself (day 0 is a valid
  answer — "already ≥ 30 cm and ready today"), over that `(dataset, trecho)`'s own future
  observations, for the first one with `nivel_observed = 3` (i.e. `observed_height_cm > 30` **and**
  `operational_status = 'ready'`) within a 120-day horizon. Built **only from
  `observed_height_cm`**, never from `true_height_cm` — per the Phase 5 instruction, the diagnostic
  latent state is off-limits even as a target basis. Not found within the horizon →
  `target_days_until_30cm_censored = 1`, value `NULL`.
- **DEC-004** applies to whichever observation a target lookup lands on: if that observation's own
  `week_days_present < 7`, the **entire row** is excluded (not just that target) — see §10.

---

## 17. Splits

**Temporal, not random** (`split` column) — dev set (`main`+`seed2`) only; `ood` is never split, it
is held out entirely (§1).

```
train        <= 2024-12-31                       18 486 rows
embargo      2025-01-01 .. 2025-01-30 (excluded)     ~700 rows
validation   2025-01-31 .. 2025-08-31                5 500 rows
embargo      2025-09-01 .. 2025-09-30 (excluded)     ~700 rows
test         2025-10-01 .. 2026-09-01                8 424 rows
```

**Why an embargo, not a bare cutoff.** A 30-day gap (≥ the longest height-target horizon) sits on
each side of the validation and test boundaries. Without it, a `train`-anchored row's
`target_height_plus_30d_cm` and a `validation`-anchored row's `height_prev_cm` could reference the
same nearby dates — not a hard leak, but exactly the kind of cross-split dependency the
instruction ("avoid the same future appearing in train and test simultaneously") asks to prevent.
Because the split is a pure function of `as_of_date` (not of `trecho_id`), every trecho's row for a
given week lands in the same split — weeks are never split across trechos.

**Second scheme — blocked by `trecho`** (`split_blocked_trecho` column, `split_scheme` records
both are available): each `trecho_id` is deterministically hashed (MD5 mod 20) into train (14/20,
70 %) / validation (3/20, 15 %) / test (3/20, 15 %), **regardless of date**. This is the
TASK.md-requested "per-trecho blocked split" — a robustness check for whether a model generalises
to *unseen trechos*, not just unseen time. Stored as a second column in the same file rather than a
duplicated 33 k-row export, to avoid doubling the file for no benefit; Phase 7/8 read whichever
column the experiment needs.

| Temporal split | rows |
|---|---:|
| train | 18 486 |
| validation | 5 500 |
| test | 8 424 |
| excluded (embargo) | 1 414 |
| **total (dev)** | **33 824** |

| Blocked-by-trecho split | rows |
|---|---:|
| train | 22 652 |
| validation | 6 864 |
| test | 4 308 |

---

## 18. OOD holdout isolation

`feature_row_ood_holdout.csv` (16 831 rows, `ood` scenario) is built by the **same** code path as
the dev set — same leakage rules, same cycle-safe lags, same target construction — but:

- lives in a **separate file**, never concatenated with the dev table;
- every row's `split = 'excluded'`, `split_scheme = 'ood_holdout'` — it cannot be picked up by a
  `split in (train, validation, test)` filter by accident;
- was **not** used to compute any correlation, EDA figure, or feature-selection decision in this
  document — every number in §2–§13 is from the dev set only;
- Phase 6–8 must not use it for model selection, hyperparameters, or feature choice. Its only
  legitimate use is a final, one-shot evaluation after those choices are frozen.

---

## 19. Feature classification (frozen)

The authoritative version is code: [`src/greenv_vegpred/features/feature_spec.py`](../src/greenv_vegpred/features/feature_spec.py).
Phase 7 **must** import `KEEP`/`CANDIDATE` from there rather than reading raw CSV columns.

**KEEP** (9 — the Phase 6/7 baseline set):
`height_cm`, `height_prev_cm`, `weekly_growth_cm`, `days_since_rocada`, `gdd_week_tb15_c`,
`tmin_week_c`, `rain_30d_mm`, `water_deficit_30d`, `operational_status`.

**CANDIDATE** (23 — add one at a time in Phase 7, measure the delta):
`height_lag2_cm`, `weeks_observed_last_8`, `rocada_in_new_cycle`, `gdd_week_tb10_c`,
`gdd_week_tb17_c`, `tmean_week_c`, `tmax_week_c`, `rain_7d_mm`, `rain_14d_mm`,
`shortwave_7d_mj_m2`, `et0_7d_mm`, `water_deficit_90d`, `relative_humidity_7d_pct`,
`week_of_year`, `season_sin`, `season_cos`, `dry_season_flag`, `vegetation_type`, `km_mid`,
`centroid_lat`, `centroid_lon`, `grid_cell_id`, `cell_coverage`, `frame_count`, `blockers`,
`nivel_observed`.

**METADATA** (never a feature, not dangerous): `feature_row_id`, `trecho_id`, `rodovia`, `sentido`,
`iso_year`, `iso_week`, `as_of_date`, `split`, `split_blocked_trecho`, `split_scheme`,
`feature_spec_version`, `week_days_present`, `target_days_until_30cm_horizon_days`, the three
`target_height_plus_*_actual_offset_days` columns, `built_at`.

**EXCLUDE_LEAKAGE** (never a feature, actively dangerous): `true_height_cm`, `nivel_true`,
`generator_version`, `generator_seed`, `generator_params_hash`, `data_source`, `provenance`,
`dataset`, `scenario`.

**TARGET** (the label; never a feature; never metadata): `target_height_plus_7d_cm`,
`target_height_plus_14d_cm`, `target_height_plus_30d_cm`, `target_days_until_30cm`,
`target_days_until_30cm_censored`.

No feature-selection-by-model-performance has happened — this classification is evidence-based
(literature support + the correlations in §11 + the leakage rules), not benchmarked against Phase
7's model families, per instruction.

---

## 20. Main risks (carried from `TASK.md` Phase 5, and what was found)

- **EDA describes the generator, not reality** — every finding above is a property of the Phase 4
  mechanism plus real weather, stated as such throughout.
- **Look-ahead bias** — addressed structurally: trailing-only weather windows (inherited from
  Phase 3/DEC-001), cycle-safe lags (§14), targets built strictly forward (§16), embargoed splits
  (§17), and a machine-checked audit (§15) — not just a written promise.
- **Small effective sample per trecho** — ≈ 275 usable weeks across `main`+`seed2` per trecho over
  3.7 years (156 anticipated in the original risk note; slightly higher because two datasets are
  stacked), still modest for 118 trechos × many candidate features. A reason to keep `CANDIDATE`
  features out of the Phase 6/7 baseline by default.
- **A bug found and fixed during this phase**: the first build of `target_height_plus_30d_cm` used
  an exact-date lookup and returned `NULL` for **100 %** of rows, because the weekly observation
  grid never lands exactly 30 days apart. Fixed with a ±3-day tolerance search (§16); recorded here
  so the fix is not silently invisible.

Reviewed: 2026-09-10.
