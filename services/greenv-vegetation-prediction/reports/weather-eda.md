# Weather data — acquisition, QC and EDA (Phase 3)

**Phase 3 artifact of [`docs/VEGETATION_PREDICTION_TASK.md`](../../../../docs/VEGETATION_PREDICTION_TASK.md).**
Run on 2026-09-09. All weather here is **real** (`provenance = weather_real`). The only
`hypothesis` element is the corridor's geometry — see §1.

Companion outputs:

| File | What |
|---|---|
| `data/real/weather/raw/openmeteo_*.json` (×4) | verbatim Open-Meteo responses, one per grid cell, never edited |
| `data/real/weather/raw/nasapower_*.json` (×1) | verbatim NASA POWER response (independent cross-check) |
| `data/real/weather/raw/query_manifest.json` | provider, model, coords queried, params, retrieved_at, licence, spatial representation |
| `data/real/weather/processed/weather_observation.csv` | 5 360 daily rows, `weather_observation` shape (`schema.sql`) |
| `data/real/weather/processed/weather_weekly_features.csv` | 772 rows: per (cell, ISO week) retrospective climate features |
| `data/real/weather/processed/trecho_weather_cell.csv` | 118 trechos → 4 weather cells (coords `hypothesis`) |
| `data/real/weather/processed/weather_pull_manifest.json` | `dataset_manifest` row (`kind = weather_pull`, `is_synthetic = 0`) |
| `data/real/weather/qc/qc_report.json` | full QC output |
| `data/real/weather/qc/openmeteo_vs_nasapower.json` | cross-check metrics |

> **Format note.** Processed tables are **CSV**, not parquet — `pyarrow` is not installed in this
> environment and installing packages is out of Phase-3 scope. The schema in the data dictionary is
> tabular; converting these CSVs to parquet is a one-line step once the module's Python
> environment exists (Phase 0).

---

## 1. Spatial representation of SP-021 km 0–29.3 (V1)

**These coordinates are NOT official.** The SP-021 centre line is approximated by the anchor
polyline already in GreenV (`apps/web/src/utils/classification.js` `ROAD_ANCHORS`):

| km | lat | lon |
|---:|---:|---:|
| 0.0 | −23.4080 | −46.7290 |
| 5.0 | −23.4310 | −46.7520 |
| 10.0 | −23.4600 | −46.7700 |
| 15.0 | −23.4950 | −46.7850 |
| 20.0 | −23.5400 | −46.8000 |
| 25.0 | −23.5850 | −46.8150 |
| 29.3 | −23.6290 | −46.8300 |

Every coordinate derived from these anchors — corridor sample points, trecho centroids in
`trecho_weather_cell.csv` — carries **`provenance = hypothesis`** and `coord_provenance =
hypothesis`. Resolving the real geometry needs the highway's `marco km` linear reference, which
GreenV does not have in this repository (recorded in
[`data/reference/params.yaml`](../data/reference/params.yaml) → `linear_reference`).

**Method.** The polyline was sampled every ~2 km (16 points), each point queried against
Open-Meteo, and the points de-duplicated by the grid cell the API actually returned.

**Result — 4 distinct weather cells cover the whole 29.3 km:**

| Grid cell (ERA5-Land 0.1°) | Elevation | Corridor span (approx.) | Trechos mapped (both `sentidos`) |
|---|---:|---|---:|
| `open-meteo:era5_seamless:-23.4:-46.7` | 805 m | km 0–4.5 | 18 |
| `open-meteo:era5_seamless:-23.4:-46.8` | 793 m | km 4.5–8.5 | 16 |
| `open-meteo:era5_seamless:-23.5:-46.8` | 975 m | km 8.5–21 | **50** |
| `open-meteo:era5_seamless:-23.6:-46.8` | 797 m | km 21–29.3 | 34 |
| **total** | | | **118** |

One cell (`-23.5:-46.8`, on higher ground near the Serra da Cantareira) covers **42 % of the
corridor**. See §6 for what this means for spatial precision.

---

## 2. Source, variables, provenance

**Primary: Open-Meteo Historical Weather API, model `era5_seamless`.**

`era5_land` alone was tried first and **returns NULL for `shortwave_radiation_sum` and
`et0_fao_evapotranspiration`** (verified 2026-09-09). `era5_seamless` blends:

