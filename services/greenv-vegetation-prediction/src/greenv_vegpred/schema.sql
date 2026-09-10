-- =====================================================================
-- greenv-vegetation-prediction — canonical data schema (Phase 2)
-- Phase 2 artifact of docs/VEGETATION_PREDICTION_TASK.md
--
-- Target engine V1: SQLite (>= 3.37 recommended, for generated columns and
-- window functions). Written to migrate to PostgreSQL (>= 12) with only the
-- mechanical substitutions listed in reports/data-dictionary.md
-- ("SQLite -> PostgreSQL migration map").
--
-- CONVENTIONS (portable across both engines):
--   * timestamps / dates : TEXT holding ISO-8601 UTC
--                          ('YYYY-MM-DD' or 'YYYY-MM-DDTHH:MM:SSZ')
--   * booleans           : INTEGER with CHECK (col IN (0,1))
--   * enums              : TEXT with CHECK (col IN (...)) + a `provenance`
--                          lookup table for documentation/joins
--   * JSON payloads      : TEXT holding a JSON document (blockers, etc.)
--   * primary keys       : application-generated TEXT (UUIDv7 or a documented
--                          natural key). No AUTOINCREMENT / SERIAL, so seeds
--                          are deterministic and portable.
--   * generated columns  : GENERATED ALWAYS AS (...) STORED  (both engines)
--
-- The four GreenV fields (rodovia, sentido, km, capturado_em) are present on
-- every artifact: rodovia/sentido/km live on `trecho`; capturado_em is
-- `observed_at` on `height_observation` and is carried onto derived rows.
--
-- SQLite: run `PRAGMA foreign_keys = ON;` per connection (see data-dictionary).
-- =====================================================================


-- ---------------------------------------------------------------------
-- 0. Provenance taxonomy (lookup). Every "where it came from" column in
--    this schema is constrained to one of these codes by a CHECK; this
--    table exists for joins, documentation and a machine-readable ledger.
--    Phase 1 taxonomy: measured | weather_real | synthetic |
--                      literature_derived | hypothesis
-- ---------------------------------------------------------------------
CREATE TABLE provenance (
    provenance_code TEXT PRIMARY KEY
        CHECK (provenance_code IN
            ('measured', 'weather_real', 'synthetic',
             'literature_derived', 'hypothesis')),
    description     TEXT NOT NULL
);

INSERT INTO provenance (provenance_code, description) VALUES
    ('measured',
     'A real vegetation-height reading produced by the GreenV measurement pipeline. None expected in V1.'),
    ('weather_real',
     'Real meteorological data from a named provider (Open-Meteo / INMET / NASA POWER), with retrieved_at.'),
    ('synthetic',
     'A value produced by the Phase 4 generator. Never a measurement.'),
    ('literature_derived',
     'A parameter or method taken from a cited source (equation coefficient, base temperature, ...).'),
    ('hypothesis',
     'An engineering assumption with no reliable source. Must be swept and disclosed, never trusted.');


-- ---------------------------------------------------------------------
-- 1. trecho — the spatial reference. Mostly static metadata.
--    One row per ~500 m stretch of one rodovia in one sentido.
--    V1 geometry (km anchors, centroid) derives from a SYNTHETIC linear
--    reference (params.yaml: linear_reference, confidence: hypothesis) —
--    hence linear_reference_provenance defaults to 'hypothesis'.
-- ---------------------------------------------------------------------
CREATE TABLE trecho (
    trecho_id      TEXT PRIMARY KEY,          -- e.g. 'SP-021:norte:000000' (rodovia:sentido:km_start_m, zero-padded)
    rodovia        TEXT    NOT NULL,          -- e.g. 'SP-021'
    sentido        TEXT    NOT NULL
        CHECK (sentido IN ('norte', 'sul', 'leste', 'oeste', 'interno', 'externo', 'crescente', 'decrescente')),
    km_start       REAL    NOT NULL,          -- km, e.g. 12.0
    km_end         REAL    NOT NULL,          -- km, e.g. 12.5
    length_m       REAL    NOT NULL,          -- ~500
    centroid_lat   REAL,                      -- nullable until populated (Phase 3) from the linear reference
    centroid_lon   REAL,
    geom_wkt       TEXT,                      -- optional LINESTRING WKT of the centre line; PostGIS geometry on migration
    linear_reference_provenance TEXT NOT NULL DEFAULT 'hypothesis'
        REFERENCES provenance (provenance_code),
    created_at     TEXT    NOT NULL,          -- ISO-8601 UTC
    notes          TEXT,

    CHECK (km_end > km_start),
    CHECK (length_m > 0),
    UNIQUE (rodovia, sentido, km_start)
);

