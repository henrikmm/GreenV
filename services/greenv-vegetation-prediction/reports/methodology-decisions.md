# Methodology decisions — greenv-vegetation-prediction

A running log of methodological choices that later phases must respect. Lightweight ADR style:
each decision has context, the decision, rationale, status, and where it is enforced. Companion to
[`params.yaml`](../data/reference/params.yaml) (parameters), [`data-dictionary.md`](data-dictionary.md)
(schema) and [`weather-eda.md`](weather-eda.md) (the Phase 3 evidence these rest on).

**Nothing here is a fact about SP-021 vegetation. These are engineering choices for the V1
prototype, frozen so Phase 4+ does not re-litigate them.**

The decisions below define the weather feature spec **`weather-v1`**. Every `feature_row` and every
feature-build `dataset_manifest` produced under these rules carries `feature_spec_version =
"weather-v1"`.

| ID | Topic | Status | Frozen on |
|---|---|---|---|
| DEC-001 | Climate feature granularity | **frozen for V1** | 2026-09-09 |
| DEC-002 | Water-deficit metric | **frozen for V1** | 2026-09-09 |
| DEC-003 | GDD base temperature | **frozen for V1** | 2026-09-09 |
| DEC-004 | Partial ISO weeks | **frozen for V1** | 2026-09-09 |
| DEC-005 | Corridor geometry | **frozen for V1** | 2026-09-09 |
| DEC-006 | INMET validation | **deferred, not a blocker** | 2026-09-09 |
| DEC-007 | Weather history extent | **frozen for V1** | 2026-09-09 |

---

## DEC-001 — Climate feature granularity

**Context.** Phase 3 (`weather-eda.md` §5–§6): across the 4 ERA5-Land cells covering SP-021,
`rain_sum`, `precipitation_sum`, `shortwave_radiation_sum` and `relative_humidity_2m_mean` are
**byte-identical on 100 % of days**. Only temperature differs, and only by grid-cell elevation
(the 975 m cell is ~1.3 °C cooler, ~400 GDD/yr lower; `Tmax` identical across cells on 0 % of
days).

**Decision.**

1. **Corridor-common series (one time series, shared by all 118 trechos):**
   `rain_mm`, `precip_mm`, `shortwave_radiation_mj_m2`, `relative_humidity_pct`, `et0_mm`, and
   every window derived from them — `rain_7d/14d/30d`, `precip_7d/30d`, `shortwave_7d`,
   `et0_7d/30d`, `water_deficit_*` (DEC-002).
   Canonical source cell: **`open-meteo:era5_seamless:-23.5:-46.8`** (covers ~42 % of the corridor,
   the geographic centre). The feature build asserts the other three cells match on these
   variables; if a future wider fetch breaks the identity, this decision is revisited.
2. **Per-weather-cell series (4 series; each trecho takes its mapped cell via
   `trecho_weather_cell.csv`):** `temp_min_c`, `temp_mean_c`, `temp_max_c`, `tmin_week`,
   `tmean_week`, `tmax_week`, `tmin_7d`, `tmean_7d`, and all `gdd_*` (GDD derives from Tmin/Tmax).

3. **This is explicitly not 500 m resolution.** The corridor has **one rain/radiation regime and
   four temperature regimes** over 29.3 km. `feature_row` must join trecho → cell for temperature
   / GDD and trecho → corridor for everything else, and no per-trecho weather feature may imply
   sub-cell precision.

**Rationale.**
*Scientific:* presenting rain or radiation per trecho would be fabricated resolution — the data
carries none. Temperature's elevation spread is real (if coarse) signal and worth keeping.
*Operational:* one rain series is simpler, smaller, and matches how the corridor actually behaves
hydrologically at this scale.

**Enforced in.** `build_weather.py` (identity assertion), the Phase 5 `feature_row` build,
`feature_spec_version = "weather-v1"`.

---

## DEC-002 — Water-deficit metric for V1

**Context.** Phase 1 (`literature-review.md` §4.1): drought reduces C4 forage growth ~35 %; the
effect is modelled as a multiplier driven by a rolling water balance *or* an `ETR/ETP` ratio.
Pequeno et al. (2011) used real evapotranspiration (`ETR`) from a site-calibrated water balance in
their best model. We have Open-Meteo FAO-56 `et0` and precipitation; we have **no** soil data.