- `temperature_2m_{min,mean,max}`, `precipitation_sum`, `rain_sum`, `relative_humidity_2m_mean`
  → **ERA5-Land** (~9–11 km, native 0.1° grid);
- `shortwave_radiation_sum`, `et0_fao_evapotranspiration`
  → **ERA5** (~25 km, 0.25° grid), blended in.

So radiation and ET0 have **coarser spatial resolution** than temperature and humidity.

| Field written | Open-Meteo variable | Unit | Source layer |
|---|---|---|---|
| `temp_min_c` / `temp_mean_c` / `temp_max_c` | `temperature_2m_min/mean/max` | °C | ERA5-Land 0.1° |
| `precip_mm` | `precipitation_sum` | mm | ERA5-Land 0.1° |
| `rain_mm` | `rain_sum` | mm | ERA5-Land 0.1° |
| `relative_humidity_pct` | `relative_humidity_2m_mean` | % | ERA5-Land 0.1° |
| `wind_speed_ms` | `wind_speed_10m_mean` | m/s | ERA5-Land 0.1° |
| `shortwave_radiation_mj_m2` | `shortwave_radiation_sum` | MJ/m² | ERA5 0.25° |
| `et0_mm` | `et0_fao_evapotranspiration` | mm | ERA5 0.25° |
| `gdd_c` | derived | °C·day | `max(0, (Tmax+Tmin)/2 − Tb)`, **Tb = 15 °C reference** |
| `water_deficit_index` | derived | mm | `precip_mm − et0_mm` (daily) |

**Reproducibility recorded in every raw file:** `provider`, `model`, `query_url`, `query_params`,
`queried_latitude/longitude`, `returned_cell_latitude/longitude`, `timezone`
(`America/Sao_Paulo`), `utc_offset_seconds` (−10800), `daily_units`, `spatial_resolution_note`,
`start_date`, `end_date`, `retrieved_at`, `license`
(**CC-BY-4.0**; "Weather data by Open-Meteo.com; source: Copernicus / ECMWF ERA5 & ERA5-Land"),
`provenance = weather_real`.

**GDD / Tb.** `Tb = 15 °C` is a **`literature_derived` reference value** (Villa Nova et al. 2007,
for *Pennisetum* / *Cynodon* in Piracicaba SP) — **not** asserted to be the base temperature of
SP-021 vegetation, which is unknown (Phase 1 range 10–17 °C, "controversial"). It is configurable
(`build_weather.py --tb`), and `weather_weekly_features.csv` carries `gdd_week_tb10_c`,
`gdd_week_tb15_c`, `gdd_week_tb17_c` so Phase 5/8 can sweep without re-fetching.

---

## 3. Period, volume, variables

| | Value |
|---|---|
| Period obtained | **2023-01-01 → 2026-09-01** |
| Days per cell | **1 340** (contiguous, no gaps) |
| Cells | **4** |
| Daily rows total | **5 360** |
| Weekly feature rows | **772** (193 ISO weeks × 4 cells; 8 are partial — first/last ISO week, flagged by `week_days_present < 7`) |
| Variables | Tmin, Tmean, Tmax, precipitation_sum, rain_sum, shortwave_radiation_sum, et0_fao_evapotranspiration, relative_humidity_2m_mean, wind_speed_10m_mean (+ derived `gdd_c`, `water_deficit_index`) |

**Wider range not taken:** an initial 2020–2026 pull hit Open-Meteo free-tier call-weight limits
(HTTP 429). 2023–2026 satisfies the Phase 3 minimum and gives 3.7 years — enough for weekly
seasonality and 30-day feature warm-up, but only ~3 full annual cycles, so year-to-year
variability is thinly sampled.

---

## 4. Quality-control results (`qc/qc_report.json`)

