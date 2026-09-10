# Data dictionary — greenv-vegetation-prediction

**Phase 2 artifact of [`docs/VEGETATION_PREDICTION_TASK.md`](../../../../docs/VEGETATION_PREDICTION_TASK.md).**
Companion to [`src/greenv_vegpred/schema.sql`](../src/greenv_vegpred/schema.sql) and
[`seeds/trecho_sp021.sql`](../seeds/trecho_sp021.sql).

**Status of the science behind this schema:** Phase 1 is **partially validated**. The schema
records *which* base temperature and *which* provenance every value has, but it does not turn a
Phase 1 `hypothesis` or `literature-unconfirmed` parameter into a fact. Values such as
`gdd_base_temp_c = 15.0` are defaults to be swept, not settled numbers — see
[`data/reference/params.yaml`](../data/reference/params.yaml).

---

## 1. Conventions

| Concern | V1 (SQLite) | Rationale |
|---|---|---|
| Timestamps / dates | `TEXT` holding ISO-8601 UTC (`YYYY-MM-DD` or `YYYY-MM-DDTHH:MM:SSZ`) | SQLite has no date type; ISO-8601 sorts lexically and migrates to `timestamptz` cleanly |
| Booleans | `INTEGER` + `CHECK (col IN (0,1))` | no native boolean in SQLite |
| Enums | `TEXT` + `CHECK (col IN (...))`, plus the `provenance` lookup table | portable; becomes a PG `ENUM` or stays a `CHECK` on migration |
| JSON (`blockers`) | `TEXT` holding a JSON array | SQLite JSON1 functions work on `TEXT`; becomes `jsonb` on PG |
| Primary keys | application-generated `TEXT` — UUIDv7, or a documented natural key (`trecho_id`) | no `AUTOINCREMENT`/`SERIAL`, so seeds are deterministic and portable |
| Derived classification (`nivel`) | `GENERATED ALWAYS AS (...) STORED` | non-forgeable; supported by SQLite ≥ 3.31 and PG ≥ 12 |
| Foreign keys | declared; `PRAGMA foreign_keys = ON;` per SQLite connection | PG enforces FKs by default |

**Column roles** used in the tables below:

- **key** — identifier / part of a uniqueness constraint.
- **feature** — MAY be a model input. Must be knowable strictly at or before `as_of_date`.
- **target** — a model output / label. Strictly *after* `as_of_date`. Never an input.
- **metadata** — provenance, timestamps, pipeline bookkeeping. Never an input.
- **derived** — computed from other columns (e.g. `nivel`); a feature or a target depending on which week it describes.
- **leakage-risk** — exists in the DB, looks feature-like, but must be excluded or carefully windowed (see §10).

**The four GreenV fields** `(rodovia, sentido, km, capturado_em)` are present everywhere:
`rodovia` / `sentido` / `km_start` / `km_end` on `trecho`; `capturado_em` = `observed_at` on
`height_observation`, carried onto `feature_row.as_of_date` and `prediction.as_of_date`.

**Provenance is mandatory** on every table that carries information whose origin matters
(`height_observation`, `weather_observation`, `rocada_event`, and the derived `feature_row` /
`prediction` via `data_source` / `input_data_source` / `dataset_is_synthetic`). The taxonomy is
the Phase 1 one: `measured`, `weather_real`, `synthetic`, `literature_derived`, `hypothesis`.

---

## 2. `provenance` (lookup)

| Column | Type | Null? | Role | Notes |
|---|---|---|---|---|
| `provenance_code` | TEXT | no | key | one of `measured` \| `weather_real` \| `synthetic` \| `literature_derived` \| `hypothesis` |
| `description` | TEXT | no | metadata | human-readable meaning |

Seeded with 5 rows by `schema.sql`. Every `provenance` / `linear_reference_provenance` column
FKs here, and additionally has a `CHECK` narrowing it to the codes valid for that table.

---

## 3. `trecho` — spatial reference