CREATE INDEX ix_trecho_road ON trecho (rodovia, sentido, km_start);


-- ---------------------------------------------------------------------
-- 2. height_observation — one vegetation-height observation for a trecho.
--    Weekly in the ideal case. Holds the RAW facts of one observation;
--    "altura anterior" and "crescimento semanal" are NOT stored here
--    (they are per-context derivations — see view v_trecho_weekly and
--    table feature_row).
--
--    data_source is the coarse guard that keeps synthetic and measured
--    apart; provenance is the fine-grained taxonomy. Both are mandatory.
-- ---------------------------------------------------------------------
CREATE TABLE height_observation (
    observation_id          TEXT PRIMARY KEY,           -- UUIDv7
    trecho_id               TEXT NOT NULL REFERENCES trecho (trecho_id),

    -- WHEN (capturado_em = observed_at)
    observed_at             TEXT NOT NULL,              -- ISO-8601 UTC timestamp
    observed_on             TEXT NOT NULL,              -- ISO date (derived, for weekly bucketing)
    iso_year                INTEGER NOT NULL,
    iso_week                INTEGER NOT NULL CHECK (iso_week BETWEEN 1 AND 53),

    -- WHAT (height signal)
    height_cm               REAL,                       -- current height; NULL allowed only with a height_status reason
    height_status           TEXT NOT NULL DEFAULT 'observed'
        CHECK (height_status IN ('observed', 'absent', 'censored')),
    height_p50_cm           REAL,
    height_p90_cm           REAL,
    height_p95_cm           REAL,
    h95_spread_m            REAL,                       -- between-view disagreement (uncertainty proxy from the measurement envelope)

    -- QUALITY / CONFIDENCE of the measurement
    cell_coverage           REAL CHECK (cell_coverage IS NULL OR (cell_coverage BETWEEN 0 AND 1)),
    measured_cell_count     INTEGER,
    abstained_cell_count    INTEGER,
    quality_confidence      TEXT CHECK (quality_confidence IS NULL OR quality_confidence IN ('low','medium','high')),

    -- OPERATIONAL STATUS (carried through from the measurement packet)
    operational_status      TEXT NOT NULL DEFAULT 'unknown'
        CHECK (operational_status IN ('ready', 'not-ready', 'unknown')),
    blockers                TEXT,                       -- JSON array of blocker codes, e.g. '["road-metadata-missing"]'

    -- CLASSIFICATION (business rule; generated, non-forgeable).
    -- not-ready or missing height => nivel 0 ("nao avaliado"), never nivel 1.
    nivel                   INTEGER GENERATED ALWAYS AS (
                                CASE
                                    WHEN operational_status = 'not-ready' THEN 0
                                    WHEN height_cm IS NULL THEN 0
                                    WHEN height_cm < 10 THEN 1
                                    WHEN height_cm <= 30 THEN 2
                                    ELSE 3
                                END
                            ) STORED,

    -- LINK back to the measurement pipeline (metadata; NOT features)
    source_generation       TEXT,                       -- sourceGeneration (sha256 of source mp4) — idempotency key
    run_id                  TEXT,                       -- measurement run id
    measurement_result_key  TEXT,                       -- object-storage key of measurement-result-v1.json

    -- PROVENANCE (mandatory)
    data_source             TEXT NOT NULL
        CHECK (data_source IN ('measured', 'synthetic')),
    provenance              TEXT NOT NULL
        REFERENCES provenance (provenance_code)
        CHECK (provenance IN ('measured', 'synthetic')),

    -- SYNTHETIC-only reproducibility (NULL for measured rows)
    generator_version       TEXT,
    generator_seed          INTEGER,
    generator_params_hash   TEXT,

    -- PIPELINE metadata (NOT features)
    ingested_at             TEXT NOT NULL,
    notes                   TEXT,

    -- data_source and provenance must agree
    CHECK ( (data_source = 'measured'  AND provenance = 'measured')
         OR (data_source = 'synthetic' AND provenance = 'synthetic') ),
    -- a synthetic row must be reproducible
    CHECK ( data_source <> 'synthetic'
            OR (generator_version IS NOT NULL AND generator_seed IS NOT NULL
                AND generator_params_hash IS NOT NULL) ),
    -- one observation per trecho per ISO week per data_source
    -- (a measured obs and a synthetic obs for the same week may coexist, on purpose)
    UNIQUE (trecho_id, iso_year, iso_week, data_source)
);