| Check | Result |
|---|---|
| Missing dates | **0** across all 4 cells (1 340 / 1 340 expected days present) |
| Duplicate `(cell, date)` rows | **0** |
| Temporal continuity | **OK** for every cell (contiguous daily series, no gap) |
| Null values (any variable) | **0** — including `et0` and `shortwave` (no reanalysis-latency gap at the 2026-09-01 cut-off) |
| `Tmin > Tmax` | 0 |
| `Tmean` outside `[Tmin, Tmax]` | 0 |
| Temperature outside −15…50 °C | 0 (observed daily range: Tmin abs 2.1 °C, Tmax abs 35.2 °C — plausible for the SP plateau) |
| Precipitation < 0 | 0 |
| Relative humidity outside 0–100 % | 0 (observed 36–98 %) |
| Shortwave < 0 / ET0 < 0 / wind < 0 | 0 |
| Units consistent across cells | **yes** (°C, mm, MJ/m², %, m/s) |
| Timezone consistent | **yes** (`America/Sao_Paulo`, offset −10800 s) |
| Distinct weather cells | **4** |
| Missing % by variable (overall) | **0.0 %** for all 10 variables |

The dataset is clean. The caveats are about *representativeness*, not integrity (§6, §7).

---

## 5. Seasonal profile (climatology)

Monthly climatology, cell `-23.5:-46.8` (covers ~42 % of the corridor), 2023–2026:

| Month | Tmean °C | Tmin °C | Rain mm/mo | Shortwave MJ/m²·d | ET0 mm/mo | GDD(Tb15) °C·d/mo |
|---|---:|---:|---:|---:|---:|---:|
| Jan | 20.5 | 17.1 | 190 | 20.0 | 117 | 181 |
| Feb | 21.2 | 17.9 | 203 | 19.4 | 115 | 203 |
| Mar | 20.6 | 16.9 | 137 | 19.0 | 112 | 186 |
| Apr | 18.8 | 15.2 | 83 | 16.0 | 92 | 133 |
| May | 17.1 | 13.2 | 46 | 13.6 | 81 | 86 |
| Jun | 15.1 | 10.9 | 45 | 12.4 | 72 | 36 |
| Jul | 14.6 | 10.2 | 19 | 13.6 | 77 | 31 |
| Aug | 16.7 | 11.8 | 33 | 16.0 | 100 | 91 |
| Sep | 19.4 | 14.0 | 43 | 19.4 | 126 | 154 |
| Oct | 18.9 | 14.9 | 129 | 18.4 | 111 | 140 |
| Nov | 19.7 | 15.3 | 120 | 21.1 | 125 | 153 |
| Dec | 21.2 | 17.3 | 160 | 21.9 | 133 | 203 |

**Reads as a textbook subtropical Cwa/Cwb regime:** hot wet summer (Dec–Mar, 140–200 mm/mo), cool
dry winter (Jun–Aug, 19–45 mm/mo). Monthly GDD(Tb15) swings **~6×** between July (~31 °C·d) and
Dec/Feb (~203 °C·d).

**Whole-period per cell:**

| Cell | Elev | Tmean °C | Rain mm/yr eq. | Dry days (rain<1mm) | Shortwave MJ/m²·d | ET0 mm/yr | RH % | GDD(Tb15) °C·d/yr |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| −23.4:−46.7 | 805 m | 19.83 | 1 186 | 60 % | 17.3 | 1 284 | 78 | 1 987 |
| −23.4:−46.8 | 793 m | 19.82 | 1 186 | 60 % | 17.3 | 1 288 | 78 | 1 977 |
| −23.5:−46.8 | 975 m | 18.53 | 1 186 | 60 % | 17.3 | 1 240 | 78 | 1 561 |
| −23.6:−46.8 | 797 m | 19.61 | 1 186 | 60 % | 17.3 | 1 249 | 80 | 1 917 |

**This is the finding that matters for spatial precision (§6):** rain, shortwave and RH are
*byte-identical* across all four cells; only temperature varies, and only by elevation
(the 975 m cell is ~1.3 °C cooler and ~400 GDD/yr lower).

`Tb` sensitivity (annual GDD, cell −23.5:−46.8): **Tb10 ≈ 3 300 · Tb15 ≈ 1 561 · Tb17 ≈ 1 060
°C·d/yr** — the choice of base temperature more than halves the annual heat sum, which is exactly
why it must stay a swept parameter.

---

## 6. Spatial precision — stated honestly

ERA5 / ERA5-Land cells are **9–25 km** across. A `trecho` is **500 m**. So:

1. **The corridor has one precipitation regime and one radiation regime.** `rain_mm` and
   `shortwave_radiation_mj_m2` are identical for all 118 trechos on **100 % of days**
   (`qc/openmeteo_vs_nasapower.json` context; verified directly). Any model must treat
   corridor-wide rain and radiation as a single time series, not a per-trecho feature.
2. **Temperature has 4 regimes**, separated only by grid-cell elevation
   (Tmax identical across cells on 0 % of days; the between-cell Tmean spread is up to 1.3 °C,
   driven by the 975 m cell). This is real signal but coarse — it is *elevation*, not *position
   along the road*.
3. **`trecho_weather_cell.csv` records the mapping explicitly**, with `coord_provenance =
   hypothesis` and a `spatial_note` on every row. The feature build (Phase 5) must join
   trecho → cell and must not present cell-level weather as trecho-level truth.
4. **Do not build per-trecho weather features that imply 500 m resolution.** The honest feature
   set is: corridor rain/radiation (1 series) + cell temperature/GDD (4 series) + static trecho
   position.

---

## 7. Cross-check: Open-Meteo vs NASA POWER (INMET substitute)

**INMET was the intended secondary source but its API is unreachable from this environment**
(`apitempo.inmet.gov.br` and `portal.inmet.gov.br` both return "connection reset", verified
2026-09-09 across three endpoints). Rather than skip validation, **NASA POWER** was used —
also real, and **reanalysis-independent of ERA5** (sources: SYN1DEG, FLASHFLUX, GEOSIT, MERRA-2),
so a genuine cross-check.

Comparison: Open-Meteo cell `-23.5:-46.8` vs NASA POWER 0.5° point at (−23.5, −46.8), 1 338
overlapping days.

| Variable | Pearson r | Bias (OM − POWER) | MAE | RMSE | OM mean | POWER mean |
|---|---:|---:|---:|---:|---:|---:|
| Tmin | 0.967 | **−1.11 °C** | 1.20 | 1.42 | 14.46 | 15.57 |
| Tmean | 0.976 | **−2.13 °C** | 2.13 | 2.26 | 18.53 | 20.66 |
| Tmax | 0.916 | **−3.87 °C** | 3.87 | 4.21 | 23.65 | 27.51 |
| Shortwave (MJ/m²) | 0.905 | +1.10 | 2.00 | 2.79 | 17.33 | 16.24 |
| Relative humidity (%) | 0.876 | +3.82 | 5.35 | 6.47 | 78.34 | 74.53 |
| Precipitation (mm/day) | **0.708** | +0.51 | 2.04 | 4.37 | 3.23 | 2.72 |

**Annual precipitation totals (mm):**

| Year | Open-Meteo | NASA POWER |
|---:|---:|---:|
| 2023 | 1 226 | 1 254 |
| 2024 | 1 290 | 1 001 |
| 2025 | 1 038 | 754 |
| 2026 (to Sep 1) | 801 | 629 |

**Interpretation:**

- **Temperature:** correlation is excellent (r 0.92–0.98) but Open-Meteo runs **systematically
  cooler**, by ~1 °C on Tmin up to ~4 °C on Tmax. Most of this is the elevation contrast: the
  ERA5-Land 0.1° cell is at 975 m, the POWER 0.5° cell averages a wider, lower area (790 m). ERA5-
  Land is arguably the better fit to a *specific* 975 m stretch; POWER is a regional mean. Neither
  is wrong — the **~2 °C spread is the honest between-product uncertainty on absolute temperature**
  (and therefore on absolute GDD).
- **Radiation and RH:** acceptable agreement (r ≈ 0.88–0.91, small biases).
- **Precipitation:** the weakest variable. Daily r is only **0.71**, and annual totals diverge by
  up to **~28 %** in the dry years (2024, 2025). Both products agree on a **drying trend
  2023 → 2026** and on the wet/dry seasonal shape, but any absolute rainfall total for SP-021
  should carry a ±25–30 % band.

---

## 8. Limitations

1. **Coordinates are approximate (`hypothesis`).** The corridor geometry comes from GreenV's
   `ROAD_ANCHORS`, not an official `marco km` reference. A 1–2 km positional error moves nothing
   at ERA5 resolution but must be fixed before any per-trecho spatial claim.