| Column | Type | Unit | Null? | Provenance | Role | Notes |
|---|---|---|---|---|---|---|
| `trecho_id` | TEXT | — | no | — | key | `rodovia:sentido:km_start_m` zero-padded, e.g. `SP-021:norte:012000` |
| `rodovia` | TEXT | — | no | given | feature (static) | `SP-021` |
| `sentido` | TEXT | — | no | given | feature (static) | CHECK-constrained set incl. `norte`/`sul` |
| `km_start` | REAL | km | no | given | feature (static) | arithmetic partition, from the Phase 2 brief |
| `km_end` | REAL | km | no | given | feature (static) | `> km_start` |
| `length_m` | REAL | m | no | given | metadata | ~500 (last SP-021 stretch is 300) |
| `centroid_lat` | REAL | deg | yes | **hypothesis** (Phase 3) | feature (static) | NULL until Phase 3; from the synthetic linear reference |
| `centroid_lon` | REAL | deg | yes | **hypothesis** (Phase 3) | feature (static) | idem |
| `geom_wkt` | TEXT | — | yes | hypothesis (Phase 3) | metadata | optional centre-line WKT; PostGIS geometry later |
| `linear_reference_provenance` | TEXT | — | no | — | metadata | FK `provenance`; default `hypothesis` for SP-021 |
| `created_at` | TEXT | ISO-8601 | no | — | metadata | |
| `notes` | TEXT | — | yes | — | metadata | |

`rodovia`, `sentido`, `km_*`, `centroid_*` are legitimate **static features** (position). They
encode *where*, never *truth about the vegetation*, and the SP-021 geometry is
`hypothesis`-provenance — a false-precision caution, not a leakage one.

---

## 4. `height_observation` — one vegetation-height observation

| Column | Type | Unit | Null? | Provenance | Role | Notes |
|---|---|---|---|---|---|---|
| `observation_id` | TEXT | — | no | — | key | UUIDv7 |
| `trecho_id` | TEXT | — | no | — | key | FK `trecho` |
| `observed_at` | TEXT | ISO-8601 UTC | no | measured/synthetic | feature (temporal anchor) | = `capturado_em` |
| `observed_on` | TEXT | ISO date | no | derived | key | weekly bucketing |
| `iso_year` | INTEGER | — | no | derived | key | |
| `iso_week` | INTEGER | 1–53 | no | derived | key | |
| `height_cm` | REAL | cm | yes | measured/synthetic | **feature** (current-week) / **target** (horizon-week) | the main signal. NULL only with `height_status` reason |
| `height_status` | TEXT | — | no | metadata | metadata | `observed` \| `absent` \| `censored` |
| `height_p50_cm` / `p90` / `p95` | REAL | cm | yes | measured/synthetic | feature | within-trecho distribution from the measurement envelope |
| `h95_spread_m` | REAL | m | yes | measured/synthetic | feature (uncertainty proxy) | between-view disagreement; **only from the current week** |
| `cell_coverage` | REAL | 0–1 | yes | measured/synthetic | feature (quality) | fraction of cells with a valid reading |
| `measured_cell_count` | INTEGER | count | yes | measured/synthetic | feature (quality) | |
| `abstained_cell_count` | INTEGER | count | yes | measured/synthetic | feature (quality) | |
| `quality_confidence` | TEXT | — | yes | derived | feature (quality) | `low`/`medium`/`high` |
| `operational_status` | TEXT | — | no | measured/synthetic | feature (management context) | `ready`/`not-ready`/`unknown`; drives `nivel` |
| `blockers` | TEXT (JSON) | — | yes | measured/synthetic | metadata | e.g. `["road-metadata-missing"]` |
| `nivel` | INTEGER | 0–3 | generated | derived | **feature** (current-week) / **target** (horizon-week) | STORED generated column; `not-ready` or NULL height ⇒ 0 |
| `source_generation` | TEXT | — | yes | measured | **leakage-risk** | sha256 of the source mp4; idempotency key. Proxy for "a measurement happened" — exclude from features |
| `run_id` | TEXT | — | yes | measured | **leakage-risk** | idem |
| `measurement_result_key` | TEXT | — | yes | measured | **leakage-risk** | object-storage key; idem |
| `data_source` | TEXT | — | no | — | metadata | `measured` \| `synthetic` — **mandatory anti-confusion guard** |
| `provenance` | TEXT | — | no | — | metadata | FK `provenance`; must agree with `data_source` (CHECK) |
| `generator_version` / `generator_seed` / `generator_params_hash` | TEXT/INT/TEXT | — | yes | synthetic | **leakage-risk** | synthetic-only; a model could separate synthetic from measured, or memorise per-seed structure — exclude |
| `ingested_at` | TEXT | ISO-8601 | no | — | **leakage-risk** | pipeline timestamp; correlates with collection campaigns, not vegetation — exclude |
| `notes` | TEXT | — | yes | — | metadata | |