CREATE INDEX ix_obs_trecho_date  ON height_observation (trecho_id, observed_on);
CREATE INDEX ix_obs_week         ON height_observation (iso_year, iso_week);
CREATE INDEX ix_obs_source       ON height_observation (data_source);


-- ---------------------------------------------------------------------
-- 3. weather_observation — real (or, if ever needed, synthetic) daily
--    weather for a trecho or a reanalysis grid cell. Weekly aggregation
--    happens downstream (feature_row / views).
--
--    gdd_base_temp_c records WHICH base temperature produced gdd_c, so GDD
--    is reproducible and the Phase 1 controversy (Tb 10-17 degC) is
--    explicit in the data rather than hidden in code.
-- ---------------------------------------------------------------------
CREATE TABLE weather_observation (
    weather_id              TEXT PRIMARY KEY,           -- UUIDv7
    trecho_id               TEXT REFERENCES trecho (trecho_id),
    grid_cell_id            TEXT,                       -- reanalysis cell key, e.g. 'era5land:-23.55:-46.75'

    obs_date                TEXT NOT NULL,              -- ISO date
    iso_year                INTEGER NOT NULL,
    iso_week                INTEGER NOT NULL CHECK (iso_week BETWEEN 1 AND 53),

    temp_min_c              REAL,
    temp_mean_c             REAL,
    temp_max_c              REAL,
    precip_mm               REAL,                       -- total precipitation
    rain_mm                 REAL,                       -- liquid rain (Open-Meteo separates rain from precipitation)
    shortwave_radiation_mj_m2 REAL,                     -- daily shortwave radiation sum
    et0_mm                  REAL,                       -- FAO-56 reference evapotranspiration
    relative_humidity_pct   REAL,                       -- nullable (hourly-aggregated or from INMET)
    wind_speed_ms           REAL,

    gdd_c                   REAL,                       -- growing degree days for this day
    gdd_base_temp_c         REAL NOT NULL DEFAULT 15.0, -- Tb used for gdd_c (Phase 1: Villa Nova 2007; sweep 10-17)
    water_deficit_index     REAL,                       -- adopted deficit metric (e.g. rolling P - ET0); formula in code + data-dictionary

    -- PROVENANCE (mandatory)
    provider                TEXT NOT NULL
        CHECK (provider IN ('open-meteo', 'inmet', 'nasa-power', 'synthetic')),
    dataset                 TEXT,                       -- e.g. 'ERA5-Land', 'BDMEP', 'MERRA-2'
    data_source             TEXT NOT NULL
        CHECK (data_source IN ('weather_real', 'synthetic')),
    provenance              TEXT NOT NULL
        REFERENCES provenance (provenance_code)
        CHECK (provenance IN ('weather_real', 'synthetic')),
    license                 TEXT,                       -- e.g. 'CC-BY-4.0'
    retrieved_at            TEXT NOT NULL,              -- when fetched (reproducibility + metadata)
    raw_response_ref        TEXT,                       -- path to the cached raw response

    CHECK ( (data_source = 'weather_real' AND provenance = 'weather_real' AND provider <> 'synthetic')
         OR (data_source = 'synthetic'    AND provenance = 'synthetic') ),
    CHECK (trecho_id IS NOT NULL OR grid_cell_id IS NOT NULL),
    CHECK (gdd_base_temp_c BETWEEN 0 AND 30),
    UNIQUE (trecho_id, grid_cell_id, obs_date, provider)
);