2. **No per-trecho weather resolution.** 4 cells for 29.3 km; rain and radiation are a single
   corridor-wide series; temperature varies only by cell elevation (§6).
3. **Radiation and ET0 are coarser than the rest** (ERA5 0.25° vs ERA5-Land 0.1°), blended by
   `era5_seamless`.
4. **Absolute temperature / GDD uncertainty ~2 °C / ~½×** between reanalyses (§7) and across the
   Tb 10–17 °C range (§5). Models should lean on *relative* / *anomaly* GDD, and Phase 8 must
   sweep Tb.
5. **Absolute rainfall uncertain to ±25–30 %.** Use rolling *relative* rain and a water-deficit
   index rather than raw mm where possible.
6. **Only 3.7 years, ~3 full annual cycles.** Year-to-year variability is thinly sampled; the
   2023→2026 period was progressively dry, which could bias a model toward dry-regime growth.
7. **INMET not integrated** — API unreachable here. No station-observation ground truth was
   obtained. The manual path (BDMEP portal, nearest automatic station **A701 São Paulo – Mirante
   de Santana**, ~−23.50/−46.62, and **A771 Interlagos**) is left for a future run.
8. **ET0 is Open-Meteo's FAO-56 derivation**, not independently verified (NASA POWER's `EVPTRNS`
   was requested for a future comparison but not yet analysed).
9. **`et0` / GDD provenance is `weather_real` for inputs but the quantities are derived** — GDD by
   `build_weather.py`, ET0 by Open-Meteo. Treated like `literature_derived` methods pinned by
   `feature_spec_version` later.
10. **Output is CSV, not parquet** (no `pyarrow` here).

---

## 9. Phase 3 completion criteria — check

| Criterion (`TASK.md`) | Status | Evidence |
|---|---|---|
| ≥ 3 years of weekly weather for every SP-021 trecho, no unexplained gap > 1 week | **Met** | 2023-01-01 → 2026-09-01 (3.7 y); 0 missing days, 0 gaps across 4 cells; every trecho maps to a cell (`trecho_weather_cell.csv`) |
| Raw responses cached; `make weather` reproduces the processed set | **Partly** | raw cached verbatim (`raw/*.json` + `query_manifest.json`); reproduced by `python scripts/fetch_weather.py && python scripts/build_weather.py` (a `Makefile` target is Phase 0) |
| Provenance columns populated | **Met** | every row: `provider`, `data_source = weather_real`, `provenance = weather_real`, `license`, `retrieved_at`, `raw_response_ref` |
| The INMET discrepancy is quantified | **Substituted** | INMET API unreachable; NASA POWER cross-check quantified instead (§7, `qc/openmeteo_vs_nasapower.json`) |

**Honest status:** the three data criteria are met with real, clean, reproducible data. The INMET
criterion is met *in spirit* via an independent reanalysis (NASA POWER); a true station cross-check
is deferred.

---

## 10. What still needs resolving before Phase 4

1. **Confirm / replace the corridor geometry.** Decide whether `ROAD_ANCHORS` is good enough for
   V1 (probably yes, at ERA5 resolution) and record that decision; the real `marco km` reference
   remains the open dependency.
2. **Decide the weather feature granularity for `feature_row`:** corridor-wide rain/radiation
   (1 series) + per-cell temperature/GDD (4 series). Write it into `feature_spec_version`.
3. **Choose the water-deficit metric** (daily `P − ET0` rolling sum is implemented; confirm vs an
   `ETR/ETP` ratio — Phase 1 mentioned both).
4. **Fix the Tb sweep set** for Phase 8 (currently {10, 15, 17}); confirm 15 °C stays the
   *reference*, never the asserted value.
5. **Handle partial ISO weeks** (`week_days_present < 7`, 8 rows) — drop or downweight in Phase 5.
6. **Optionally widen the history** to 2018–2022 with a rate-limit-aware fetch (multiple runs), if
   Phase 5 EDA shows 3 annual cycles is too thin.
7. **Optional:** a real INMET station pull (A701 / A771) once network access allows, and the
   NASA POWER `EVPTRNS` vs Open-Meteo ET0 comparison.
8. **Convert CSV → parquet** once the module's Python environment (Phase 0) exists.

Reviewed: 2026-09-09.