Uniqueness: `(trecho_id, iso_year, iso_week, data_source)` — one measured and one synthetic
observation per trecho-week may coexist on purpose (Phase 8 comparison). A re-measured week
*replaces* the measured row (application logic).

---

## 5. `weather_observation` — daily weather for a trecho / grid cell

| Column | Type | Unit | Null? | Provenance | Role | Notes |
|---|---|---|---|---|---|---|
| `weather_id` | TEXT | — | no | — | key | UUIDv7 |
| `trecho_id` | TEXT | — | yes | — | key | FK `trecho`; one of `trecho_id`/`grid_cell_id` required |
| `grid_cell_id` | TEXT | — | yes | — | key | reanalysis cell, e.g. `era5land:-23.55:-46.75` |
| `obs_date` | TEXT | ISO date | no | weather_real/synthetic | key | |
| `iso_year` / `iso_week` | INTEGER | — | no | derived | key | weekly aggregation |
| `temp_min_c` / `temp_mean_c` / `temp_max_c` | REAL | °C | yes | weather_real | feature (via weekly aggregate) | |
| `precip_mm` | REAL | mm | yes | weather_real | feature (via rolling sum) | total precipitation |
| `rain_mm` | REAL | mm | yes | weather_real | feature | liquid rain (Open-Meteo separates) |
| `shortwave_radiation_mj_m2` | REAL | MJ/m² | yes | weather_real | feature | daily shortwave sum; RUE path |
| `et0_mm` | REAL | mm | yes | weather_real | feature | FAO-56 reference ET |
| `relative_humidity_pct` | REAL | % | yes | weather_real | **feature candidate only** | Phase 1: no direct effect found; provenance of any humidity *term* is `hypothesis` |
| `wind_speed_ms` | REAL | m/s | yes | weather_real | metadata | not a planned feature |
| `gdd_c` | REAL | °C·day | yes | derived / literature_derived | feature (via weekly sum) | `max(0, (Tmax+Tmin)/2 − Tb)` |
| `gdd_base_temp_c` | REAL | °C | no | **literature_derived** | metadata | Tb used for `gdd_c`. Default 15.0 (Villa Nova 2007). **Sweep 10–17** — it is not settled |
| `water_deficit_index` | REAL | — | yes | **literature_derived** (method) | feature | adopted deficit metric; formula pinned in code + `feature_spec_version` |
| `provider` | TEXT | — | no | — | metadata | `open-meteo` \| `inmet` \| `nasa-power` \| `synthetic` |
| `dataset` | TEXT | — | yes | — | metadata | `ERA5-Land`, `BDMEP`, `MERRA-2` |
| `data_source` | TEXT | — | no | — | metadata | `weather_real` \| `synthetic` — **mandatory** |
| `provenance` | TEXT | — | no | — | metadata | FK `provenance`; CHECK agrees with `data_source` |
| `license` | TEXT | — | yes | — | metadata | e.g. `CC-BY-4.0` (Open-Meteo) |
| `retrieved_at` | TEXT | ISO-8601 | no | — | **leakage-risk** | fetch time; reproducibility metadata, not a feature |
| `raw_response_ref` | TEXT | — | yes | — | metadata | path to cached raw response |

**Leakage note:** weather rows exist for dates *after* any given `as_of_date`. The feature build
must window every weather aggregate to `(as_of_date − N, as_of_date]`. Future weather is allowed
only in the *forecast* step (climatology or a real forecast), never in training features (§10).

---

## 6. `rocada_event` — a mowing event

