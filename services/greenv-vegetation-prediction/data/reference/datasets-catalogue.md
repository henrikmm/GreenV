# Datasets and weather-source catalogue

**Phase 1 artifact of [`docs/VEGETATION_PREDICTION_TASK.md`](../../../../docs/VEGETATION_PREDICTION_TASK.md).**
Rapid review, September 2026. Each entry is classified:

- **DIRECT** — usable as-is to train / validate a per-`trecho` weekly vegetation-height model for a
  Brazilian roadside verge.
- **PROXY** — a related quantity (biomass, greenness, temperate sward height) or a related setting
  that can calibrate or sanity-check the synthetic generator.
- **REFERENCE ONLY** — background, taxonomy, or model parameters; not row-level training data.
- **NOT USEFUL** — noted so it is not re-investigated.

**Bottom line up front: no `DIRECT` dataset was found.** The closest are `PROXY` — temperate
grass-height time series and tropical-pasture biomass. This is the evidence base for the Phase 4
decision to generate a synthetic dataset driven by real weather.

---

## A. Vegetation growth / height / biomass datasets

### A1. Perennial ryegrass weekly height series, Teagasc Cork (1982–2015)

| Field | Value |
|---|---|
| Name | Weekly grass-height time series used in Onibonoje et al. (2025) |
| URL | arXiv:2511.03749 (<https://arxiv.org/abs/2511.03749>); underlying data from Teagasc AGRIC |
| Institution / origin | Teagasc, Animal & Grassland Research and Innovation Centre, Co. Cork, Ireland |
| Licence | Not stated in the paper; Teagasc research data, availability on request — **unverified** |
| Size | 1,757 weekly observations, 34 years, one site |
| Variables | weekly sward **height** only (univariate); no weather, no management columns published |
| Temporal frequency | weekly |
| Region | Cork, Ireland — temperate maritime (Cfb) |
| Species | *Lolium perenne* (perennial ryegrass) — **C3** |
| Applicability to GreenV | The single closest match in *structure*: a long weekly sward-height series. Good for (a) validating that our forecasting harness and metrics behave, (b) a realistic RMSE yardstick (~3–5 cm weekly, 1-step). |
| Limitations | Wrong species (C3), wrong climate, single managed research plot, no drivers, licence unverified. Cannot be transferred to a São Paulo C4 verge. |
| Classification | **PROXY** |

### A2. AFBI GrassCheck (Northern Ireland)

| Field | Value |
|---|---|
| Name | GrassCheck farm grass-growth dataset (used in McHugh et al. 2020, PMC7274299) |
| URL | <https://www.afbini.gov.uk/articles/grasscheck>; paper PMC7274299 |
| Institution / origin | Agri-Food and Biosciences Institute (AFBI), Northern Ireland |
| Licence | Programme data; public bulletins are open, row-level data availability **unverified** |
| Size | ~4,917 records, ~50 farms |
| Variables | daily grass growth (kg DM/ha); total rainfall, air temperature, solar radiation; pre/post-grazing cover, soil moisture, DM, crude protein, utilisation |
| Temporal frequency | weekly farm walks → daily growth estimates |
| Region | Northern Ireland — temperate maritime |
| Species | ryegrass-dominant swards — C3 |
| Applicability to GreenV | Shows the working feature set (weather + management + soil → growth) and that Random Forest is a strong baseline; a template for our `feature_row`. |
| Limitations | C3, temperate, grazed dairy swards; growth as kg DM/ha not height; class-binned target. |
| Classification | **PROXY** |

### A3. PastureBase Ireland / MoSt GG model outputs

| Field | Value |
|---|---|
| Name | PastureBase Ireland (PBI) national grassland database + Moorepark St Giles Grass Growth model |
| URL | <https://www.teagasc.ie/crops/grassland/pasturebase-ireland/>; growth map <https://pasturebase.teagasc.ie/> |
| Institution / origin | Teagasc, Ireland |
| Licence | Login-gated; farmer-owned records; **not open** |
| Size | Weekly grass covers, 2013–present, ~6,000 active farms |
| Variables | weekly grass cover / growth (kg DM/ha) per paddock; linked Met Éireann weather; fertiliser; management |
| Temporal frequency | weekly |
| Region | Ireland — temperate maritime |
| Species | perennial ryegrass ± clover — C3 |
| Applicability to GreenV | Reference for national-scale weekly growth modelling and for the MoSt mechanistic model structure; not obtainable. |
| Limitations | Not accessible; wrong species and climate. |
| Classification | **REFERENCE ONLY** |

### A4. "A dataset for pasture parameter estimation based on satellite remote sensing and weather variables"

| Field | Value |
|---|---|
| Name | Pasture parameter estimation dataset (Data in Brief / ScienceDirect S235234092400177X) |
| URL | <https://www.sciencedirect.com/science/article/pii/S235234092400177X> (abstract could not be fetched — HTTP 403) |
| Institution / origin | Not confirmed (fetch blocked); Data in Brief article |
| Licence | Data in Brief is typically CC BY — **unverified** |
| Size | Not confirmed |
| Variables (from search summary) | Sentinel-2 bands / vegetation indices; temperature, rainfall, vapour pressure deficit; forage-availability measures; 5 growth cycles |
| Temporal frequency | satellite revisit (~5-day Sentinel-2), 2018–2023 |
| Region | Not confirmed |
| Species | pasture (not confirmed) |
| Applicability to GreenV | Potentially a `PROXY` for a weather-driven forage-cycle model; needs the article opened (Phase 5) to classify properly. |
| Limitations | Could not verify origin, licence, region, or size — treat as a lead, not a resource, until fetched. |
| Classification | **PROXY (provisional — unverified)** |

### A5. Pasture biomass from top-view images (Australia)

| Field | Value |
|---|---|
| Name | *Estimating Pasture Biomass from Top-View Images: A Dataset for Precision Agriculture* |
| URL | arXiv:2510.22916 (<https://arxiv.org/abs/2510.22916>) |
| Institution / origin | Australian research groups (arXiv preprint) |
| Licence | Preprint; dataset licence **unverified** |
| Size | 1,162 top-view images + lab-validated biomass, 19 sites, 4 Australian states |
| Variables | RGB images, lab biomass (kg DM/ha), height, NDVI |
| Temporal frequency | campaign-based (not a regular time series) |
| Region | Australia — mixed temperate/subtropical |
| Species | mixed pasture |
| Applicability to GreenV | A height↔biomass↔NDVI calibration reference; not a growth-over-time dataset. |
| Limitations | No temporal dynamics; Australian pastures; image-centric. |
| Classification | **REFERENCE ONLY** |

### A6. Global Pasture Watch

| Field | Value |
|---|---|
| Name | Global Pasture Watch — grassland extent, class and short-vegetation height |
| URL | <https://github.com/wri/global-pasture-watch>; Google Earth Engine / STAC |
| Institution / origin | World Resources Institute + OpenGeoHub + LAPIG/UFG (Brazil is a partner) |
| Licence | Open (CC BY 4.0 for most layers) — **confirm per layer** |
| Size | Global, 30 m grid, 2000–2022 |
| Variables | grassland extent, cultivated/natural class, vegetation height (from ICESat-2 / GEDI + ML) |
| Temporal frequency | annual |
| Region | Global, includes Brazil |
| Species | grassland class, not species |
| Applicability to GreenV | Could give an annual regional height baseline near the Rodoanel; too coarse temporally and spatially for a 500 m weekly verge model. |
| Limitations | Annual; 30 m; satellite-derived short-vegetation height, not roadside sward height. |
| Classification | **REFERENCE ONLY** |

### A7. PhenoCam Dataset (v2.0 / v3.0)

| Field | Value |
|---|---|
| Name | PhenoCam Dataset — vegetation phenology from digital camera imagery |
| URL | <https://daac.ornl.gov/VEGETATION/guides/PhenoCam_V2.html>; <https://phenocam.nau.edu/> |
| Institution / origin | PhenoCam Network / Northern Arizona University; archived at ORNL DAAC (NASA) |
| Licence | Open (ORNL DAAC / NASA data-use policy; CC0-like with attribution) |
| Size | v3.0: 738 sites, ~4,800 site-years; grassland sites among them; 2000–2018+ |
| Variables | canopy greenness (GCC), green-up / green-down transition dates, 1- and 3-day series |
| Temporal frequency | daily / sub-daily → 1–3 day summaries |
| Region | mostly North America; few tropical/Brazilian sites |
| Species | site-level vegetation type (grassland, etc.), not species |
| Applicability to GreenV | A `PROXY` for the *shape* of a grassland growth/senescence season and for a greenness→growth-rate relationship; not height. |
| Limitations | Greenness ≠ height; almost no Brazilian roadside sites. |
| Classification | **PROXY** |

### A8. EMBRAPA / Brazilian agrometeorological forage models (Tonato 2010; Pequeno 2011)

| Field | Value |
|---|---|
| Name | Empirical forage-accumulation models for *Cynodon*, *Panicum*, *Urochloa* (Central Brazil) |
| URL | scielo `B3fW8YhjT9jGpmMFc76t7kQ`; scielo `S0100-204X2011000700001` |
| Institution / origin | EMBRAPA + ESALQ/USP |
| Licence | Open-access articles (SciELO) |
| Size | multi-year rainfed cutting trials; equations and coefficients, not row-level public data |
| Variables | Tmin/Tmax/Tmean, global radiation, GDD, evapotranspiration, water balance → DM accumulation rate (kg DM/ha/day) |
| Temporal frequency | per regrowth / cutting cycle |
| Region | Central Brazil (Goiás / São Carlos SP region) — same C4 grasses as SP-021 verge |
| Species | *Cynodon*, *Panicum maximum*, *Urochloa* cultivars — C4 |
| Applicability to GreenV | **The best regional match.** Supplies the temperature/radiation/water coefficients the generator needs (see `params.yaml`). |
| Limitations | Managed cutting trials, not unmown verge; DM not height; coefficients need local calibration; the 2011 paper's numbers are unconfirmed (fetch failed). |
| Classification | **REFERENCE ONLY** (parameters) — feeds the generator, not the training rows |

### A9. Flora e Funga do Brasil (REFLORA / JBRJ) — Poaceae

| Field | Value |
|---|---|
| Name | Flora e Funga do Brasil — Poaceae list |
| URL | <https://floradobrasil.jbrj.gov.br/>; GBIF dataset `aacd816d-662c-49d2-ad1a-97e66e2a2908` |
| Institution / origin | Jardim Botânico do Rio de Janeiro (JBRJ) |
| Licence | CC BY 4.0 (via GBIF) |
| Size | full Brazilian Poaceae checklist with descriptions, distribution, keys |
| Variables | taxonomy, morphology, geographic distribution, life form — no growth data |
| Temporal frequency | n/a |
| Region | Brazil |
| Species | all Brazilian grasses |
| Applicability to GreenV | The authority for confirming/normalising species names once the SP-021 verge sward is surveyed; feeds the `vegetation type` feature vocabulary. |
| Limitations | Taxonomy only; no growth, biomass or height. |
| Classification | **REFERENCE ONLY** |

### A10. Kaggle

- No Kaggle dataset with a **verifiable** origin for grass-height growth time series was found in
  this review. Teagasc / AFBI publish through their own portals and papers, not Kaggle. Kaggle
  copies of "grass growth" data seen in results have unclear provenance and are **NOT USEFUL**
  under the Phase 1 rule ("Kaggle somente se a origem dos dados for verificável").
- Classification: **NOT USEFUL** (as a class, pending a specific verifiable dataset).

---

## B. Weather / climate data sources (for the SP-021 corridor)

### B1. Open-Meteo Historical Weather API — **recommended primary source for V1**

| Field | Value |
|---|---|
| URL | <https://open-meteo.com/en/docs/historical-weather-api> |
| Origin | Open-Meteo (non-profit); data from Copernicus/ECMWF ERA5, ERA5-Land, ECMWF IFS |
| Licence | **CC BY 4.0** — free for commercial use with attribution ("Weather data by Open-Meteo.com", and cite ERA5 / Copernicus). Zenodo DOI `10.5281/zenodo.7970649`. |
| API key | **Not required** for non-commercial use; commercial/high-volume needs a key |
| Variables (daily) | temperature_2m max/min/mean; precipitation_sum, rain_sum, precipitation_hours; shortwave_radiation_sum; et0_fao_evapotranspiration; wind; sunshine_duration. Relative humidity and soil moisture available at **hourly** resolution (aggregate ourselves). |
| Spatial resolution | ERA5 0.25° (~25 km); ERA5-Land 0.1° (~11 km); IFS 9 km (from 2017) |
| Temporal coverage | ERA5 from 1940; ERA5-Land from 1950 |
| Update latency | ~5 days (reanalysis); IFS near-real-time |
| Fit for SP-021 | One or two grid cells cover the ~30 km road; enough for a weekly per-`trecho` model when `trechos` are mapped to the nearest cell. Gap-free, keyless, reproducible. |
| Limitations | Reanalysis, not station observations — smooths local extremes; ~11–25 km cells are coarse for a linear feature; humidity/soil need hourly pulls. |
| Classification | **Primary weather source, V1** |

### B2. INMET — BDMEP historical station data

| Field | Value |
|---|---|
| URL | <https://bdmep.inmet.gov.br/>; portal <https://portal.inmet.gov.br/dadoshistoricos>; automatic-station catalogue <https://portal.inmet.gov.br/paginas/catalogoaut> |
| Origin | Instituto Nacional de Meteorologia (INMET), Brazil (government) |
| Licence | Public government data (open; attribution to INMET) |
| Variables | daily/hourly temperature, precipitation, relative humidity, wind, global radiation, pressure (automatic stations minute→hourly) |
| Coverage | conventional stations = long historical series; automatic stations from ~2000s; updated ~every 90 days (BDMEP) |
| Region | national; several stations in the São Paulo metropolitan region near the Rodoanel |
| Fit for SP-021 | **Ground-truth cross-check** for the Open-Meteo reanalysis; real local humidity and radiation. |
| Limitations | Station gaps/downtime; not a continuous grid; BDMEP is 1–3 months behind; the nearest station is still some km from the road; access is a web export / third-party crawler, no official REST API. |
| Classification | **Secondary weather source — validation overlay** |

### B3. NASA POWER

| Field | Value |
|---|---|
| URL | <https://power.larc.nasa.gov/> (API docs <https://power.larc.nasa.gov/docs/services/api/temporal/daily/>) |
| Origin | NASA Langley; MERRA-2 (meteorology) + CERES (solar) reanalysis |
| Licence | Free, open, no key; on AWS Open Data registry |
| Variables | daily temperature, humidity, precipitation, wind, **solar irradiance (surface + PAR-relevant)**, evapotranspiration, ~200 derived parameters |
| Spatial resolution | ~0.5° × 0.625° meteorology; ~1° solar |
| Temporal coverage | 1981–present (daily); 2–7 day latency |
| Fit for SP-021 | Best free source of **solar radiation / PAR** for the RUE growth path; agroclimatology-oriented. |
| Limitations | Coarser grid than Open-Meteo ERA5-Land; solar at 1° is very coarse for a road. |
| Classification | **Tertiary weather source — solar radiation and ET** |

---

## C. Summary

| Question | Answer |
|---|---|
| A dataset usable directly to train the GreenV model? | **No.** Nothing `DIRECT`. |
| Closest usable data? | `PROXY`: temperate weekly grass-height series (A1), temperate growth+weather+management (A2), tropical pasture biomass/greenness (A5, A7). |
| Best regional science for parameters? | Brazilian C4 forage agrometeorological models (A8) + `Tb` for *Pennisetum*/*Cynodon* in SP (A8, literature review §2.2). |
| Weather for SP-021? | **Open-Meteo Historical (CC BY 4.0, keyless)** as primary, **INMET BDMEP** as local cross-check, **NASA POWER** for solar. All real, all free. |
| Consequence | Phase 4 (synthetic generator) is required; it will be driven by **real** Open-Meteo/INMET weather and **literature-derived** parameters, with every row stamped `synthetic`. |

Reviewed: 2026-09-09.