CREATE INDEX ix_wx_trecho_date ON weather_observation (trecho_id, obs_date);
CREATE INDEX ix_wx_cell_date   ON weather_observation (grid_cell_id, obs_date);
CREATE INDEX ix_wx_week        ON weather_observation (iso_year, iso_week);


-- ---------------------------------------------------------------------
-- 4. rocada_event — a mowing event that resets the growth curve of a trecho.
--    days_since_rocada is NOT stored here (it is a per-(trecho, date)
--    derivation — see feature_row and v_trecho_weekly).
-- ---------------------------------------------------------------------
CREATE TABLE rocada_event (
    rocada_id               TEXT PRIMARY KEY,           -- UUIDv7
    trecho_id               TEXT NOT NULL REFERENCES trecho (trecho_id),
    occurred_on             TEXT NOT NULL,              -- ISO date
    height_before_cm        REAL,
    height_after_cm         REAL,                       -- residual cut height, when known
    equipment_type          TEXT,                       -- vocabulary from apps/web EQUIPMENT_TYPES, optional

    -- ORIGIN of the event record
    source                  TEXT NOT NULL
        CHECK (source IN ('ordem-de-servico', 'import', 'manual', 'synthetic')),
    ordem_de_servico_id     TEXT,                       -- id in a future OS system; no FK yet

    -- PROVENANCE (mandatory)
    data_source             TEXT NOT NULL
        CHECK (data_source IN ('measured', 'synthetic')),
    provenance              TEXT NOT NULL
        REFERENCES provenance (provenance_code)
        CHECK (provenance IN ('measured', 'synthetic')),

    recorded_at             TEXT NOT NULL,
    notes                   TEXT,

    CHECK ( (data_source = 'measured'  AND provenance = 'measured'  AND source <> 'synthetic')
         OR (data_source = 'synthetic' AND provenance = 'synthetic' AND source =  'synthetic') ),
    CHECK (height_after_cm IS NULL OR height_before_cm IS NULL OR height_after_cm <= height_before_cm),
    UNIQUE (trecho_id, occurred_on, source)
);

CREATE INDEX ix_rocada_trecho_date ON rocada_event (trecho_id, occurred_on);