| Column | Type | Unit | Null? | Provenance | Role | Notes |
|---|---|---|---|---|---|---|
| `rocada_id` | TEXT | — | no | — | key | UUIDv7 |
| `trecho_id` | TEXT | — | no | — | key | FK `trecho` |
| `occurred_on` | TEXT | ISO date | no | measured/synthetic | feature-source | drives `days_since_rocada` |
| `height_before_cm` | REAL | cm | yes | measured/synthetic | metadata | |
| `height_after_cm` | REAL | cm | yes | measured/synthetic | metadata | residual cut height; Phase 1 `hypothesis` (3–8 cm) when synthetic |
| `equipment_type` | TEXT | — | yes | measured | metadata | `apps/web` `EQUIPMENT_TYPES` vocabulary |
| `source` | TEXT | — | no | — | metadata | `ordem-de-servico` \| `import` \| `manual` \| `synthetic` |
| `ordem_de_servico_id` | TEXT | — | yes | — | metadata | id in a future OS system; no FK yet |
| `data_source` | TEXT | — | no | — | metadata | `measured` \| `synthetic` — **mandatory** |
| `provenance` | TEXT | — | no | — | metadata | FK `provenance`; CHECK agrees with `data_source` and `source` |
| `recorded_at` | TEXT | ISO-8601 | no | — | **leakage-risk** | bookkeeping time, not a feature |

**Leakage note:** only `rocada_event`s with `occurred_on <= as_of_date` may inform
`days_since_rocada`. A *future* mowing (and any "next scheduled `roçada`" field, which does not
exist here on purpose) must never enter a feature (§10).

---

## 7. `feature_row` — the materialised training matrix

One row per `(trecho_id, as_of_date, split_scheme, feature_spec_version)`. **Every column is
labelled feature / target / split-meta below. Nothing else in the database is a model input.**

### 7.1 Keys and anchor

| Column | Type | Role | Notes |
|---|---|---|---|
| `feature_row_id` | TEXT | key | UUIDv7 |
| `trecho_id` | TEXT | key | FK `trecho` |
| `iso_year` / `iso_week` | INTEGER | key | |
| `as_of_date` | TEXT | key / **the temporal wall** | nothing dated after this may inform any feature |
| `data_source` | TEXT | metadata | `measured` \| `synthetic` \| `mixed` — provenance of the height signal feeding this row |

### 7.2 Features — height / growth history (past + present only)

| Column | Unit | Notes |
|---|---|---|
| `height_cm` | cm | current week |
| `height_prev_cm` | cm | one ISO week back |
| `height_lag2_cm` | cm | two ISO weeks back |
| `weekly_growth_cm` | cm | `height_cm − height_prev_cm` (past-to-present) |
| `weeks_observed_last_8` | count | data-density feature |

### 7.3 Features — weather, windowed to `(as_of_date − N, as_of_date]`

| Column | Unit | Notes |
|---|---|---|
| `gdd_week_c` | °C·day | sum of daily GDD, past 7 days (Tb from `weather_observation.gdd_base_temp_c`) |
| `gdd_cum_since_rocada_c` | °C·day | cumulative GDD since the last past `roçada` |
| `tmin_week_c` / `tmean_week_c` / `tmax_week_c` | °C | past-7-day aggregates; **Tmin is the Phase 1 dominant driver** |
| `rain_7d_mm` / `rain_14d_mm` / `rain_30d_mm` | mm | trailing sums |
| `shortwave_7d_mj_m2` | MJ/m² | trailing sum |
| `et0_7d_mm` | mm | trailing sum |
| `water_deficit_30d` | — | adopted deficit metric over the trailing 30 days |
| `relative_humidity_7d_pct` | % | **candidate only**; provenance `hypothesis` if used as a term |

### 7.4 Features — maintenance / seasonality / position

| Column | Unit | Notes |
|---|---|---|
| `days_since_rocada` | days | counts only `roçada`s with `occurred_on <= as_of_date`; NULL if never mown in the record |
| `week_of_year` | 1–53 | |
| `season_sin` / `season_cos` | — | day-of-year harmonic; keep small — seasonality is mostly emergent from real weather (Phase 1) |
| `dry_season_flag` | 0/1 | SP wet/dry season |
| `vegetation_type` | — | optional; V1 a per-trecho proxy label |
| `km_mid` / `centroid_lat` / `centroid_lon` | km / deg | static position; SP-021 coords are `hypothesis`-provenance |
| `operational_status` | — | status of the **source** observation (management-context feature, McHugh-style) |

### 7.5 Targets — strictly after `as_of_date`