**Decision.** The V1 water-deficit feature is the **rolling climatic water balance
`P − ET0`, in mm, at 30-day (primary) and 90-day (seasonal) windows** (a 7-day short-term version
is also kept). Sign convention: **negative = deficit**. `water_deficit_30d`, `water_deficit_7d`
are already in `weather_weekly_features.csv`; a 90-day version is added in Phase 5.

**Rejected for V1:**
- **`ETR/ETP` ratio** — computing `ETR` (actual ET) needs a soil-water-balance model: available
  water capacity, rooting depth, a crop coefficient `Kc`, and a stress function. Every one of
  those is a `hypothesis` parameter for an unmanaged roadside C4 verge, so `ETR/ETP` would stack
  modelling error on top of a proxy. Deferred to a possible V2 if a soil dataset appears.
- A bucket / API-style soil-moisture model — same objection.

**Rationale.**
*Scientific:* `P − ET0` is the FAO-56 "climatic water balance" — a standard, transparent
agrometeorological deficit index that needs no invented soil parameters. `ET0` is already computed
the standard way (FAO-56 Penman-Monteith, by Open-Meteo). It is monotone with atmospheric drought
stress and it is what Phase 1 named as the fallback when only `P` and `ET0` are available.
*Operational:* reproducible from two columns we already hold; directly interpretable ("the last 30
days ran 40 mm short of demand"); no extra data pull; no extra knob.

**Provenance.** Inputs (`P`, `ET0`) are `weather_real`; the metric is a `literature_derived`
*method*, pinned by `feature_spec_version = "weather-v1"`.

**Enforced in.** `build_weather.py`, Phase 5 `feature_row`.

---

## DEC-003 — GDD base temperature

**Context.** Phase 1: `Tb = 15 °C` is confirmed only for *Pennisetum* / *Cynodon* in SP (Villa
Nova et al. 2007). `Tb` for *Urochloa* / *Panicum* — which dominate roadside verge — was not found
with a citation; Brazilian literature puts the range at 10–17 °C and calls it controversial and
cultivar-dependent. Phase 3: the Tb choice more than halves the annual GDD sum (Tb10 ≈ 3 300,
Tb15 ≈ 1 561, Tb17 ≈ 1 060 °C·d/yr for the same cell).

**Decision.**

1. `Tb = 15 °C` is a **reference value only**. It is the default of `build_weather.py --tb` and
   the value written to `weather_observation.gdd_c` / `gdd_base_temp_c`.
2. The **sweep set is {10, 15, 17} °C**, always. `weather_weekly_features.csv` carries
   `gdd_week_tb10_c`, `gdd_week_tb15_c`, `gdd_week_tb17_c` so Phase 5/8 can sweep without
   re-fetching.
3. **No `Tb` value is asserted as the base temperature of SP-021 vegetation.** Phase 8 must report
   model skill for all three and treat `Tb` as a sensitivity axis, not a fitted constant. Phase 13
   states this in the limitations.
4. Daily GDD is the simple-average form `max(0, (Tmax + Tmin)/2 − Tb)`. A "corrected GDD" /
   photothermal-unit variant (which Pequeno et al. found equally strong) is a **V2** alternative,
   not V1.

**Enforced in.** `build_weather.py`, `params.yaml:gdd_base_temperature` (note: transferred from
other species), every raw/processed file's `gdd_base_temp_c`, `feature_spec_version = "weather-v1"`.

---

## DEC-004 — Partial ISO weeks

**Context.** `weather_weekly_features.csv` has 8 rows (2 per cell) with `week_days_present < 7`:
the ISO week containing 2023-01-01 (1 day) and the final partial week of the series.

**Decision.** **Mark, keep, exclude from training.**

- Keep the partial-week rows in `weather_weekly_features.csv` — they carry a real partial
  aggregate; silently dropping data is worse. They are already flagged by `week_days_present`.
- In `feature_row` (Phase 5): any row whose **anchor week** or whose **+7 / +14 / +30 day target
  weeks** have `week_days_present < 7` is set `split = 'excluded'` (never train / validation /
  test).
- **Not reweighted.** Sample weights would add a knob with no principled V1 value. Exclusion is
  transparent and costs ~2 weeks per cell out of 193.

**Rationale.** A `gdd_week` summed over 1 day is not comparable to one over 7; the weekly
aggregates on a partial week are biased low. Rolling windows that merely *end* inside a partial
week are fine (they use daily data), but the weekly features are not.

**Enforced in.** Phase 5 `feature_row` build, `feature_spec_version = "weather-v1"`.

---

## DEC-005 — Corridor geometry

**Context.** The SP-021 centre line is approximated by GreenV's `apps/web` `ROAD_ANCHORS`
(7 points). GreenV has no official `marco km` linear reference in the repository.

**Decision.**

1. The `ROAD_ANCHORS`-derived coordinates (`provenance = hypothesis`) are **accepted for the V1
   climate layer.** At ERA5 / ERA5-Land resolution (9–25 km), a 1–2 km positional error changes
   nothing: the corridor maps to the same 4 cells and the QC / climatology are unaffected.
2. They are **explicitly not an official linear reference.** No `km`, no trecho boundary, no
   centroid derived from them may be presented as surveyed. Every such value keeps
   `coord_provenance = hypothesis` / `linear_reference_provenance = 'hypothesis'`.
3. The real `marco km` linear reference remains the open dependency for *placing a measurement on
   the map at km precision*. It does **not** block the V1 weather layer or the V1 model.

**Enforced in.** `trecho_weather_cell.csv`, `seeds/trecho_sp021.sql`,
`params.yaml:linear_reference`, `data-dictionary.md §3`.

---

## DEC-006 — INMET validation

**Context.** INMET was the intended secondary (station-observation) cross-check. Its API is
unreachable from this environment (connection reset on 3 endpoints, 2026-09-09).

**Decision.** INMET is registered as **pending validation, not a V1 blocker.**

- The V1 weather layer is complete and cross-checked against an **independent reanalysis**
  (NASA POWER — `weather-eda.md` §7): temperature r 0.92–0.98 with a ~2 °C absolute spread,
  precipitation daily r 0.71 with ±25–30 % annual-total divergence in dry years.
- Deferred work (no phase depends on it): a station pull from **A701 (São Paulo – Mirante de
  Santana)** and **A771 (Interlagos)** via the BDMEP portal, plus a temperature / precipitation
  comparison, once network access allows.
- V1 methodology proceeds on Open-Meteo `era5_seamless` + the NASA POWER cross-check.

**Enforced in.** `weather-eda.md` §7, §10; this log.

---

## DEC-007 — Weather history extent

**Context.** Phase 3 obtained 2023-01-01 → 2026-09-01 (3.7 years, ~3 full annual cycles). A wider
2020–2026 pull hit Open-Meteo free-tier call-weight limits (HTTP 429).

**Decision.** **Do not widen to 2018–2022 now.** 3.7 years is sufficient for weekly seasonality,
30 / 90-day rolling-feature warm-up, and a temporal train / validation / test split.

**Revisit if:** Phase 5 EDA shows instability from too few annual cycles (noisy seasonal
decomposition, an unrepresentative test year), or Phase 8 wants a longer backtest. Then run a
rate-limit-aware multi-call fetch for 2018–2022.

**Caveat carried forward.** 2023–2026 was progressively dry (Open-Meteo annual rain 1 226 → 801
mm). The model may see a dry-biased climate; Phase 5 EDA and Phase 13 limitations must state this.

**Enforced in.** `fetch_weather.py` (`START_DATE = "2023-01-01"`), `weather-eda.md` §3, §8.

---

## Downstream obligations (what "weather-v1" binds)

| Phase | Must respect |
|---|---|
| 4 (synthetic generator) | drive growth from the corridor-common rain/radiation series (DEC-001) and the per-cell GDD (DEC-001, DEC-003); use `P − ET0` for any water term (DEC-002); do not invent sub-cell weather |
| 5 (EDA / feature build) | build `feature_row` with `feature_spec_version = "weather-v1"`; exclude partial-week rows (DEC-004); state the dry-period caveat (DEC-007) |
| 8 (evaluation) | sweep `Tb ∈ {10, 15, 17}` and report all three (DEC-003); ablate the water-deficit term |
| 13 (documentation) | list the ~2 °C temperature / ±25–30 % precipitation uncertainty, the "1 rain regime for 29 km" resolution, the `hypothesis` geometry, and the pending INMET check |

Reviewed: 2026-09-09.