-- ---------------------------------------------------------------------
-- 5. feature_row — the materialised training matrix. One row per
--    (trecho, ISO week, split_scheme, feature_spec_version), anchored at
--    as_of_date. EVERY column here is either:
--      * a FEATURE   — strictly known at as_of_date (past or present), OR
--      * a TARGET    — strictly after as_of_date (target_* prefix), OR
--      * SPLIT/META  — not a model input.
--
--    The feature build MUST window every rolling aggregate to
--    (as_of_date - N days, as_of_date]. See data-dictionary "leakage".
-- ---------------------------------------------------------------------
CREATE TABLE feature_row (
    feature_row_id              TEXT PRIMARY KEY,        -- UUIDv7
    trecho_id                   TEXT NOT NULL REFERENCES trecho (trecho_id),
    iso_year                    INTEGER NOT NULL,
    iso_week                    INTEGER NOT NULL CHECK (iso_week BETWEEN 1 AND 53),
    as_of_date                  TEXT NOT NULL,           -- the temporal anchor (ISO date). Nothing after this may inform a feature.
    data_source                 TEXT NOT NULL
        CHECK (data_source IN ('measured', 'synthetic', 'mixed')),

    -- ----- FEATURES: height / growth history (past + present only) -----
    height_cm                   REAL,                    -- current
    height_prev_cm              REAL,                    -- one ISO week back
    height_lag2_cm              REAL,                    -- two ISO weeks back
    weekly_growth_cm            REAL,                    -- height_cm - height_prev_cm
    weeks_observed_last_8       INTEGER,                 -- data-density feature

    -- ----- FEATURES: weather, windowed to (as_of_date - N, as_of_date] -
    gdd_week_c                  REAL,                    -- sum of daily GDD, past 7 days
    gdd_cum_since_rocada_c      REAL,
    tmin_week_c                 REAL,
    tmean_week_c                REAL,
    tmax_week_c                 REAL,
    rain_7d_mm                  REAL,
    rain_14d_mm                 REAL,
    rain_30d_mm                 REAL,
    shortwave_7d_mj_m2          REAL,
    et0_7d_mm                   REAL,
    water_deficit_30d           REAL,
    relative_humidity_7d_pct    REAL,                    -- CANDIDATE ONLY (Phase 1: no direct effect; provenance hypothesis)

    -- ----- FEATURES: maintenance / seasonality / position --------------
    days_since_rocada           INTEGER,                 -- counts only rocada_events with occurred_on <= as_of_date; NULL if never
    week_of_year                INTEGER,
    season_sin                  REAL,
    season_cos                  REAL,
    dry_season_flag             INTEGER CHECK (dry_season_flag IS NULL OR dry_season_flag IN (0,1)),
    vegetation_type             TEXT,                    -- optional; V1 a per-trecho proxy label
    km_mid                      REAL,
    centroid_lat                REAL,
    centroid_lon                REAL,
    operational_status          TEXT,                    -- status of the SOURCE observation (management context feature)

    -- ----- TARGETS: strictly future relative to as_of_date -------------
    target_height_plus_7d_cm    REAL,                    -- NULL if the horizon week was not observed
    target_height_plus_14d_cm   REAL,
    target_height_plus_30d_cm   REAL,
    target_days_until_30cm      REAL,                     -- forward-simulated from as_of_date+1; NULL if censored
    target_days_until_30cm_censored INTEGER NOT NULL DEFAULT 0
        CHECK (target_days_until_30cm_censored IN (0,1)),
    target_days_until_30cm_horizon_days INTEGER,          -- the max horizon used for censoring, e.g. 120

    -- ----- SPLIT / METADATA (never model inputs) ----------------------
    split                       TEXT NOT NULL
        CHECK (split IN ('train', 'validation', 'test', 'excluded')),
    split_scheme                TEXT NOT NULL,           -- e.g. 'temporal-cut-2025' or 'blocked-by-trecho'
    feature_spec_version        TEXT NOT NULL,           -- which feature definition produced this row
    source_observation_id       TEXT REFERENCES height_observation (observation_id),
    generator_params_hash       TEXT,                    -- when data_source involves synthetic
    built_at                    TEXT NOT NULL,

    UNIQUE (trecho_id, as_of_date, split_scheme, feature_spec_version)
);

CREATE INDEX ix_fr_trecho_asof ON feature_row (trecho_id, as_of_date);
CREATE INDEX ix_fr_split       ON feature_row (split, split_scheme);


-- ---------------------------------------------------------------------
-- 6. prediction — model output for one trecho at one as_of_date.
--    input_data_source + dataset_is_synthetic make it impossible to
--    mistake a prediction built on synthetic input for one on real data.
-- ---------------------------------------------------------------------
CREATE TABLE prediction (
    prediction_id             TEXT PRIMARY KEY,          -- UUIDv7
    trecho_id                 TEXT NOT NULL REFERENCES trecho (trecho_id),
    as_of_date                TEXT NOT NULL,
    model_kind                TEXT NOT NULL
        CHECK (model_kind IN
            ('naive_persistence', 'naive_last_growth', 'naive_seasonal',
             'mechanistic', 'linear', 'ridge', 'lasso',
             'random_forest', 'hist_gradient_boosting', 'other')),
    model_version             TEXT NOT NULL,

    h_plus_7_cm               REAL,
    h_plus_14_cm              REAL,
    h_plus_30_cm              REAL,
    h_plus_7_low_cm           REAL,
    h_plus_7_high_cm          REAL,
    h_plus_14_low_cm          REAL,
    h_plus_14_high_cm         REAL,
    h_plus_30_low_cm          REAL,
    h_plus_30_high_cm         REAL,

    days_to_30cm              REAL,
    days_to_30cm_low          REAL,
    days_to_30cm_high         REAL,
    days_to_30cm_censored     INTEGER NOT NULL DEFAULT 0
        CHECK (days_to_30cm_censored IN (0,1)),
    days_to_30cm_horizon_days INTEGER,

    interval_method           TEXT
        CHECK (interval_method IS NULL OR interval_method IN
            ('conformal', 'quantile', 'bootstrap', 'residual')),
    interval_nominal_coverage REAL CHECK (interval_nominal_coverage IS NULL OR (interval_nominal_coverage BETWEEN 0 AND 1)),
    confidence                TEXT CHECK (confidence IS NULL OR confidence IN ('low', 'medium', 'high')),

    -- carried through from the source observation
    operational_status        TEXT,
    blockers                  TEXT,                      -- JSON

    -- guardrails against mistaking synthetic-based output for real
    source_observation_id     TEXT REFERENCES height_observation (observation_id),
    input_data_source         TEXT NOT NULL
        CHECK (input_data_source IN ('measured', 'synthetic', 'mixed')),
    dataset_is_synthetic      INTEGER NOT NULL
        CHECK (dataset_is_synthetic IN (0,1)),

    created_at                TEXT NOT NULL,

    -- a not-ready source caps confidence at 'low'
    CHECK (operational_status <> 'not-ready' OR confidence IS NULL OR confidence = 'low'),
    UNIQUE (trecho_id, as_of_date, model_kind, model_version)
);