| Column | Unit | Notes |
|---|---|---|
| `target_height_plus_7d_cm` | cm | NULL if the +7-day week was not observed |
| `target_height_plus_14d_cm` | cm | |
| `target_height_plus_30d_cm` | cm | |
| `target_days_until_30cm` | days | **forward-simulated from `as_of_date + 1`**, never inverted from the current reading; NULL if censored |
| `target_days_until_30cm_censored` | 0/1 | 1 ⇒ 30 cm not reached within the horizon |
| `target_days_until_30cm_horizon_days` | days | the max horizon used for censoring (e.g. 120) |

### 7.6 Split / metadata — never model inputs

| Column | Role | Notes |
|---|---|---|
| `split` | split-meta | `train` / `validation` / `test` / `excluded` |
| `split_scheme` | split-meta | e.g. `temporal-cut-2025`, `blocked-by-trecho` |
| `feature_spec_version` | metadata | pins the exact feature definitions (window sizes, Tb, deficit formula) |
| `source_observation_id` | metadata | FK `height_observation` |
| `generator_params_hash` | metadata | when synthetic is involved |
| `built_at` | metadata | build time — **not** a feature |

---

## 8. `prediction` — model output

| Column | Type | Unit | Null? | Role | Notes |
|---|---|---|---|---|---|
| `prediction_id` | TEXT | — | no | key | UUIDv7 |
| `trecho_id` | TEXT | — | no | key | FK `trecho` |
| `as_of_date` | TEXT | ISO date | no | key | |
| `model_kind` | TEXT | — | no | key | `naive_persistence` … `hist_gradient_boosting` … |
| `model_version` | TEXT | — | no | key | |
| `h_plus_7_cm` / `_14` / `_30` | REAL | cm | yes | output | point forecasts |
| `h_plus_7_low_cm` / `_high_cm` (×3 horizons) | REAL | cm | yes | output | interval bounds |
| `days_to_30cm` | REAL | days | yes | output | the primary answer |
| `days_to_30cm_low` / `_high` | REAL | days | yes | output | interval |
| `days_to_30cm_censored` | INTEGER | 0/1 | no | output | 1 ⇒ beyond horizon |
| `days_to_30cm_horizon_days` | INTEGER | days | yes | output | |
| `interval_method` | TEXT | — | yes | metadata | `conformal` / `quantile` / `bootstrap` / `residual` |
| `interval_nominal_coverage` | REAL | 0–1 | yes | metadata | e.g. 0.80 |
| `confidence` | TEXT | — | yes | output | `low`/`medium`/`high`; capped at `low` when source is `not-ready` (CHECK) |
| `operational_status` | TEXT | — | yes | metadata | carried through from the source observation |
| `blockers` | TEXT (JSON) | — | yes | metadata | carried through |
| `source_observation_id` | TEXT | — | yes | metadata | FK `height_observation` |
| `input_data_source` | TEXT | — | no | **metadata (guard)** | `measured` \| `synthetic` \| `mixed` |
| `dataset_is_synthetic` | INTEGER | 0/1 | no | **metadata (guard)** | explicit "this prediction was built on synthetic data" flag |
| `created_at` | TEXT | ISO-8601 | no | metadata | |

The `prediction` table is **never** a feature source for another model (target leakage /
circularity). See §10.

---

## 9. `dataset_manifest` — machine-readable ledger

| Column | Type | Role | Notes |
|---|---|---|---|
| `manifest_id` | TEXT | key | UUIDv7 |
| `kind` | TEXT | metadata | `synthetic_heights` / `weather_pull` / `feature_build` / `rocada_schedule` / `trecho_seed` / `model_training` / `other` |
| `created_at` | TEXT | metadata | |
| `params_hash` / `seed` | TEXT / INTEGER | metadata | reproducibility |
| `row_count` | INTEGER | metadata | |
| `is_synthetic` | INTEGER (0/1) | metadata | explicit |
| `source_summary` / `notes` | TEXT | metadata | providers, date ranges, `params.yaml` version, code version |

This table is the spine of the Phase 13 REAL vs SYNTHETIC ledger.

---

## 10. Data-leakage analysis — columns that must NOT be training features

The training matrix is `feature_row`. A column is safe as a feature only if its value is fully
determined by information available **at or before `as_of_date`**. The following are in the
database but must be excluded or carefully windowed:

### 10.1 Future by definition — never a feature

