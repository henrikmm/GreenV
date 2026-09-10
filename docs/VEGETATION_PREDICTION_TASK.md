# Vegetation prediction — feature master plan

**GreenV measures roadside vegetation on Motiva's `faixa de domínio`; this feature forecasts how
fast it grows, so a crew is sent before a `trecho` becomes a problem rather than after.** The
operational question is one number per `trecho`: **in how many days does it reach ≈30 cm?**

**Nothing here is built yet.** This document is the plan of record for the feature, in the same
role as [`AUTOMATIC-HEIGHT.md`](AUTOMATIC-HEIGHT.md). Sprint tracking stays on the team's Trello
board (`AGENTS.md`); this file is the design and the phase breakdown, not a live task queue.

Written in English because `AGENTS.md` requires English for documentation. The Brazilian road
terms stay in Portuguese by the same rule: `rodovia`, `sentido`, `km`, `roçada`, `marco km`,
`trecho`, `ordem de serviço`.

- **`trecho`** — a road stretch. Here: a fixed ≈500 m span of one `rodovia` in one `sentido`.
- **`roçada`** — mowing / vegetation cutting. A `roçada` event resets a `trecho`'s height.
- **`ordem de serviço`** — the work order that sends a crew to one or more `trechos`.
- **`marco km`** — a kilometre marker; the anchor of the highway's linear reference.

---

## The business rule (given by Motiva)

| Level | Height | Meaning |
|---|---|---|
| Nível 1 | `altura < 10 cm` | normal |
| Nível 2 | `10 cm ≤ altura ≤ 30 cm` | monitoring |
| Nível 3 | `altura > 30 cm` | critical |

A missing or `not-ready` reading is **Nível 0 / não avaliado**, never Nível 1 — the same rule
`apps/web/src/utils/classification.js` already encodes.

## Initial scenario

- **SP-021 / Rodoanel Oeste**, roughly `km` 0 to `km` 29.3.
- `trechos` of ≈500 m → ≈59 per `sentido`, ≈118 total.
- Ideal capture cadence: once per week per `trecho`.

## Objectives

**Primary:** predict, per `trecho`, the number of days until it reaches ≈30 cm (Nível 3).

**Secondary:**

- predict height at +7, +14 and +30 days;
- report an uncertainty interval on every prediction;
- support `roçada` events as growth-curve resets;
- use real weather and seasonality as drivers;
- keep the data schema and the API in the shape GreenV will later consume, without wiring them.

## The data problem, stated plainly

There is **almost certainly not enough real per-`trecho` height history** to train on: the
`greenv-measurement-worker` exists, but nothing persists a weekly series today (traced in the
architecture analysis — `segment.measured.v1` has no consumer), and weekly capture on SP-021 has
not been running long. So V1:

1. **researches real datasets** and catalogues what exists, with licences;
2. **researches the scientific literature** for grass-growth dynamics and usable proxies;
3. **uses real meteorological data** for the SP-021 corridor wherever possible;
4. **generates a synthetic vegetation-height dataset**, driven by the real weather and by
   literature-derived parameters;
5. **labels every row and every parameter** as `measured`, `weather_real`, `synthetic` or
   `literature-derived` — and never presents one as another.

---

## Scope — V1

**A self-contained research prototype inside `services/greenv-vegetation-prediction/`.** It runs
offline from local data: real weather + a synthetic height dataset. It produces model comparisons,
a days-to-30 cm forecast with an uncertainty interval, and a self-contained read API serving those
forecasts from its own local store. It changes no other part of the repository.

## Out of scope — V1

- modifying `measurement/` (it is a git subtree; the rule in `AGENTS.md` stands);
- real RabbitMQ integration (no live queue consumer or publisher);
- any change to the dashboard (`apps/web`);
- Terraform / cloud deploy (`infrastructure/`, `compose.yaml`);
- botanical / species classification from imagery;
- any change to the GreenV main database (V1 uses its own local store).

---

## Models to compare

All four are implemented behind one interface and evaluated by one harness. **No winner is chosen
in advance** — Phase 8 selects one from the numbers and records why.

1. **Naïve baseline** — persistence, last-growth-carried-forward, seasonal-naïve.
2. **Linear / multiple regression** — OLS and regularised (Ridge / Lasso).
3. **Random Forest.**
4. **Gradient Boosting** — scikit-learn `HistGradientBoosting` preferred (keeps the module
   pip-only and portable); one pinned native library (LightGBM / XGBoost) allowed only if Phase 7
   justifies it.

## Metrics

- **MAE** — primary, for height at +7 / +14 / +30 days.
- **RMSE** — primary, same horizons.
- **R²** — auxiliary only (reported, never the basis of selection alone).
- **Absolute error in days** for the 30 cm arrival prediction — median, distribution, and share
  within ±3 and ±7 days. This is the metric the operation actually feels.

## Candidate variables

| Variable | Notes |
|---|---|
| current height | latest observation for the `trecho` |
| previous height | one week back |
| weekly growth | derived: current − previous |
| rainfall | rolling 7 / 14 / 30-day, real weather |
| temperature | rolling mean; also GDD (growing-degree-days), base temperature from the literature |
| humidity | if the weather source provides it; nullable |
| seasonality | week-of-year, month, `sin`/`cos` of day-of-year, wet/dry-season flag for SP |
| days since last `roçada` | from `roçada` events; drives the regrowth curve |
| vegetation type | when available; a per-`trecho` proxy in V1, real class later |
| segment / geographic position | `trecho_id`, `km`, `centroid_lat`/`lon` |

`km` and position come from a **linear reference**. GreenV has no `marco km` reference in this
repository, so V1 uses a **synthetic** one for SP-021 (anchor points, like the `ROAD_ANCHORS` in
`apps/web/src/utils/classification.js`), flagged `synthetic`.

## Dataset provenance taxonomy

Every row, column and parameter carries exactly one label. This is enforced by a `data_source`
column on every table, by the ledger in Phase 13, and by a visible watermark on every synthetic
figure.

| Label | What it is |
|---|---|
| `measured` | a real vegetation-height reading (none expected in V1; the column exists for V2) |
| `weather_real` | real meteorological data from a named provider, with `retrieved_at` |
| `synthetic` | values produced by the generator in Phase 4 |
| `literature-derived` | a parameter taken from a cited source (equation coefficient, base temp, regrowth lag) |

## Scientific rule

**No synthetic data may be presented as real data** — in a table, a figure, a metric, an API
response or a slide. Synthetic results are described as measuring the generator, not the field.
This mirrors `AUTOMATIC-HEIGHT.md`'s "what has been verified, and what has not".

---

## Phase 0 — Setup and architecture

**Objective.** Stand up the self-contained module skeleton and freeze the input and output
contracts, without touching any existing file.

**Tasks**

- [ ] Create `services/greenv-vegetation-prediction/` (structure below), Python 3.12,
      `pyproject.toml`, `Makefile`, `scripts/verify.sh`.
- [ ] Write the module `README.md` (outcome first) and `AGENTS.md` (module conventions: the
      provenance taxonomy, no imports from `measurement/`, no connection to the GreenV main DB, no
      RabbitMQ in V1).
- [ ] Freeze the **input contract**: a copy of one real `measurement-result-v1.json` envelope in
      `data/reference/contracts/`, with a JSON Schema, annotated with the fields the module reads
      (`positions[]`, `measurement.quality`, `sourceGeneration`, `context`, `capturado_em`). Cite
      the worker file and the date the copy was taken.
- [ ] Freeze the **output contract**: `api/openapi.v1.yaml` for the `/v1/*` endpoints (from the
      architecture analysis).
- [ ] Choose the V1 local store (SQLite) and record the V2 target (PostgreSQL schema
      `vegetation`); keep all DDL Postgres-portable.
- [ ] Tooling: `ruff` (lint + format), `pytest`; a keyless `verify.sh`.
- [ ] Record the four-fields invariant `(rodovia, sentido, km, capturado_em)` on every artifact.

**Completion criteria**

- `./scripts/verify.sh` is green on the empty skeleton (lint + import + empty suite).
- `README.md`, `AGENTS.md`, both contract files present.
- `git status` shows changes only under `services/greenv-vegetation-prediction/` and this doc.

**Artifacts**

- module skeleton; `README.md`; `AGENTS.md`;
- `api/openapi.v1.yaml`;
- `data/reference/contracts/measurement-result-v1.schema.json` + the annotated example.

**Dependencies:** this document; the architecture analysis (module location, responsibilities,
contracts).

**Main risks**

- scope creep into the production Java service — V1 is a Python prototype only;
- the frozen envelope copy drifting from real worker output — mitigate by citing source + date on
  the copy and re-checking in Phase 12.

## Phase 1 — Scientific research and datasets

**Objective.** Build a cited evidence base for roadside grass-growth dynamics and catalogue any
real dataset that could substitute for, or calibrate, a synthetic one.

**Tasks**