CREATE INDEX ix_pred_trecho_asof ON prediction (trecho_id, as_of_date);
CREATE INDEX ix_pred_model        ON prediction (model_kind, model_version);


-- ---------------------------------------------------------------------
-- 7. dataset_manifest — machine-readable ledger of every generator run,
--    weather pull and feature build. Feeds the Phase 13 REAL vs SYNTHETIC
--    ledger. is_synthetic is explicit.
-- ---------------------------------------------------------------------
CREATE TABLE dataset_manifest (
    manifest_id     TEXT PRIMARY KEY,                    -- UUIDv7
    kind            TEXT NOT NULL
        CHECK (kind IN ('synthetic_heights', 'weather_pull', 'feature_build',
                        'rocada_schedule', 'trecho_seed', 'model_training', 'other')),
    created_at      TEXT NOT NULL,
    params_hash     TEXT,
    seed            INTEGER,
    row_count       INTEGER,
    is_synthetic    INTEGER NOT NULL CHECK (is_synthetic IN (0,1)),
    source_summary  TEXT,                                -- free text: providers, date ranges, params.yaml version, code version
    notes           TEXT
);


-- ---------------------------------------------------------------------
-- 8. Convenience views (portable; window functions require SQLite >= 3.25).
--    These compute "altura anterior" / "crescimento semanal" / latest
--    reading without denormalising height_observation.
-- ---------------------------------------------------------------------

-- Per-trecho weekly series with lag-1 height and weekly growth,
-- partitioned by data_source so measured and synthetic never mix in a lag.
CREATE VIEW v_trecho_weekly AS
SELECT
    o.trecho_id,
    o.data_source,
    o.iso_year,
    o.iso_week,
    o.observed_on,
    o.observed_at,
    o.height_cm,
    o.nivel,
    o.operational_status,
    LAG(o.height_cm) OVER w  AS height_prev_cm,
    o.height_cm - LAG(o.height_cm) OVER w AS weekly_growth_cm,
    julianday(o.observed_on) - julianday(LAG(o.observed_on) OVER w) AS days_since_prev_obs
FROM height_observation o
WINDOW w AS (
    PARTITION BY o.trecho_id, o.data_source
    ORDER BY o.iso_year, o.iso_week
);
-- NOTE: julianday() is SQLite-specific. On PostgreSQL replace with
--   (observed_on::date - lag(observed_on)::date OVER w).

-- Latest observation per trecho per data_source (for the read API).
CREATE VIEW v_trecho_latest AS
SELECT o.*
FROM height_observation o
JOIN (
    SELECT trecho_id, data_source, MAX(observed_at) AS max_observed_at
    FROM height_observation
    GROUP BY trecho_id, data_source
) last
  ON last.trecho_id = o.trecho_id
 AND last.data_source = o.data_source
 AND last.max_observed_at = o.observed_at;

-- =====================================================================
-- End of schema. Seed the trecho grid for SP-021 with seeds/trecho_sp021.sql
-- (structural km partition only; centroid/geom are populated in Phase 3
-- from the hypothesis-flagged linear reference).
-- =====================================================================