1. **Every `feature_row.target_*` column.** They are labels.
2. **`height_observation` rows with `observed_on > as_of_date`.** The feature build must filter
   to `observed_on <= as_of_date`. `height_prev_cm`, `height_lag2_cm`, `weekly_growth_cm` must use
   strictly *past* ISO weeks.
3. **`weather_observation` rows with `obs_date > as_of_date`.** Every weather aggregate
   (`gdd_week_c`, `rain_30d_mm`, …) must use a **trailing** window `(as_of_date − N, as_of_date]`,
   never a symmetric or forward window. Future weather belongs only to the forecast step
   (climatology / real forecast), flagged separately, never to training features.
4. **`rocada_event` rows with `occurred_on > as_of_date`.** `days_since_rocada` and
   `gdd_cum_since_rocada_c` count only past mowings. There is deliberately **no** "next scheduled
   `roçada`" column.
5. **`nivel` / `height_cm` of a horizon week** (`+7d`, `+14d`, `+30d`) — those are targets. Only
   the *current* week's `nivel` / `height_cm` is a feature.
6. **The entire `prediction` table.** Feeding a prediction back as a feature is target leakage and
   makes the evaluation circular.

### 10.2 Synthetic-vs-measured separators — exclude so the model cannot cheat

7. **`generator_version`, `generator_seed`, `generator_params_hash`** (`height_observation`).
   Present only on synthetic rows. A model could trivially split synthetic from measured, or
   memorise per-seed structure. Exclude from features; keep for reproducibility only.
8. **`source_generation`, `run_id`, `measurement_result_key`** (`height_observation`). Present
   only on measured rows. Same risk in reverse — "has a `run_id`" ⇒ measured distribution.
9. **`provider`, `dataset`, `raw_response_ref`, `license`** (`weather_observation`). If synthetic
   weather is ever used, these separate it from real weather.
10. **`cell_coverage`, `abstained_cell_count`, `h95_spread_m`, `operational_status`, `blockers`,
    `quality_confidence`.** These *are* legitimate features (quality / management context,
    McHugh-style) — **but only from the current week**, and only if the EDA (Phase 5) confirms
    their distribution does not differ systematically between synthetic and measured rows. If it
    does, they become synthetic detectors and must be dropped or matched.

### 10.3 Pipeline timestamps — never a feature

11. **`ingested_at`, `retrieved_at`, `recorded_at`, `built_at`, `created_at`.** These correlate
    with data-collection campaigns and code deploys, not with vegetation. Use `observed_on` /
    `as_of_date` for any temporal feature.

### 10.4 Split and identity

12. **`split`, `split_scheme`, `feature_spec_version`.** Bookkeeping.
13. **`trecho_id` as a raw categorical** — allowed only with care. One-hot over 118 trechos on a
    small synthetic dataset invites memorisation of per-trecho latent effects. Prefer the
    continuous position features (`km_mid`, `centroid_*`) and a `vegetation_type` proxy; if
    `trecho_id` is used, use target encoding fitted on train only.

### 10.5 The subtle windowing traps (call out in Phase 5 / Phase 8)

14. **Symmetric rolling windows.** `rain_30d` centred on the target week leaks 15 days of future
    rain. All windows are trailing.
15. **`target_days_until_30cm` computed by inverting the current height** rather than by forward
    simulation from `as_of_date + 1`. The former bakes the answer into the feature set.
16. **Lag features that cross a `roçada`.** `weekly_growth_cm` spanning a mowing is a −40 cm
    "growth" — legitimate history, but the generator *scheduled* that mowing because height was
    high, so a model can learn "large negative growth last week ⇒ was tall" which is real signal;
    the leak would be also exposing *when the next* mowing is.

---

## 11. SQLite → PostgreSQL migration map