- [x] Literature review: sward / grass height growth curves; growth rate vs temperature (GDD, base
      temperature — temperate C3 ≈5 °C, tropical C4 ≈10 °C; SP roadside grasses are largely C4,
      e.g. *Urochloa*/*Brachiaria*, *Melinis*); rainfall response; subtropical São Paulo
      seasonality; regrowth after cutting (lag → exponential → plateau; logistic / monomolecular).
      _Done 2026-09-09 as a **rapid** review (web search + partial full-text); depth and
      unconfirmed values flagged in `reports/literature-review.md`._
- [x] Catalogue candidate **real** datasets: pasture / sward height trials; remote-sensing
      vegetation-height, LAI or NDVI time series; verge / roadside management datasets; any open
      highway vegetation data. Record availability, licence, spatial and temporal resolution, and
      whether the quantity is height (cm) or a proxy. _Done — `data/reference/datasets-catalogue.md`._
- [x] Catalogue **proxy** relationships from the literature (NDVI↔height, biomass↔height,
      GDD↔growth-rate, rainfall↔growth-rate): equation, coefficient range, source, climate zone.
      _Done — in the literature review (§2, §4, §6) and `params.yaml`._
- [x] Weather sources: Open-Meteo historical reanalysis (free, keyless), INMET stations near the
      Rodoanel Oeste, NASA POWER. Record variables, resolution, latency, licence.
      _Done — `datasets-catalogue.md` §B._
- [x] Build `data/reference/params.yaml`: every literature-derived parameter with `value`,
      `range`, `unit`, `source` (DOI / URL), `climate_zone`, `species_assumption`, `confidence`.
      _Done — several entries are `confidence: hypothesis` and must be swept, not trusted._
- [x] Write `reports/literature-review.md` with a references list. _Done._

**Completion criteria**

- `params.yaml` covers at least what the generator needs: base growth rate, GDD sensitivity,
  rainfall sensitivity, seasonal amplitude, post-`roçada` regrowth lag and shape, plateau height,
  measurement-noise scale — each with a citation.
- `literature-review.md` reviewed; dataset candidates catalogued with licences.

**Artifacts**

- `reports/literature-review.md`;
- `data/reference/params.yaml` (with provenance per parameter);
- `data/reference/datasets-catalogue.md`.

**Dependencies:** Phase 0.

**Main risks**

- no directly transferable real height dataset for tropical roadside verge — likely, and it forces
  the synthetic route;
- literature skewed to temperate agronomy — parameters need an explicit "temperate-derived,
  adjusted" flag and a wider `range`;
- paywalled sources.

## Phase 2 — Data schema definition

**Objective.** Define the canonical tables for `trechos`, observations, weather, `roçada` events,
the training matrix and predictions — each with explicit provenance columns.

**Tasks**

- [x] `trecho`: `trecho_id`, `rodovia`, `sentido`, `km_start`, `km_end` (≈500 m),
      `centroid_lat`/`centroid_lon`, `length_m`. Seed SP-021 `km` 0–29.3, both `sentidos`.
      _Done — `schema.sql`; seed `seeds/trecho_sp021.sql` (118 rows, km partition only; geometry
      deferred to Phase 3, every row `linear_reference_provenance = 'hypothesis'`)._
- [x] `height_observation`: `trecho_id`, `observed_on`, `iso_week`, `height_cm`,
      `height_p50_cm` / `p90` / `p95`, `nivel` (1–3), `cell_coverage`, `operational_status`,
      `blockers`, **`data_source`** (`measured` | `synthetic`), `source_generation`, `run_id`,
      `provenance`. _Done — `nivel` is a STORED generated column; `data_source`+`provenance`
      mandatory and cross-checked._
- [x] `weather_observation`: `trecho_id` (or grid cell), `date`, `rain_mm`, `temp_min_c`,
      `temp_max_c`, `temp_mean_c`, `humidity_pct` (nullable), `gdd`, **`data_source`**
      (`weather_real` | `synthetic`), `provider`, `retrieved_at`. _Done — plus `gdd_base_temp_c`
      per row so the Phase 1 Tb 10–17 °C range is swept without a schema change._
- [x] `rocada_event`: `trecho_id`, `occurred_on`, `height_before_cm` (nullable), `source`
      (`synthetic` | `import` | `ordem-de-servico`), `ordem_de_servico_id` (nullable).
      _Done — `source` also allows `manual`; `data_source`+`provenance` mandatory._
- [x] `feature_row` (materialised training matrix): one row per `(trecho, iso_week)` with every
      candidate variable + targets (`height_next_week_cm`, `height_plus_7/14/30_cm`,
      `days_to_30cm`) + a `split` column (temporal: train / validation / test).
      _Done — anchored at `as_of_date`; every column labelled feature / target / split-meta in the
      dictionary; `target_days_until_30cm` carries a censoring flag._
- [x] `prediction`: `trecho_id`, `as_of`, `model_kind`, `h_plus_7_cm`, `h_plus_14_cm`,
      `h_plus_30_cm`, `days_to_30cm`, `days_to_30cm_low`, `days_to_30cm_high`, `interval_method`,
      `confidence`, `blockers`. _Done — plus `input_data_source` + `dataset_is_synthetic` guards;
      `not-ready` source caps `confidence` at `low` (CHECK)._
- [x] Every table carries `(rodovia, sentido, km, capturado_em)` or joins to them via `trecho_id`.
      _Done — documented in the data dictionary §1._
- [x] Express as SQL DDL (SQLite-valid, Postgres-portable) in `src/greenv_vegpred/schema.sql` plus
      a data dictionary. _Files written; DDL **not yet executed** in a SQLite engine (see
      completion criteria)._

**Completion criteria**

- DDL applies cleanly to a fresh SQLite database. — _**pending execution**; `schema.sql` written
  to SQLite-valid forms but not run this session._
- the data dictionary lists every column with type, unit, nullability and its provenance label.
  — _**done** (`reports/data-dictionary.md`)._
- a round-trip test inserts and reads one sample row per table. — _**not done**; belongs to
  Phase 11 (tests)._

**Artifacts**

- `src/greenv_vegpred/schema.sql`;
- `reports/data-dictionary.md`;
- seeded `trecho` table for SP-021.

**Dependencies:** Phase 0; Phase 1 (parameter needs shape the feature row).

**Main risks**

- `km` needs the linear reference GreenV lacks in-repo — V1 uses a synthetic one, flagged;
- an over-wide `feature_row` invites leakage — guarded in Phases 5 and 8.

## Phase 3 — Real weather acquisition and preparation

**Objective.** Obtain and cache real historical weather for the SP-021 corridor at weekly
resolution, at least three years, ready to drive both the generator and the models.

**Tasks**

- [x] `scripts/fetch-weather.py`: pull Open-Meteo historical for a grid along SP-021, mapped to
      `trechos` by nearest. Keyless; cache raw responses under `data/real/weather/raw/` with
      `retrieved_at`. _Done — `scripts/fetch_weather.py` (~2 km sampling). Model `era5_seamless`
      (pure `era5_land` returns NULL radiation/ET0). 16 sample points → **4** distinct cells cover
      km 0–29.3; `data/real/weather/processed/trecho_weather_cell.csv` maps all 118 trechos._
- [x] Cross-check against the nearest INMET station(s); quantify the discrepancy.
      _INMET API unreachable from this environment (connection reset, 3 endpoints). **Substituted
      NASA POWER** (reanalysis-independent of ERA5); discrepancy quantified in `weather-eda.md` §7
      and `data/real/weather/qc/openmeteo_vs_nasapower.json` (Tmean bias −2.1 °C, precip daily
      r 0.71, annual precip diverges up to ~28 % in dry years). True station cross-check deferred._
- [x] Derive GDD (documented base temperature), 7 / 14 / 30-day rolling rain, rolling mean temp,
      humidity if present. _Done — `weather_weekly_features.csv`: `gdd_week` for Tb ∈ {10,15,17}
      (15 = `literature_derived` reference, **not** asserted), `rain_7d/14d/30d`, `tmin_7d`,
      `tmean_7d`, `rh_week`, `shortwave_7d`, `et0_7d/30d`, `water_deficit_7d/30d`._
- [x] Seasonality features: week-of-year, month, `sin`/`cos` of day-of-year, SP wet/dry-season
      flag. _Done — added to `weather_weekly_features.csv` (`month`, `week_of_year`, `day_of_year`,
      `season_sin`, `season_cos`, `dry_season_flag`)._
- [x] Write `weather_observation` rows with `data_source = 'weather_real'`, `provider`,
      `retrieved_at`. _Done — `weather_observation.csv`, 5 360 daily rows, every row
      `provenance = weather_real` + `license` + `raw_response_ref`._
- [x] `reports/weather-eda.md`: coverage, gaps, seasonal profile, Open-Meteo vs INMET overlay.
      _Done — coverage/gaps/QC, monthly climatology, spatial-precision section, Open-Meteo vs NASA
      POWER cross-check._

**Completion criteria**

- ≥3 years of weekly weather for every SP-021 `trecho`, no unexplained gap > 1 week. — _**met**:
  2023-01-01 → 2026-09-01 (3.7 y); 0 missing days / 0 gaps across 4 cells; every trecho mapped._
- raw responses cached; `make weather` reproduces the processed set. — _**partly**: raw cached
  verbatim; reproduced by `python scripts/fetch_weather.py && python scripts/build_weather.py`
  (a `Makefile` target is Phase 0)._
- provenance columns populated; the INMET discrepancy is quantified. — _**met** (provenance);
  INMET **substituted** by NASA POWER cross-check (see above)._

**Artifacts**

- `data/real/weather/` (raw + processed) — _**done**; processed as **CSV** not parquet
  (`pyarrow` not installed; trivial to convert once Phase 0's env exists)._
- `reports/weather-eda.md` — _**done**._
- populated `weather_observation` — _**done** as `data/real/weather/processed/weather_observation.csv`
  (DB not yet created; Phase 2 schema not executed)._

**Dependencies:** Phase 2 (schema); Phase 1 (which variables, GDD base temperature).

**Main risks**

- reanalysis-grid coarseness against a 30 km road;
- INMET station sparsity / downtime;
- attribution obligations (Open-Meteo CC-BY) — recorded in `data/README.md`;
- this is a real external fetch — free and keyless (no paid-work concern under `AGENTS.md`), but it
  must be run explicitly and its date recorded.

## Phase 4 — Realistic synthetic dataset generator

**Objective.** Produce a labelled synthetic height time series for all SP-021 `trechos`, driven by
the real weather and the literature parameters, with a documented generative model and a
`synthetic` stamp on every row.

**Tasks**

- [x] Specify the generative model in `reports/synthetic-model.md`: (a) logistic (baseline) /
      monomolecular (OOD) regrowth toward a plateau, (b) daily increment = f(GDD, water balance,
      tiny photoperiod residual) with coefficients from `params.yaml` ranges, (c) `roçada` events
      resetting to `rocada_residual_height_cm` with a lag ramp, (d) per-`trecho` random effects
      (`m_i`, `s_i`, `Hmax_i`, `trig_i`, `veg_type`), (e) `true_height_cm` vs
      `observed_height_cm = true + N(0, σ_meas)` + a clustered missing-week process, (f) an
      `operational_status`/blocker process. _Done — Part A; §11 records a post-QC calibration._
- [x] Implement `src/greenv_vegpred/synth/` + `scripts/generate_synthetic.py` with a fixed
      `--seed`. _Done — `synth/{params,generator}.py`; `generate_synthetic.py` runs main/seed2/ood._
- [x] Generate a `roçada` schedule (policy-driven + operational scatter) → `rocada_event` rows
      with `source = 'synthetic'`. _Done — per-trecho trigger `N(50,8)` + `Gamma` delay + seasonal
      rhythm + 10 % miss + ~5 % proactive; `rocada_events.csv` per dataset._
- [x] Emit `height_observation` rows with `data_source = 'synthetic'` and `provenance` naming the
      generator version + seed + a params hash. _Done — every row: `data_source=synthetic`,
      `provenance=synthetic`, `generator_version`, `generator_seed`, `generator_params_hash`._
- [x] Produce ≥3 simulated years × ≈118 `trechos` × weekly. _Done — 2023-01-01 → 2026-09-01
      (3.7 y), 118 trechos, ~193 ISO weeks, ~17 k observations per dataset after ~24 % missingness._
- [x] Add an out-of-distribution holdout: a second seed / different parameter draw. _Done, and
      **more than a reseed**: `ood` changes the hydric response (logistic → piecewise-linear,
      harsher floor), the trecho-effect distribution (unimodal → bimodal), the maintenance regime
      (longer delay, higher trigger, July stand-down) and the regrowth curve (logistic →
      monomolecular). Plus `seed2` for seed-variance._
- [x] Sanity plots: height traces, weekly-growth distribution, seasonal amplitude, post-`roçada`
      regrowth shape, growth vs GDD, growth vs water deficit, Nível distribution, between-trecho
      heterogeneity. _Done — 8 SVG figures per dataset in `reports/figures/synthetic/`, each with a
      SYNTHETIC watermark._

**Completion criteria**

- one command regenerates the full dataset deterministically — _**met**:
  `python scripts/generate_synthetic.py` (fixed seeds 42 / 1337 / 2024; `generator_params_hash`
  changes if any parameter changes)._
- every synthetic row carries `data_source = 'synthetic'` + generator provenance — _**met**._
- `synthetic-model.md` documents every equation and parameter with its Phase 1 citation —
  _**met**; §10 tags each parameter LC / LU / H; §11 is the post-QC audit trail._
- sanity plots reviewed against the literature; the OOD holdout exists — _**met** (growth×GDD
  monotone, ~6.5× seasonal swing, sigmoid regrowth, no negative/absurd heights); OOD exists and
  differs by mechanism._

**Artifacts**

- `data/synthetic/` (parquet + a manifest with seed and params hash);
- `reports/synthetic-model.md`;
- `src/greenv_vegpred/synth/`;
- `reports/figures/synthetic/` (watermarked).

**Dependencies:** Phase 1 (parameters); Phase 2 (schema); Phase 3 (real weather as the driver).
Must respect the frozen weather decisions in
`services/greenv-vegetation-prediction/reports/methodology-decisions.md` (`weather-v1`): corridor-
common rain/radiation, per-cell GDD, `Tb ∈ {10,15,17}` swept, `P − ET0` as the water term.

**Main risks**

- **circularity** — if the generator and a model share a functional form, ML "wins" trivially.
  Mitigate: the generator uses mechanisms models are not told; the OOD holdout; Phase 8 reports
  this risk in the headline.
- a reader mistaking realistic synthetic output for a validated reading — mitigate: watermark
  every figure and table.
- parameter ranges dominated by temperate agronomy.

## Phase 5 — Exploratory analysis

**Objective.** Understand the dataset, quantify the relationship between each candidate variable
and growth, and surface leakage or confounding before modelling.

**Tasks**

- [x] `notebooks/01-eda.ipynb`, exported to `reports/eda.md` + figures: distributions, missingness,
      per-`trecho` trajectories, seasonal decomposition. _Done, **without a notebook** — no Jupyter
      in this environment; `scripts/eda_features.py` produces the same numbers/figures directly
      into `reports/eda.md` + `reports/figures/eda/*.svg` (9 figures). Content equivalent; format
      differs from the task's literal wording._
- [ ] Correlation and mutual information of each candidate variable with next-week height and with
      days-to-30 cm. _**Partly done** — Pearson correlation for every `KEEP`/`CANDIDATE` numeric
      variable against both targets is in `reports/eda.md` §11. **Mutual information was not
      computed** (no MI implementation available without an extra dependency); left unchecked._
- [x] Growth-rate vs GDD, vs rolling rain, vs weeks-since-`roçada` — scatter + smoothers.
      _Done for GDD (`eda.md` §6, binned scatter) and the 30-day water balance as the
      rain-driven proxy (§7, DEC-002); rain itself is covered by correlation only, not a dedicated
      scatter. `days_since_rocada` covered via the height-regrowth curve (§8) + correlation, not a
      literal growth-rate scatter._
- [x] Leakage audit: no feature encodes the target (no future weather; `days_to_30cm` not derived
      from the same week's height without a lag). _Done — `data/features/leakage_audit.json`,
      **7/7 machine-checked assertions pass** (`overall_pass: true`); written up in `eda.md` §15._
- [x] Define the **splits**: temporal (train = first years, validation = next, test = last year)
      and a per-`trecho` blocked split. Store as the `split` column.
      _Done — `split` (temporal, with a 30-day embargo at each boundary) and
      `split_blocked_trecho` (MD5-hashed per trecho, 70/15/15) in `feature_row_dev.csv`; `ood` kept
      in a fully separate file, never split (`eda.md` §17–§18)._
- [x] Decide feature transforms and freeze the final candidate list with justification.
      _List frozen in `src/greenv_vegpred/features/feature_spec.py` (KEEP/CANDIDATE/METADATA/
      EXCLUDE_LEAKAGE/TARGET) — **decided and documented**, not yet implemented as encoder code
      (one-hot for `vegetation_type`/`blockers`, target-encoding for `trecho_id`) — that lands with
      the Phase 7 model pipeline._

**Completion criteria**

- `reports/eda.md` published with figures. — _**met**._
- splits defined and stored. — _**met**._
- the leakage audit is written and passes. — _**met**._
- the final feature list is frozen in `src/greenv_vegpred/features/`. — _**met**
  (`feature_spec.py`)._

**Artifacts**

- `reports/eda.md`; `reports/figures/eda/` — _**done**._
- the `feature_row` materialiser — _**done**, `src/greenv_vegpred/features/build.py` +
  `scripts/build_features.py`; output as CSV (`data/features/feature_row_dev.csv`,
  `feature_row_ood_holdout.csv`), not loaded into the Phase 2 SQLite schema (still not executed)._
- the split definition — _**done**, two schemes, see above._

**Dependencies:** Phase 3; Phase 4.

**Main risks**

- EDA conclusions describe the generator, not reality — stated on every finding;
- look-ahead bias in feature construction;
- small effective sample (weekly × 3 years ≈ 156 points per `trecho`).

## Phase 6 — Statistical baseline

**Objective.** Establish naïve and simple statistical baselines, plus a transparent mechanistic
baseline, that every ML model must beat.

**Tasks**

- [x] Naïve: persistence (next week = this week); last weekly growth carried forward;
      seasonal-naïve (same week last year). _Done for persistence and last-growth. The literal
      "same trecho, 52 weeks ago" seasonal-naïve was **deliberately not implemented** — roçada
      happens ~5.96×/trecho/year (median interval 51 d), so a 52-week lookback almost always lands
      in an unrelated cycle; implementing it would have been checklist-cosmetic. Substituted with
      `seasonal_climatology` (train-period mean height by calendar month, pooled across trechos) —
      the defensible form of "historical/seasonal", per `reports/model-comparison.md`._
- [x] Mechanistic: linear growth since last `roçada` at a per-`trecho` **fitted** rate; a GDD-scaled
      variant. _Done in a follow-up pass — `MechanisticHeightBaseline` /
      `MechanisticDaysUntil30Baseline` in `baseline.py`: a per-trecho since-cut rate fit on
      `train` only (pooled across `main`+`seed2`, 118/118 trechos reached the 5-sample floor),
      scaled by a GDD persistence-of-climate multiplier (Tb=15 °C **reference**, DEC-003-compliant)
      and a `days_since_rocada`-gated lag-damping term. No water-deficit term — not required by
      this task's original wording and none was added unrequested. Full formula, leakage guards
      and the "why pooled by trecho, not by (dataset,trecho)" reasoning in
      `reports/model-comparison.md`._
- [x] Days-to-30 cm from each baseline (forward iteration / inversion). _Two dedicated baselines
      now: `naive_days_until_30cm` (Phase 6 first pass) and `mechanistic_days_until_30cm` (this
      pass) — still not "from each height baseline separately" (e.g. no
      persistence-based or seasonal-climatology-based days-to-30cm variant), which was never
      requested._
- [x] Implement in `src/greenv_vegpred/models/baseline.py`; run through the Phase 8 harness on the
      validation split. _`baseline.py` done. **The shared harness now exists**:
      `src/greenv_vegpred/evaluate/harness.py` — `evaluate_height_model`,
      `evaluate_days_until_30cm_model`, `stratify_height`/`stratify_days`, against a duck-typed
      `.fit`/`.predict_height`/`.predict` protocol Phase 7 models can implement directly. Not
      literally "the Phase 8 harness" (Phase 8 itself — backtesting, block-bootstrap CI, ablation,
      calibration — is still unbuilt), but the metric/stratification core Phase 8 would otherwise
      have had to write is done and reused, not duplicated._
- [x] Record the numbers in `reports/model-comparison.md` (table stub). _Done — real numbers, not a
      stub; dataset = `feature_row_dev.csv` (`main`+`seed2`, `provenance=synthetic`), Phase 5
      temporal split; regenerated after the harness migration, with an explicit before/after diff
      confirming `persistence`/`last_growth`/`seasonal_climatology`/`naive_days_until_30cm` are
      byte-for-byte unchanged._

**Completion criteria**

- all baselines run through the shared harness. — _**met** — `persistence`, `last_growth`,
  `seasonal_climatology`, `mechanistic` (height) and `naive_days_until_30cm`,
  `mechanistic_days_until_30cm` (days-to-30cm) all run through
  `greenv_vegpred.evaluate.harness`, not inline per-script logic._
- MAE / RMSE / R² / days-error recorded with the dataset version they were computed on. — _**met**
  (`reports/model-comparison.md`; raw numbers also in `data/models/baseline_validation_metrics.json`)._
- the mechanistic baseline is documented. — _**met** — full formula, the per-trecho fit method
  (never crosses a roçada), the GDD persistence-of-climate assumption, the `days_since_rocada`
  lag-damping term, and why no water-deficit term, all in `reports/model-comparison.md` and the
  `baseline.py` docstrings._

**Artifacts**

- `src/greenv_vegpred/models/baseline.py`;
- `src/greenv_vegpred/evaluate/harness.py`;
- baseline rows in `reports/model-comparison.md`.

**Dependencies:** Phase 5 (splits, features); the Phase 8 harness (co-developed — the harness
built here covers metrics/stratification; Phase 8's backtesting/ablation/calibration remain to be
added on top of it).

**Main risks**

- weekly persistence is genuinely hard to beat at a 7-day horizon — if ML does not beat it there,
  that is a finding, keep it;
- seasonal-naïve needs ≥1 year of history and can leak if the split is careless.

## Phase 7 — Candidate machine-learning models

**Objective.** Implement the four required families against one feature matrix and one interface,
with no winner pre-selected.

**Tasks**

- [x] A common `Model` protocol: `fit(train_rows)`, `predict_height(row, horizon_days)`,
      `predict(row)` for `days_until_30cm` — the duck-typed protocol from the Phase 6
      `evaluate/harness.py`, reused as-is (no separate `predict_interval`; that is Phase 9's scope).
- [x] The Phase 6 baselines (`persistence`, `seasonal_climatology`, `mechanistic`,
      `mechanistic_days_until_30cm`) already implement this protocol and are reused directly as the
      comparison point — not re-wrapped.
- [x] **OLS / Ridge / Lasso** (`linear.py`), standardised features, chosen on validation.
      Ridge was the original pass's representative (`alpha` grid, `alpha=0.01` already standing
      in for near-unregularised OLS). The addendum pass then added true `LinearRegression` (OLS)
      and `Lasso` (`alpha ∈ {0.001, 0.01, 0.1}`) on the `keep_plus_candidate` feature set and
      compared all three fairly (same preprocessing, same feature set, TRAIN fit / VALIDATION
      selection): **OLS and Lasso add no value over Ridge** (avg height MAE 14.709 / 14.709 /
      14.707; days MAE 18.48 / 18.48 / 18.45 — differences within noise), and none approaches
      Random Forest (11.369 / 11.80). See `reports/hyperparameters.md` §6.1. Both the **direct**
      per-horizon framing (original pass) and a **recursive one-step** framing (addendum, reusing
      the existing `random_forest__keep` artifact with no retraining) were implemented and
      compared: the recursive framing is **worse at every horizon beyond +7d** (+9.5% MAE at
      +14d, +28.5% at +30d — the "recursive multi-step error accumulation" risk this file itself
      names), so the direct framing remains the one carried forward. See §6.2.
- [x] **Random Forest** (`random_forest.py`).
- [x] **Gradient Boosting** (`gradient_boosting.py`): scikit-learn `HistGradientBoosting`; no
      native library was justified or added — scikit-learn already had to be installed for this
      phase (absent from the base environment) and no concrete reason to add a second dependency
      arose.
- [x] Hyperparameter search on the validation split only (documented grid, fixed seed 42) — see
      `reports/hyperparameters.md`.
- [x] Export coefficients / feature importances for each (for Phase 13): Ridge coefficients, RF
      impurity importances, and permutation importance for all three algorithms.
- [x] Record both prediction framings for `days_until_30cm`. Framing (B) — predict
      `days_until_30cm` directly, with capped-at-120-days handling of censoring — was implemented
      in the original pass. Framing (A) — derive the crossing day from the already-fitted height
      model's own +7d/+14d/+30d predictions via interpolation/one bounded extrapolation — was
      added in the addendum pass, reusing the existing `random_forest__keep_plus_candidate`
      height artifact with no retraining. Compared on VALIDATION: framing (A) mean MAE (13.75) is
      worse than framing (B)'s (11.80) — its extrapolated tail carries large errors, as expected —
      but its median (6.29) and %±3d/%±7d (39.6%/55.8%) are actually *better* than framing (B)'s
      (7.51 / 38.0%/48.7%), a heavy-tailed-error pattern recorded rather than collapsed into one
      verdict. Framing (B) remains the frozen configuration (better mean MAE, full coverage — it
      never refuses an answer on a non-censored row); framing (A) is documented as a viable,
      fully-derived second opinion, not adopted as primary. See `reports/hyperparameters.md` §6.3.

**Completion criteria**

- [x] the implemented families (OLS, Ridge, Lasso, Random Forest, HistGradientBoosting) train and
      predict through the shared harness protocol on the frozen matrix;
- [x] hyperparameters chosen only on `validation` (`TEST` never scored, `OOD` never opened);
- [x] `scripts/train_and_evaluate_ml.py` (+ `scripts/train_and_evaluate_ml_addendum.py` for the
      OLS/Lasso/recursive/framing-A items) reproduces every fitted model from the frozen feature
      matrix + fixed seed (`random_state=42` throughout);
- [x] importances / coefficients exported to `data/models/ml_validation_metrics.json` →
      `importances`.

**Artifacts**

- `src/greenv_vegpred/models/{baseline,ml_common,linear,random_forest,gradient_boosting,recursive,height_trajectory_days}.py`
  (`ml_common.py` holds the shared preprocessing pipeline and the two sklearn-backed model classes
  used by all three main algorithms; `recursive.py` and `height_trajectory_days.py` are addendum
  wrappers around already-fitted artifacts — no new training in either);
- `models/artifacts/` (12 fitted `.joblib` models from the original pass + `manifest.json`; 4 more
  — OLS/Lasso × height/days_until_30cm, on `keep_plus_candidate` — from the addendum, +
  `manifest_addendum.json`);
- `data/models/ml_validation_metrics.json` (original pass: validation metrics, selections,
  importances, stratifications) and `data/models/ml_validation_metrics_phase7_addendum.json`
  (addendum: OLS/Ridge/Lasso comparison, direct-vs-recursive comparison, framing A-vs-B
  comparison — a separate, clearly-versioned file; the original is untouched);
- `reports/hyperparameters.md`.

**Dependencies:** Phase 5; Phase 6.

**Main risks**

- overfitting to synthetic structure;
- a native GBM library adds a build dependency — prefer scikit-learn to stay pip-only;
- recursive multi-step error accumulation.

## Phase 8 — Evaluation and comparison

**Objective.** One honest, reproducible comparison of every model and baseline on held-out data,
with the required metrics and stated uncertainty about the numbers themselves.

**Tasks**

- [x] `src/greenv_vegpred/evaluate/` (the Phase 6 harness, reused as-is) +
      `scripts/evaluate_phase8.py`: MAE and RMSE (primary) and R² (auxiliary) for +7 / +14 / +30
      day height; absolute error in days for the 30 cm arrival (median, distribution, share within
      ±3 and ±7 days) — all on TEST, computed once.
- [x] Backtesting: rolling-origin evaluation (5 origins, 2025-03-01 → 2026-03-01, 30-day embargo,
      120-day window, growing training window) — **refit** at each origin with the frozen Random
      Forest hyperparameters (never re-tuned), documented in `reports/model-comparison.md` §Phase
      8. Found real temporal instability (+7d MAE ranges 7.3–13.7 cm across origins), reported
      rather than smoothed over.
- [x] Breakdowns: per Nível, per season, per roçada-proximity, per `operational_status`, and per
      history-completeness on TEST (per-`trecho` breakdown was judged too fine-grained to be
      individually meaningful at ~70 rows/trecho on TEST — the requested breakdowns are the ones
      that group trechos into interpretable strata, which is what "per trecho" was for).
- [x] Block-bootstrap (by `trecho_id`, respecting within-trecho dependence, 500 resamples) 95% CIs
      for height MAE +7/+14/+30d and `days_until_30cm` MAE, on TEST.
- [x] Compare framing A vs B for days-to-30 cm, on TEST (framing B/direct: 10.24 d MAE; framing A/
      height-trajectory, secondary analysis: 11.76 d MAE — B remains frozen).
- [x] Ablation (on VALIDATION, not TEST — see `reports/model-comparison.md` §Phase 8 for why):
      height/history, roçada/cycle, temperature/GDD, rain/water-deficit, additional candidates —
      quantified, height/history dominates by 2–7× any other single group.
- [ ] Calibration of the interval predictions. *Scope note:* no prediction interval exists yet to
      calibrate — `predict_interval(...)` is explicitly Phase 9's task (§Phase 9 below), not
      Phase 7's. This item is deferred to Phase 9, where it belongs, rather than forced here
      against a stated interval that doesn't exist.
- [x] `reports/model-comparison.md`: the final ranked table (baseline vs Ridge vs Random Forest vs
      HistGB, VALIDATION → TEST → OOD) with CIs, and a written selection decision — Random Forest
      remains recommended, with its OOD degradation (22–28% height MAE) stated as a real, named
      limitation rather than absorbed into the recommendation silently.

**Completion criteria**

- [x] the full evaluation (TEST, rolling-origin, OOD, ablation, CIs, stratification) reproduces
      from `scripts/evaluate_phase8.py` + the frozen Phase 7 artifacts — no `make evaluate` target
      exists in this project (no Makefile anywhere in the repo), so the equivalent reproducing
      command is the script's direct invocation, documented in the script's own docstring;
- [x] every number carries its split (VALIDATION/TEST/OOD) and, for the four headline TEST
      metrics, a 95% CI;
- [x] ablation done (on VALIDATION, documented why); calibration of prediction intervals is
      deferred to Phase 9 (no interval exists yet — see above, not silently dropped);
- [x] Random Forest (`keep_plus_candidate`, the Phase 7 frozen configuration) is selected to carry
      forward into Phase 9, with the written rationale in `reports/model-comparison.md`'s "Final
      decision (Phase 8)" section.

**Artifacts**

- `reports/model-comparison.md` (extended, not replaced — the Phase 6 baseline section is
  unchanged, Phase 8's evaluation is appended as its own section);
- `scripts/evaluate_phase8.py`; `data/models/phase8_final_evaluation.json`.
  *Scope note:* `reports/figures/eval/` and `evaluate/results.parquet` (TASK.md's original
  artifact list) were not produced — the concrete Phase 8 instruction given for this pass asked
  for numeric tables and JSON, matching every other phase's convention in this project (none of
  which uses parquet or produces plots), and introducing `pandas`/`pyarrow` or a plotting library
  for this alone was not judged justified without being asked for.

**Dependencies:** Phase 6; Phase 7.

**Main risks**

- metric-shopping across horizons — fix the primary metric and horizon up front;
- synthetic evaluation ≠ real performance — the headline caveat of the whole report;
- days-error is undefined for `trechos` that never reach 30 cm in-window — define the censoring
  rule and report the censored fraction.

## Phase 9 — Days-to-30 cm prediction and uncertainty interval

**Objective.** Turn the selected model into the operational output: per `trecho`, days-to-30 cm +
a calibrated uncertainty interval + the +7 / +14 / +30 heights, with `operational_status` carried
through.

**Tasks**

- [x] `src/greenv_vegpred/forecast/`: from the latest observation, produce `days_until_critical`
      (the operational `days_until_30cm` answer) via the already-frozen Phase 7 model (framing B,
      direct regression) — not a forward-simulation loop against a weather forecast. *Scope note:*
      the model already regresses `days_until_30cm` directly from the anchor row's own features
      (no recursive stepping; Phase 7's addendum already found recursive stepping strictly worse —
      `reports/hyperparameters.md` §6.2), so "forward-simulate to 10cm/30cm" is answered by that
      existing regression, not a new simulation loop; weeks-since-`roçada` is already
      `days_since_rocada`, a frozen KEEP feature.
- [x] Uncertainty interval: **split conformal prediction** (Vovk et al. 2005 / Lei et al. 2018),
      calibrated on VALIDATION only, frozen before TEST was read, measured on TEST once — see
      `reports/forecast-method.md` for the method choice and the measured coverage.
- [x] **Weather-forecast input for V1 — closed by architectural decision, not left open.**
      Verified directly (not assumed): the frozen V1 primary model (framing B, direct regression)
      does not consume a forward weather trajectory at all — it regresses from the anchor row's
      own already-observed features, with no forward-simulation loop for a forecast to feed. The
      one framing that does step forward (the recursive height model, Phase 7 addendum) was
      already evaluated and found strictly worse beyond +7d (`reports/hyperparameters.md` §6.2);
      reinstating it only to have a place for a weather input would mean reintroducing an
      already-rejected, worse framing. **Decision, recorded here: not applicable to the selected
      V1 primary framing; a future weather trajectory belongs to the secondary trajectory model
      (framing A) or a V2 recursive redesign, neither of which is the frozen production path.**
      See `reports/forecast-method.md` §13.
- [x] Censoring: `days_until_critical = null`, `status = "beyond_horizon"` when the model's own
      point estimate exceeds the 120-day horizon — implemented in `build_days_forecast`.
- [x] `operational_status`/`blockers` are now carried EXPLICITLY inside every forecast object
      (not just joinable after the fact), and a `not-ready` source caps a new, separate
      `operational_confidence` field at `"low"` — implemented as `_operational_confidence` in
      `interval.py`, matching `schema.sql`'s own pre-existing `prediction.confidence` CHECK
      constraint (`operational_status <> 'not-ready' OR confidence = 'low'`) verbatim. This is
      kept distinct from the interval's own statistical `confidence` (0.80/0.90, the nominal
      coverage level) — the two are never merged into one field. See
      `reports/forecast-method.md` §3's "two different questions" subsection.
- [x] Ranking feed: `rank_forecasts()` (`src/greenv_vegpred/forecast/ranking.py`), ordering
      `critical` → `forecast` (ascending `days_until_critical`) → `beyond_horizon` →
      `insufficient_data`, ties broken by `trecho_id`. Demonstrated on the current 118-trecho
      snapshot (`data/forecast/ranking_current.json`/`.csv`) — see `reports/forecast-method.md` §11.
- [x] `reports/forecast-method.md`.

**Completion criteria**

- [x] the reproducing entry point (`scripts/calibrate_and_evaluate_intervals.py` for the
      calibration/coverage, `scripts/build_current_forecast_batch.py` for the ranking/batch
      export — no `make` target exists anywhere in this repository, same note as Phase 8)
      regenerates every Phase 9 artifact from the unmodified Phase 7 model artifacts;
- [x] interval coverage is reported against nominal (80%/90%) on TEST, with the deviation stated
      explicitly (days: 83.2%/90.8%, mildly conservative; height: 75.7-76.4%/86.4-87.5%, mildly
      under nominal) — not silently accepted as "close enough", and the conformal coverage
      *guarantee*'s own assumptions (exchangeability, in tension with VALIDATION's role in Phase 7
      model selection and with this data's temporal structure) are now stated explicitly rather
      than asserted unconditionally — `reports/forecast-method.md` §2's callout;
- [x] the censoring rule (`beyond_horizon`) is applied and counted; every forecast object carries
      `data_provenance`, `operational_status`, `blockers`, and `operational_confidence` directly;
- [x] the ranking export is built and reproducible (§11 above).

**Artifacts**

- `src/greenv_vegpred/forecast/{__init__,interval,ranking}.py`;
- `data/models/interval_calibration.json` (frozen calibration parameters);
- `data/models/phase9_interval_evaluation.json` (TEST coverage, OOD diagnostic, sanity checks);
- `data/forecast/ranking_current.json`/`.csv` (the ranking export, 118 rows);
- `data/forecast/prediction_batch.csv` (the V1-equivalent of `schema.sql`'s `prediction` table);
- `reports/forecast-method.md`.
  *Correction to this file's own earlier note:* an earlier pass of this checklist claimed
  "there is no `prediction` table in `schema.sql`" — that was checked directly this time and was
  **wrong**: `schema.sql` (Phase 2) already defines a full `prediction` table (lines 356-406),
  including the exact `interval_nominal_coverage`/`confidence` split and the
  not-ready-caps-confidence-at-low rule this pass implemented. No migration was made to it — the
  table definition is untouched; `prediction_batch.csv`'s columns map onto it directly (see
  `reports/forecast-method.md` §12 for the column-by-column mapping and why a CSV, not a live
  `INSERT`, is this V1's equivalent artifact, consistent with every other phase's CSV/JSON output).

**Dependencies:** Phase 8 (selected model + interval method).

**Main risks**

- compounding error over 30 days;
- interval under-coverage;
- a climatology fallback hiding weather-driven spikes;
- users reading the point estimate and ignoring the interval — designed against in the API and the
  docs.

## Phase 10 — Self-contained API

**Objective.** A read API serving the forecasts, history and ranking from the module's own local
store, matching the Phase 0 output contract, with no dependency on RabbitMQ or the GreenV DB.

**Tasks**

- [x] FastAPI app: `src/greenv_vegpred/api/{__init__,app,routes,service,loader,schemas,trecho_meta}.py`.
      *Scope note:* backed by the Phase 9 JSON snapshot (`data/forecast/ranking_current.json`),
      not a SQLite store — no SQLite (or any) database was built anywhere in this project's
      Phases 1-9, so "backed by the local SQLite store" describes infrastructure that does not
      exist; the actual instruction given for this phase confirmed this directly (read
      `schema.sql`, `src/greenv_vegpred/forecast/`, the Phase 9 artifacts) rather than assuming
      the original text, and asked for a read API over the already-built forecast/ranking
      objects instead. Adding a SQLite layer now, only to satisfy this line, would be new
      unrequested infrastructure, not exposing existing work.
- [x] Endpoints — *scope note:* the actual instruction named a different, smaller set than this
      line's `/v1/trechos*`/`history`/`rocada-events` list (nothing in Phases 1-9 produces a
      trecho history table or accepts a `rocada` write, so those would be invented, not exposed).
      Implemented: `GET /health`, `GET /api/v1/summary`, `GET /api/v1/forecasts/ranking`,
      `GET /api/v1/forecasts` (filtered list), `GET /api/v1/forecasts/{trecho_id}`,
      `POST /api/v1/forecasts/predict`. See `reports/api.md` for the full contract and the reason
      each TASK.md-named endpoint not built would have meant inventing unrequested scope.
- [x] Response models expose Nível 0-3 as `vegetation_level`, closed in the Phase 10 closure
      pass. Read `apps/web/src/utils/classification.js` directly (read-only; `apps/web`
      untouched) and found it **fully compatible** with `schema.sql`'s own
      `height_observation.nivel` GENERATED formula — both use `not-ready`/missing height → `0`,
      `<10cm` → `1`, `10-30cm` → `2`, `>30cm` → `3`. No second classification system was created:
      one function (`api/trecho_meta.py::vegetation_level`) implements the one already-frozen
      rule, applied once at the API boundary. `Forecast.status`/`operational_confidence` (Phase
      9's own vocabulary, about the *forecast*, not the *current level*) are unchanged and kept
      alongside `vegetation_level` (about the *current observation*) — genuinely different
      questions, not a duplicate contract.
- [x] `(rodovia, sentido, km, capturado_em)` — closed. Verified `trecho_id`'s documented format
      in `schema.sql` (`rodovia:sentido:km_start_m`) first, then verified `km_end` against every
      row of `feature_row_dev.csv` + the OOD holdout (50,655 rows) before deriving it — 116 of
      118 trechos are a uniform 500 m, but the corridor's final ~300 m segment in each direction
      is shorter, so `km_end = min(km_start + 0.5, 29.3km)` (the documented corridor length),
      which reproduces every trecho's real `km_mid`-implied end exactly, including those two.
      `Forecast` now exposes `rodovia`, `sentido`, `km_start`, `km_end`, and `captured_at`
      (`= as_of_date`, since this pipeline has no timestamp finer than a date — not fabricated).
      The API derives these server-side (`api/trecho_meta.py::parse_trecho_id`); a dashboard
      never has to parse `trecho_id` itself.
- [x] `GET /health` reports `model_available`, `calibration_available`, `snapshot_available`, and
      `data_provenance: "synthetic"` — the synthetic-data marker this task asks for, under the
      name this phase's actual instruction used for it.
- [x] Generated OpenAPI (`/openapi.json`, confirmed live) snapshotted to `api/openapi.v1.yaml` —
      regenerated FROM the running app's own schema (see `reports/api.md`), never hand-maintained
      as a second contract, so "diffed against" is structurally impossible to fail.
- [ ] `make serve`. *Scope note:* no `Makefile` exists anywhere in this repository (same note as
      Phases 8-9) — the reproducible command is `uvicorn greenv_vegpred.api.app:app --app-dir src`,
      documented in `reports/api.md`.
- [x] `scripts/demo.http` collection — covers every endpoint, including the 404/422/503 cases.
- [x] The persistence layer (the Phase 9 JSON snapshot + frozen `.joblib`) uses no SQLite-only
      feature — trivially true, since it is not SQLite at all; no portability risk exists here.

**Completion criteria**

- [x] the documented command starts the API; every endpoint returns example SP-021 data from the
      real Phase 9 snapshot (118 trechos) — verified live, see `reports/api.md`'s smoke-test table;
- [x] responses validate against the generated (not hand-maintained) OpenAPI schema, by
      construction — the schema IS `app.openapi()`, and `api/openapi.v1.yaml` is a snapshot of it;
- [x] a synthetic-data marker (`data_provenance: "synthetic"`) is present on `/health` and on
      every forecast/list/ranking response;
- [x] no import from `measurement/`, no RabbitMQ, no GreenV DB connection — verified by
      inspection of every file under `src/greenv_vegpred/api/`.

**Artifacts**

- `src/greenv_vegpred/api/{__init__,app,routes,service,loader,schemas,trecho_meta}.py`;
- `api/openapi.v1.yaml` (regenerated snapshot, in sync by construction);
- `scripts/demo.http`;
- `reports/api.md` (architecture, endpoint contract, examples, smoke-test results);
- `pyproject.toml` (minimal, this-service-only dependency declaration: fastapi, uvicorn, pydantic,
  plus the numpy/scikit-learn/joblib already required by Phases 4-9).

**Dependencies:** Phase 2 (schema); Phase 9 (predictions to serve).

**Main risks**

- API shape drifting from the architecture doc — addressed here by generating the OpenAPI
  snapshot FROM the app rather than maintaining it separately, so drift is structurally impossible;
- SQLite-isms leaking into the schema — not applicable; no SQLite exists in this pipeline;
- the contract not matching a future Java gateway — kept in `api/openapi.v1.yaml`, to be reviewed
  whenever Phase 12 (or a real integration) actually happens.

## Phase 11 — Tests

**Objective.** A green-tick loop covering the data contracts, the generator, features, each model
interface, the evaluation harness, the forecaster and the API.

**Tasks**

- [x] `pytest` suites, 146 tests across 10 files (`tests/`): schema round-trip (real, temporary
      SQLite, `schema.sql` executed for the first time since Phase 2); feature-spec leakage
      assertions (fails on any future leakage column, by construction); roçada-cycle causality in
      the real `build_trecho_rows` (a future roçada provably never reaches a feature); each
      model's protocol on tiny/stubbed fixtures; the conformal-quantile formula hand-checked on a
      toy set; the forecaster's censoring + `operational_status`→`operational_confidence`
      propagation (all 8 required cases); API contract tests against the live `app.openapi()`.
      *Scope note:* "envelope reader against `measurement-result-v1.json`" and "linear-reference
      resolver" belong to `measurement/`/a different module's Phase 0-3 work, not this one — no
      such reader or resolver exists in `greenv-vegetation-prediction`, so nothing was invented to
      test here. "Generator determinism (same seed → same bytes)" was not added this pass — the
      actual instruction given for Phase 11 enumerated a specific, narrower set of 16 test
      categories, none of which named the Phase 4 generator directly, and re-running it to test
      byte-determinism would mean regenerating the ~50k-row synthetic corpus, out of scope for a
      "verify what already exists" pass.
- [x] A fast end-to-end smoke test (`test_pipeline_smoke.py`): the real Phase 9 snapshot → loader
      → service → one served HTTP request, cross-checked against a direct single-trecho request.
      Runs in a few milliseconds (no training involved, per this phase's explicit instruction),
      not "roughly a minute" — there is no training step in this API's request path to make it
      slower, unlike the Java-worker analogy this line was modelled on.
- [x] `scripts/verify.sh`: `pytest` + an OpenAPI-in-sync check (`api/openapi.v1.yaml` byte-compared
      against a fresh `app.openapi()` dump). *Scope note:* `ruff` + a format check were **not**
      added — no lint/format tool was installed or requested by this phase's actual instruction,
      and adding one now would be new tooling introduced without being asked, not "verifying what
      already exists".
- [x] A verification note — closed via `reports/tests.md` rather than a module `README.md`
      section. *Scope note:* no `README.md` exists for this module (none was created in any prior
      phase); creating one now solely to hold one "verification" paragraph would be a new artifact
      beyond what this phase's actual instruction asked for, which named `reports/tests.md`
      directly (item 21) as the documentation artifact. `reports/tests.md` states what was run,
      the exact pytest/coverage commands, and the results of 2+ actual runs — the same evidentiary
      bar `AGENTS.md` asks for, just not under the `README.md` heading TASK.md's original text
      assumed would already exist.

**Completion criteria**

- [x] `./scripts/verify.sh` passes (pytest 146/146 + OpenAPI-in-sync) — run directly, not assumed;
- [x] the end-to-end smoke test runs in a few milliseconds, well under "roughly a minute" (see
      scope note above for why there is no training step to time here);
- [x] every public interface named in this phase's actual instruction has at least contract
      tests: `schema.sql`, `vegetation_level`, `parse_trecho_id`, `feature_spec`,
      `build_trecho_rows`'s roçada logic, `build_days_forecast`, `conformal_quantile`,
      `rank_forecasts`, every API endpoint, the small model artifacts, and the snapshot→API path.

**Artifacts**

- `tests/{conftest,test_schema,test_classification,test_trecho_meta,test_features,test_forecast,test_ranking,test_api,test_openapi,test_model_contract,test_pipeline_smoke}.py`;
- `scripts/verify.sh`;
- `reports/tests.md` (strategy, categories, commands, 5 actual run results, coverage by module,
  the one bug found/fixed, skip conditions, limitations);
- `pyproject.toml` extended with a `[project.optional-dependencies] test` group
  (`pytest`, `pytest-cov`, `httpx`) and `[tool.pytest.ini_options]`.

**Dependencies:** all prior phases (tests co-evolve, finalised here).

**Main risks**

- tests exercising only the synthetic happy path — addressed by explicit boundary-value tests
  (nivel at exactly 10/30 cm; conformal quantile at an insufficient sample size; model-absent
  paths) rather than only happy-path cases;
- brittle golden-file tests on generator output — not applicable this pass (generator
  byte-determinism was out of scope, see task scope note above); no golden-file test was written;
- OpenAPI drift-check flakiness — avoided by generating the check FROM the live app rather than
  maintaining a hand-written expectation (`scripts/verify.sh`), and by keeping `test_openapi.py`
  structural rather than a brittle full-file diff (see `reports/tests.md` §15).

## Phase 12 — Future integration with GreenV

**Objective.** Document — and stub, without wiring — exactly how V1 becomes a GreenV citizen, so
V2 is contract-filling rather than redesign.

> **Scope deviation, explicit and by instruction.** This phase's original text describes a
> **documentation-only** pass ("stub, without wiring"; "no existing GreenV file is touched" as a
> completion criterion). The actual, detailed instruction given for this pass explicitly asked
> for a real, minimal, HTTP-verified wiring into `apps/web` instead — implemented and tested, not
> just planned. `apps/web/src/pages/DashboardPage.jsx` **was** touched (2 lines: one import, one
> component). This is recorded here rather than silently following the older text or silently
> overriding the instruction without saying so. See `reports/integration.md` for the full
> analysis, implementation, and test evidence.

**Tasks**

- [x] Minimal V1 wiring, `apps/web` → Vegetation Prediction API (the actual instruction's
      priority, superseding this line's original "plan only" framing):
      `src/services/vegetationPredictionApi.js` (the one client: summary/ranking/forecast-by-id)
      + `src/components/VegetationPredictionPanel.jsx` (the one UI surface, added to
      `DashboardPage.jsx`). Snapshot-backed reads only — `POST /predict` is never called from the
      browser. See `reports/integration.md` §2-3.
- [x] Map the V1 local store → V2 PostgreSQL schema. *Closed by decision, not executed*:
      `schema.sql` is already written portable-by-construction (its own header: "Written to
      migrate to PostgreSQL with only the mechanical substitutions listed in
      `reports/data-dictionary.md`") — there is no new DDL to design here, and no Flyway migration
      list is produced because this V1 has no live database at all (every phase's output has been
      CSV/JSON, by established convention since Phase 2) for a migration to originate from.
- [x] Production packaging decision: **recorded as an open, deliberately undecided trade-off**
      (not decided irreversibly, per this line's own instruction) — subprocess/sidecar (the
      `greenv-measurement-worker` ↔ `measurement/` pattern, spawn + JSON, never import across) vs.
      a full Java port, with the subprocess pattern named as the lower-risk default since it is
      already proven elsewhere in this repository. See `reports/integration.md` §14.
- [x] Ingestion adapter interface + roçada event source: **documented as a future contract**
      (§15), not implemented — the actual instruction explicitly said "sem necessariamente
      implementar" for this item.
- [x] "Every GreenV change V2 would need" — closed via `reports/integration.md` rather than a
      separate `V2-CHECKLIST.md` file, per this pass's actual instruction naming
      `reports/integration.md` as the preferred single artifact; a `compose.yaml` service, an
      `AGENTS.md` row, and a queue binding are all named in §14's architecture sketch rather than
      itemised in a second file that would need to stay in sync with it.
- [x] The unresolved `marco km` linear-reference dependency — already named as `hypothesis`
      provenance in `schema.sql`'s own `trecho.linear_reference_provenance` column (Phase 2); not
      re-derived here, restated in `reports/integration.md` §11 in the context of why map
      integration specifically isn't safe yet.
- [x] `apps/web` wiring sketch — done as REAL code, not only a sketch (see scope deviation above):
      `reports/integration.md` §1 documents exactly which mock (`rocada_polygons.geojson`'s
      client-side `vegetation_level`, `mockTrends.js`, `vegetationHistory.js`) sits where, and
      which one the new panel does NOT replace (it is additive, not a replacement of any existing
      mock-fed chart).

**Completion criteria**

- [x] `reports/integration.md` documents the analysis, the real implementation, and its test
      evidence (superseding "`integration-plan.md` reviewed" — the actual instruction asked for
      an implemented-and-tested artifact, not a plan-only review);
- [x] every V1→V2 gap is a named contract (§15) or an explicitly-undecided, flagged trade-off
      (§14), not a silent open question;
- [x] the "V2-CHECKLIST" content is concrete inside `reports/integration.md` §14 (see above for
      why it is not a separate file);
- [ ] "no existing GreenV file is touched" — **not met, by explicit instruction** (see the scope
      deviation note above). `apps/web/src/pages/DashboardPage.jsx` was changed (2 lines); no
      other existing GreenV file (`apps/mobile`, `measurement/`, `infrastructure/`,
      `compose.yaml`, any other service) was touched.

**Artifacts**

- `reports/integration.md` (analysis, architecture, files changed, configuration, fallback/error
  behaviour, synthetic marker, limitations, future measurement→prediction architecture, the
  future event contract);
- `apps/web/src/services/vegetationPredictionApi.js`;
- `apps/web/src/components/VegetationPredictionPanel.jsx`;
- `apps/web/.env.example`;
- `api/openapi.v1.yaml` (already the contract; unchanged this phase — no backend file was
  modified, confirmed by `scripts/verify.sh` re-run, still 146 passed + OpenAPI in sync).

**Dependencies:** Phase 10; the architecture analysis.

**Main risks**

- V2 assumptions rotting as GreenV moves;
- the process-boundary packaging not meeting latency / ops needs — flagged, not decided;
- the linear-reference work being larger than expected;
- (new, this pass) no stable key between the prediction API's 118 trechos and
  `rocada_polygons.geojson`'s 642 map polygons — named in `reports/integration.md` §11 rather than
  bridged with an invented correspondence.

## Phase 13 — Documentation for the banca and Motiva's engineers

**Objective.** Two audiences, one source of truth — an academic defence narrative and an
engineering handover — both honest about synthetic data.

**Tasks**

- [ ] `reports/banca.md` (or a slide outline): problem; data strategy (real vs synthetic); method;
      model comparison; results; uncertainty; limitations; migration to real data; operational
      value.
- [ ] `reports/engineering-handover.md`: how to run it; the schema; the API; the model artifacts;
      the verification loop; the integration plan; known gaps.
- [ ] `data/README.md` — the **real vs synthetic ledger**: every dataset and parameter tagged
      `measured` / `weather_real` / `synthetic` / `literature-derived`, with source.
- [ ] A persistent "SYNTHETIC" watermark on every applicable figure.
- [ ] A short banca FAQ: "why synthetic?", "how would real data change the result?", "what can you
      claim today?" — the answer is method + plumbing, no accuracy claim, mirroring
      `AUTOMATIC-HEIGHT.md`.
- [ ] `reports/glossary.md` — domain terms (`trecho`, `roçada`, `sentido`, `marco km`, nível 1–3).

**Completion criteria**

- both documents reviewed;
- the ledger accounts for every file under `data/`;
- no figure or claim presents synthetic output as measured;
- the banca doc has an explicit limitations section and an explicit migration section.

**Artifacts**

- `reports/banca.md`; `reports/engineering-handover.md`;
- `data/README.md` (the ledger); `reports/glossary.md`.

**Dependencies:** Phase 8; Phase 9; Phase 12.

**Main risks**

- the honest framing reading as weak to a reviewer expecting field results — pre-empt it in the
  narrative;
- the "deck" scope ballooning.

---

## Proposed structure of the future module (not created yet)

```
services/greenv-vegetation-prediction/
  README.md                     # outcome first; what runs, what is verified
  AGENTS.md                     # module conventions; the provenance rule; the boundaries
  pyproject.toml                # Python 3.12; pandas, scikit-learn, statsmodels, fastapi, pyarrow
  Makefile                      # weather | synth | train | evaluate | forecast | serve | verify
  api/
    openapi.v1.yaml             # the output contract — source of truth
  data/
    README.md                   # the REAL vs SYNTHETIC ledger
    real/weather/               # weather_real: raw cached responses + processed parquet
    synthetic/                  # synthetic: generated series + manifest (seed, params hash)
    reference/
      params.yaml               # literature-derived parameters, each with a citation
      datasets-catalogue.md
      contracts/                # frozen measurement-result-v1.json example + JSON Schema
  src/greenv_vegpred/
    config.py
    schema.sql                  # SQLite-valid, Postgres-portable DDL
    ingest/                     # envelope reader, backfill scan
    linref/                     # (lat,lon) -> (rodovia, sentido, km) -> trecho  (synthetic ref in V1)
    weather/                    # provider adapters (Open-Meteo, INMET), cache, GDD
    synth/                      # the synthetic generator
    features/                   # feature engineering + the training matrix
    models/
      baseline.py               # naive + mechanistic
      linear.py                 # OLS / Ridge / Lasso
      random_forest.py
      gradient_boosting.py
    evaluate/                   # metrics, backtesting, ablation, calibration
    forecast/                   # days-to-30cm + uncertainty interval + ranking
    api/                        # FastAPI app + local SQLite store
  notebooks/                    # EDA
  reports/                      # generated: literature review, EDA, model comparison, banca, handover
  scripts/
    fetch-weather.py
    generate-synthetic.py
    run-evaluation.py
    demo.http
    verify.sh                   # ruff + pytest + OpenAPI-in-sync
  tests/
```

## Definition of Done — the feature (V1)

- [ ] `services/greenv-vegetation-prediction/` exists, self-contained; `./scripts/verify.sh` is
      green from a clean checkout.
- [ ] Real weather for SP-021, ≥3 years weekly, cached and reproducible via one command; its
      retrieval date is recorded.
- [ ] A synthetic height dataset: deterministic from a seed; every row `data_source = 'synthetic'`;
      the generator documented with literature citations; an OOD holdout exists.
- [ ] `reports/eda.md` published.
- [ ] All four model families + baselines evaluated through one harness on held-out data.
- [ ] `reports/model-comparison.md`: MAE / RMSE / R² / days-error with confidence intervals, and a
      model selected **from the numbers**, with its rationale written.
- [ ] Days-to-30 cm + a calibrated uncertainty interval + h+7 / +14 / +30 produced for every
      SP-021 `trecho`; the ranking feed is reproducible.
- [ ] A self-contained `/v1` API serving forecasts, history and ranking; OpenAPI in sync; a
      synthetic-data banner present.
- [ ] Test suite green; a fast end-to-end test present.
- [ ] `reports/integration-plan.md` + `V2-CHECKLIST.md` — every V1→V2 gap named.
- [ ] `reports/banca.md` + `reports/engineering-handover.md` + the real-vs-synthetic ledger.
- [ ] No change to `apps/web`, `measurement/`, `infrastructure/`, `compose.yaml`, the GreenV main
      database, or any existing service.
- [ ] Every artifact and every payload carries `(rodovia, sentido, km, capturado_em)`.
- [ ] No synthetic value is presented anywhere as measured.

---

## How to present this to the banca

**The problem.** Motiva has to decide where to send mowing crews along the `faixa de domínio`.
Today the decision is reactive — a `trecho` is cut after it is already tall. If GreenV can predict
*when* each ≈500 m `trecho` crosses 30 cm (Nível 3 / critical), the operation becomes preventive:
crews are scheduled ahead of the threshold, and nearby `trechos` due at the same time are combined
into one `ordem de serviço`.

**The methodology.** A literature review of grass-growth dynamics (growing-degree-days, rainfall
response, post-cut regrowth, subtropical seasonality) yields parameters with recorded provenance.
Real weather for the SP-021 corridor (Open-Meteo, cross-checked against INMET) drives a mechanistic
synthetic generator built from those parameters. Against that dataset: naïve and mechanistic
baselines, then four model families (linear / multiple regression, Random Forest, Gradient
Boosting) behind one interface, evaluated by rolling-origin backtesting on MAE, RMSE, R² and
absolute error in days to 30 cm, with variable-ablation and interval-calibration checks. The
selected model is converted into the operational output — days-to-30 cm plus an uncertainty
interval — and served by a self-contained API in the schema GreenV will later use.

**The limitations.** No real vegetation-height reading was used to train or validate — the results
measure the synthetic generator, not the field. The growth parameters are largely from
temperate-climate agronomy, adjusted with wide ranges. `km` uses a synthetic linear reference
because GreenV has no `marco km` reference in this repository yet. No reading has been compared
against a tape measure — the same stance the automatic-height pipeline already takes
(`AUTOMATIC-HEIGHT.md`).

**Why synthetic data.** GreenV does not yet produce a weekly per-`trecho` height history: the
measurement worker exists, but nothing persists the series (the `segment.measured.v1` event has no
consumer), and weekly capture on SP-021 has not run long enough. To exercise the method, the
features and the API now, a dataset is needed now — synthetic, labelled as such throughout, and
driven by real weather so the seasonal and rainfall structure is genuine.

**How it migrates to real GreenV data.** The schema and the API are already the production ones.
When a consumer of `segment.measured.v1` (or the measurement worker itself) begins writing
`height_observation` rows with `data_source = 'measured'`, the pipeline re-trains with no contract
change; the `data_source` column keeps the two populations separate; a tape-measure validation set
enters later as a third population. The V2 checklist lists every wiring step — a `compose.yaml`
service, a queue binding, a module-owned Flyway migration — none of which touches the GreenV main
database.

**Operational value for Motiva.** A ranked "which `trechos` cross 30 cm first" list feeds the
route planner and combined `ordem de serviço` flow. The +7 / +14 / +30-day heights give a planning
window. The uncertainty interval keeps a crew from being sent a week too early or too late.
`roçada` events close the loop and expose recurrence ("same `trecho` cut three months ago"). The
whole thing is the basis for a service level such as "no `trecho` above 30 cm for more than N
days".