| SQLite (V1) | PostgreSQL | Where |
|---|---|---|
| `TEXT` ISO-8601 timestamp | `timestamptz` (or `date`) | all `*_at`, `*_on`, `observed_at` |
| `INTEGER` + `CHECK (x IN (0,1))` | `boolean` | `*_censored`, `dry_season_flag`, `is_synthetic`, `dataset_is_synthetic` |
| `TEXT` + `CHECK (x IN (...))` | `CREATE TYPE … AS ENUM` (or keep the `CHECK`) | `sentido`, `provider`, `source`, `model_kind`, `interval_method`, `data_source`, `provenance`, … |
| `TEXT` holding JSON | `jsonb` | `blockers` |
| `GENERATED ALWAYS AS (…) STORED` | identical syntax (PG ≥ 12) | `height_observation.nivel` |
| `lower(hex(randomblob(16)))` | `gen_random_uuid()::text` (pgcrypto / PG ≥ 13) | `dataset_manifest` seed |
| `strftime('%Y-%m-%dT%H:%M:%SZ','now')` | `to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')` | seeds |
| `printf('%06d', n)` | `lpad(n::text, 6, '0')` | `trecho_sp021.sql` |
| `MIN(a, b)` scalar | `least(a, b)` | `trecho_sp021.sql` |
| `julianday(a) - julianday(b)` | `a::date - b::date` | `v_trecho_weekly` |
| `PRAGMA foreign_keys = ON;` | (FKs enforced by default) | connection setup |
| no schema namespace | `CREATE SCHEMA vegetation; SET search_path = vegetation;` | all objects move under `vegetation` |
| tables as-is | optionally add `PARTITION BY RANGE (obs_date)` on `weather_observation` at scale | later |

The DDL in `schema.sql` uses only the portable left-column forms; the seed file isolates the
SQLite-only functions and lists their PG equivalents inline.

---

## 12. Compatibility with Phase 1

- **Provenance taxonomy** is exactly the Phase 1 one (`measured`, `weather_real`, `synthetic`,
  `literature_derived`, `hypothesis`), enforced by the `provenance` table + `CHECK`s.
- **`gdd_base_temp_c`** defaults to `15.0` (Villa Nova et al. 2007, `literature-confirmed` for
  *Pennisetum*/*Cynodon*), stored per weather row so the Phase 1 "10–17 °C, controversial" range
  can be swept without a schema change. The schema does **not** assert 15 °C is correct.
- **Nível thresholds** (10 / 30 cm) are the Motiva rule, hard-coded in the generated `nivel`
  column, corroborated by the DNIT roadside standard (Phase 1 §7).
- **`mowing_trigger_height_cm` (~50 cm, `literature-confirmed`)** is not in the schema — it is a
  generator-policy parameter (`params.yaml`), and `rocada_event` records the *result*.
- **`hypothesis`/`literature-unconfirmed` parameters stay flagged.** The schema never turns them
  into facts: `linear_reference_provenance = 'hypothesis'` on every SP-021 `trecho`;
  `height_after_cm`, `days_since_rocada`, humidity terms, measurement noise all trace back to
  `params.yaml` entries whose `confidence` is `hypothesis` or `literature-unconfirmed`.
- **Weakest link recorded:** the DM→height conversion (`dm_to_height_bulk_density`,
  `literature-unconfirmed`) is a *generator* concern; the schema stores `height_cm` directly and
  is agnostic to how it was produced, so a later switch to a direct height-rate generator needs no
  migration.

---

## 13. Phase 2 completion criteria — check

| Criterion (from `TASK.md`) | Status | Evidence |
|---|---|---|
| DDL applies cleanly to a fresh SQLite database | **Pending execution** — `schema.sql` written to be SQLite-valid (generated column STORED, window-function view, portable types); not yet run in this session. Run: `sqlite3 vegetation.sqlite < src/greenv_vegpred/schema.sql` | `src/greenv_vegpred/schema.sql` |
| Data dictionary lists every column with type, unit, nullability and provenance label | **Done** | this file, §2–§9 |
| A round-trip test inserts and reads one sample row per table | **Not done** — belongs to Phase 11 (tests). Phase 2 defines the schema; the round-trip harness is Phase 11 | — |
| Seeded `trecho` table for SP-021 | **Done (structural)** | `seeds/trecho_sp021.sql` — 118 rows, km partition only, geometry deferred to Phase 3, every row `linear_reference_provenance = 'hypothesis'` |

**Honest status:** the schema and dictionary are complete; "applies cleanly" and the round-trip
test require *running* SQLite, which this session did not do (no code execution requested). Those
two boxes are ready to be ticked the moment the DDL is executed — that is a Phase 11 / setup step,
not new Phase 2 design work.

Reviewed: 2026-09-09.
