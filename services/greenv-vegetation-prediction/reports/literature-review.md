# Literature review — vegetation / grass growth prediction for a roadside `faixa de domínio`

**Phase 1 artifact of [`docs/VEGETATION_PREDICTION_TASK.md`](../../../docs/VEGETATION_PREDICTION_TASK.md).**
This is a **rapid review**, not a systematic one: it is built from web-search results plus a
handful of full-text fetches (September 2026). Every number below carries its source. Where a value
could only be read from a search-result summary and not confirmed in the primary text, it is
marked **unconfirmed**. Nothing here is a GreenV measurement.

Companion files:

- [`data/reference/params.yaml`](../data/reference/params.yaml) — the parameters the synthetic
  generator needs, each with provenance and a confidence label.
- [`data/reference/datasets-catalogue.md`](../data/reference/datasets-catalogue.md) — datasets and
  weather sources, each classified `DIRECT` / `PROXY` / `REFERENCE ONLY` / `NOT USEFUL`.

---

## 1. What kind of grass this is about

SP-021 / Rodoanel Oeste runs through the São Paulo metropolitan region (subtropical, Cwa/Cfa,
dry-ish winter May–Sep, wet summer Nov–Mar). Brazilian roadside verge in this zone is dominated by
**C4 tropical grasses** — *Urochloa* (syn. *Brachiaria*) *decumbens* / *brizantha*, *Megathyrsus*
(syn. *Panicum*) *maximus*, *Melinis minutiflora* (capim-gordura), *Cynodon* spp., plus ruderal
species. This is a **hypothesis** for SP-021 specifically — the exact sward composition of the
Rodoanel verge has not been surveyed and is a Phase 5 / field question. It matters because C3 and
C4 grasses have different temperature responses.

The Brazilian taxonomic reference for confirming species identity later is **Flora e Funga do
Brasil (REFLORA / JBRJ)** — see the catalogue.

---

## 2. Growth vs temperature — Growing Degree Days (GDD)

### 2.1 The GDD framework

- **GDD (graus-dia)** = accumulated heat above a base temperature `Tb`, computed as
  `GDD_day = max(0, (Tmax + Tmin)/2 − Tb)` and summed over the regrowth period. It is the standard
  way to make "growth per week" comparable across seasons.
  Source: *Growing Degree-Day — an overview*, ScienceDirect Topics; NMSU Extension **B-826**
  "Accumulated Growing Degree Days … for Common New Mexico Rangeland Grasses"
  (<https://pubs.nmsu.edu/_b/B826/index.html>).
  **Use in generator:** the daily height increment is scaled by `GDD_day`, not by raw temperature.
  **Limitation:** GDD ignores photoperiod and assumes a linear response between `Tb` and an upper
  threshold; tropical-forage work often prefers "corrected GDD" or photothermal units (§2.3).

### 2.2 Base temperature `Tb` for warm-season / C4 grasses

- **Temperate-literature default: `Tb ≈ 10 °C` (50 °F)** for warm-season / C4 grasses (0 °C for
  cool-season). Source: NMSU B-826; GreenCast/Syngenta *About Growing Degree Days*.
  **Limitation:** derived in North America for rangeland, not Brazilian roadside verge.

- **Brazilian tropical-forage literature: `Tb ≈ 14–17 °C`, controversial and cultivar-dependent.**
  Source: search summary of *Método alternativo para cálculo da temperatura base de gramíneas
  forrageiras*, Ciência Rural (scielo `VgYt9K8ddxmCY7ST9cM6qFG`) — **unconfirmed** (read from
  summary only). The same summary notes elephantgrass ≈ 15 °C and *Brachiaria decumbens* ≈ 15–16 °C.

- **Villa Nova et al. (2007), Ciência Rural 37(2)** — *Determinação da temperatura-base inferior de
  plantas forrageiras com o uso de unidades fototérmicas*.
  DOI: `10.1590/S0103-84782007000200039`. Location: Piracicaba, SP (same climate zone as SP-021).
  Variable: lower base temperature via photothermal units.
  **Result:** `Tb = 15 °C` for elephantgrass (*Pennisetum purpureum* cv. Napier); `Tb ≈ 11.5 °C`
  for stargrass (*Cynodon nlemfuensis* cv. Florico). Confirmed from full-text fetch.
  **Use in generator:** adopt `Tb = 15 °C` as the central value for the SP-021 C4 sward, with a
  sensitivity sweep over 10–17 °C.
  **Limitation:** the study covers *Pennisetum* and *Cynodon*, **not** *Urochloa* or *Panicum*,
  which dominate roadside verge; transfer is an assumption.

### 2.3 Temperature as the dominant driver of tropical-forage accumulation

- **Tonato, Barioni, Pedreira, Rodrigues et al. (2010), Pesq. Agropec. Bras. 45(7)** —
  *Desenvolvimento de modelos preditores de acúmulo de forragem em pastagens tropicais*.
  URL: <https://www.scielo.br/j/pab/a/B3fW8YhjT9jGpmMFc76t7kQ/?lang=pt>.
  Data: rainfed cutting trials, *Cynodon*, *Panicum* and *Urochloa* cultivars, Central Brazil.
  Variables: Tmin, Tmax, Tmean, global radiation (Rg), day-of-year → mean forage accumulation
  rate (TMA, kg DM ha⁻¹ day⁻¹) by regression.
  **Results (from full-text fetch):** minimum temperature (`Tmin`) is the strongest single
  predictor, present in 62.3 % of the best models. For *Urochloa decumbens* cv. Basilisk after
  local calibration: **`TA = −74.124 + 6.39·Tmin`** (kg DM ha⁻¹ day⁻¹), standard error
  3.95 kg ha⁻¹ day⁻¹. Model skill by genus: `Cynodon` R² ≈ 0.60–0.70, `Urochloa` R² ≈ 0.55–0.60,
  `Panicum` R² < 0.40.
  **Use in generator:** anchor the *dry-matter* growth rate to a linear `Tmin` term of roughly the
  above slope, converting DM → height through a bulk-density assumption (§6). The low `Panicum` R²
  is itself a signal that a purely climatic model leaves large unexplained variance — the
  generator should carry a substantial per-`trecho` random effect and residual noise.
  **Limitation:** DM accumulation under a cutting regime ≠ standing height on an unmanaged verge;
  slope is post-local-calibration; Central-Brazil sites, not the Rodoanel.

- **Pequeno, Pedreira, Villa Nova et al. (2011), Pesq. Agropec. Bras. 46(7)** —
  *Modelos empíricos para estimar o acúmulo de matéria seca de capim-marandu com variáveis
  agrometeorológicas*. Old scielo id `S0100-204X2011000700001` (full text could not be fetched —
  certificate error; values below are from the search summary and are **unconfirmed**).
  Data: *U. brizantha* cv. Marandu, rainfed 1998–2002.
  **Results (unconfirmed):** univariate R² — corrected GDD 0.75, corrected Tmin 0.75, climate
  growth index (ICC) 0.74. Best multivariate model (`Tmin` + `Rg` + real evapotranspiration
  `ETR`): R² = 0.84, RMSE = 14.72 kg DM ha⁻¹ day⁻¹.
  **Use in generator:** supports a three-term driver — heat (`GDD`/`Tmin`), radiation, water — and
  gives an order-of-magnitude for weekly DM prediction error (~15 kg ha⁻¹ day⁻¹) that our
  synthetic noise should not undercut.
  **Limitation:** cv. Marandu under cutting; unconfirmed numbers; a single site.

---

## 3. Regrowth after `roçada` (defoliation)

- **Sigmoid / logistic regrowth.** Herbage mass plotted against time since defoliation is
  **sigmoid**: a lag phase, then a near-exponential phase, then a linear phase, then a plateau as
  self-shading and senescence set in.
  Sources: *A practical equation for pasture growth under grazing* (ResearchGate 229497968);
  *Ecophysiology of C4 Forage Grasses* (ResearchGate 282458615); *Resource Reallocation of Two
  Grass Species During Regrowth After Defoliation*, PMC6290090.
  **Use in generator:** model post-`roçada` height as a logistic (or monomolecular) approach to a
  plateau, with the growth-rate coefficient scaled by GDD / radiation / water that week.

- **Lag phase and cutting severity.** *Effect of intensity of defoliation on regrowth of pasture*
  (ResearchGate 248893512): the greater the defoliation intensity, the lower the initial regrowth
  rate and the longer the time to reach maximum growth rate; early/severe cutting lengthened the
  lag phase **by more than one week on average**.
  **Use in generator:** `regrowth_lag_days` in the 5–14 day range, longer for a lower residual
  cutting height.
  **Limitation:** these are temperate ryegrass / timothy / orchardgrass studies; the qualitative
  shape transfers, the exact lag length for a C4 verge is an assumption.

- **Time to near-maximum growth rate.** Regrowths of "at least 14 days but less than 28 days" reach
  close to the maximum average growth rate of highly digestible material (same sources).
  **Use in generator:** the exponential-to-linear transition of the regrowth curve sits around
  day 14–28 after a `roçada`.

- **Residual height after a `roçada`.** Not found with a citation for roadside mechanical mowing.
  DNIT practice is a maximum standing height of 30 cm and a mowing trigger at 0.50 m (§7); the
  cut-to height is a **hypothesis** — assume 3–8 cm residual, swept in the generator.

---

## 4. Rainfall, water deficit, humidity, radiation, seasonality

### 4.1 Water

- **Drought cuts C4 forage growth sharply.** *Contrasting strategies to cope with drought
  conditions by two tropical forage C4 grasses*, AoB PLANTS (2015), DOI
  `10.1093/aobpla/plv107` — drought reduced **shoot dry mass by ~35 %** in both grasses (Napier
  and *Brachiaria* hybrid Mulato II).
  *Nutritional imbalances … under intra-seasonal drought … alleviated by irrigation in tropical
  forage grasses*, Sci. Reports (2025), DOI `10.1038/s41598-025-18760-x` — even short dry spells
  inside the rainy season reduce forage production; irrigation recovers it.
  **Use in generator:** a water-deficit multiplier on the daily increment, driven by a rolling
  rainfall balance (or `ETR/ETP` if using NASA POWER / Open-Meteo ET0). A sustained deficit drops
  the growth rate toward ~0.5–0.7× of the well-watered rate.

- **Marandu needs > 800 mm annual precipitation and does not tolerate droughts longer than four
  months.** Source: EMBRAPA cultivar material (search summary, **unconfirmed**).
  **Use in generator:** a hard slowdown, not a stop, under prolonged dry season — SP winters are
  dry but rarely 4 months rainless.

### 4.2 Radiation

- **Radiation-use efficiency (RUE) of C4 grass ≈ 0.53 g DM per MJ intercepted PAR** (range
  0.45–0.57 for C4-grass monocultures). Source: *Nutritional and physiological limitations shape
  the radiation-use efficiency response to legume proportion in C4 grass–legume mixtures*, AoB
  PLANTS 17(4) 2025, `10.1093/aobpla/plaf036`. Broader forage RUE spans 0.91–2.76 g MJ⁻¹
  depending on genotype, density, environment (*Radiation Use Efficiency of Forage Resources: A
  Meta-Analysis*, Agronomy Journal, `10.2134/agronj2018.10.0645`).
  **Use in generator:** an optional radiation-driven growth path (`ΔDM = RUE × fIPAR × PAR`),
  useful as a cross-check on the temperature-driven path and to introduce a real seasonal
  radiation signal from the weather data.
  **Limitation:** RUE varies several-fold; use as a bounded prior, not a point value.

### 4.3 Humidity

- No quantitative relationship between air humidity and grass height growth was found in this
  review. Humidity mostly acts indirectly (through VPD and evaporative demand, already captured by
  ET0 / water balance). **Recommendation:** include humidity as a *candidate feature* for the
  models but **do not** give it an explicit term in the generator — mark it a `hypothesis` if
  used.

### 4.4 Seasonality

- **Strong summer/winter contrast for tropical grasses.** *Crescimento de capim-braquiária
  influenciado pelo grau de sombreamento e pela estação do ano*, Pesq. Agropec. Bras. (Paciullo et
  al.) — morphogenetic and structural variables and forage-production rates fall in winter
  regardless of shading. Winter leaf-appearance rate of cv. Marandu is roughly **50 % of the
  summer/spring rate** (search summary of Brazilian morphogenesis studies, **unconfirmed**).
  **Use in generator:** the seasonal factor is largely *emergent* from real weekly GDD, radiation
  and rainfall in the weather data — do not double-count it with a separate hard-coded sine term;
  keep at most a small residual seasonal amplitude.
  **Limitation:** "stagnation" in winter is well established qualitatively; the exact multiplier is
  site- and cultivar-specific.

---

## 5. Statistical and ML models used for grass-growth prediction

| Study | Models | Inputs | Target / step | Result | Note for us |
|---|---|---|---|---|---|
| McHugh et al. (2020), *Data-Driven Classifiers for Predicting Grass Growth in Northern Ireland*, PMC7274299 | Decision Tree, **Random Forest**, Naïve Bayes, Neural Net | rainfall, air temp, solar radiation, pre/post-grazing cover, soil moisture, DM, CP | daily growth (kg DM/ha) binned High/Med/Low | RF best; Kappa 0.76, RMSE 0.28 on spring data with SMOTE oversampling; 4,917 records, 50 farms (AFBI GrassCheck) | RF is a strong baseline; class imbalance / resampling matters; weather + management + soil is the feature set that worked |
| Onibonoje et al. (2025), arXiv 2511.03749 | ARIMA (baseline), LSTM, GRU, MLP, **TCN** | weekly grass **height** only (univariate) | 1-step-ahead weekly height | RMSE: ARIMA 5.31, LSTM 5.94, GRU 3.75, MLP 3.64, **TCN 2.74** cm; 1,757 weeks, 1982–2015, Teagasc Cork | even a pure height autoregression is informative; ARIMA/persistence is a real bar to beat; deep nets are out of V1 scope but the metric scale (RMSE ~3–5 cm weekly) is a useful yardstick |
| Nickmilder et al. (2021), *Development of ML Models to Predict Compressed Sward Height in Walloon Pastures*, MDPI Remote Sens. 13(3) 408 | cubist, glmnet, neural net, **random forest** | Sentinel-1 (radar), Sentinel-2 (optical), meteorological | compressed sward height (mm), pixel level | RMSE of independent validation < 20 mm CSH | RF competitive; multi-source (weather + remote sensing) helps |
| Various (Sci. Reports `s41598-024-59160-x`; Springer `s11119-023-10013-z`) | RF, SVM, PLS, **XGBoost** | Sentinel-2 + management | tropical pasture forage mass / canopy height | XGBoost often best (e.g. R² 0.86) | supports comparing RF **and** gradient boosting; do not pre-pick |

**Takeaway for Phases 6–8:** the required model set (naïve baseline, linear/multiple regression,
Random Forest, Gradient Boosting) matches what this literature actually uses. Persistence / ARIMA
is a genuinely hard baseline at short horizons. Weather + days-since-`roçada` + current height is
the feature core that repeatedly works.

---

## 6. Height ↔ biomass (proxy conversion)

Most tropical-forage literature reports **dry matter (kg DM/ha)**, not standing height (cm). To use
those parameters we need a bulk-density conversion.

- **Rising Plate Meter calibrations** give `herbage mass = a·CSH + b` with, e.g., the New Zealand
  standard `kg DM/ha = 140·CSH(cm) + 500`, or fixed multipliers of 250–260 kg DM/ha per cm; site-
  and season-specific, best RMSE ~244 kg DM/ha. Sources: *Rising Plate Meter Equations*
  (pasture.io); *Calibrating a rising plate meter to predict herbage mass of modern diverse
  agricultural swards* (Springer `s11119-026-10410-0`); Super-G platemeter factsheet.
  **Use in generator:** convert the DM growth rate to a height growth rate with a bulk density of
  order **100–260 kg DM ha⁻¹ cm⁻¹**, swept as a parameter. Compressed vs standing height differ —
  a further ~1.5–2× factor is a `hypothesis`.
  **Limitation:** all temperate ryegrass/clover swards; a lax, stemmy C4 verge has a very
  different (lower) bulk density. This conversion is the **weakest link** in the parameter chain.

---

## 7. The operational threshold — external validation of Motiva's rule

- **DNIT roadside standard.** Maximum tolerated vegetation height in the `faixa de domínio` is
  **30 cm**; mowing (`roçagem`) is to be performed when vegetation reaches **0.50 m**, within 3 m
  of the shoulder on tangents and 5 m on the inside of curves.
  Sources: DNIT — *Faixa de Domínio* (<https://www.gov.br/dnit/pt-br/rodovias/operacoes-rodoviarias/faixa-de-dominio>);
  GOINFRA *Conservação — Roçagem Manual e Mecânica* norm PDF; DNIT Resolution nº 9/2020 (referenced
  in search results).
  **Relevance:** Motiva's Nível 3 = "altura > 30 cm" is the same number the federal road authority
  uses as its maintenance limit — the business rule is externally corroborated, not arbitrary. The
  0.50 m mowing trigger is a real-world value for the generator's `roçada` scheduling policy.

---

## 8. Scientific limitations of using synthetic data for this problem

- **Plasmode / simulation bias.** Validation on synthetic data is "biased towards favoring methods
  that mimic the modeling choices made when generating the synthetic datasets" — even when the
  generator is close to the true data-generating process. Source: *Undersmoothed LASSO Models …
  Synthetic Negative Control Exposures for Bias Detection*, arXiv 2506.17760.
  **Consequence for us:** if the generator uses a GDD-linear growth term, a linear/GDD model will
  look artificially good. Mitigations already in the plan: the generator must use mechanisms the
  models are not told (Phase 4), an out-of-distribution holdout (different seed / parameter draw),
  and the circularity risk stated in the Phase 8 headline.

- **Domain gap / missing complexity.** Synthetic data "may fail to capture real-world complexity …
  creating domain gaps between simulated and real environments" and "can amplify bias or introduce
  labeling errors if datasets are not carefully validated." Sources: *Limitations of Synthetic
  Data* (apxml.com); *Synthetic data definition: Pros and Cons* (Keymakr).
  **Consequence:** no accuracy claim can be made from V1. Results are about the generator.

- **Weak structure → overinterpretation.** *Empirically calibrated simulations reveal the limits
  of phenotypic clustering algorithms for biodiversity assessment in data-scarce crops*,
  PMC12711051 — weakly structured synthetic datasets invite overinterpretation of model outputs.
  **Consequence:** report confidence intervals on every metric (Phase 8); treat "model A beats
  model B on synthetic data" as a methodology result, not a recommendation for production.

- **Bias inheritance.** If the literature parameters are themselves skewed (here: toward temperate
  agronomy and toward cutting trials rather than unmanaged verge), the synthetic dataset inherits
  that skew. Recorded in `params.yaml` as `climate_zone` and `confidence` on every parameter.

---

## 9. Key gaps this review could not close (→ still assumptions / hypotheses)

1. **SP-021 verge species composition** — no survey; assumed C4-dominant.
2. **`Tb` for *Urochloa* / *Panicum*** — only *Pennisetum* (15 °C) and *Cynodon* (11.5 °C)
   confirmed; the rest is a 10–17 °C sweep.
3. **DM → standing-height bulk density for a lax roadside C4 sward** — borrowed from temperate
   RPM calibrations; the weakest link.
4. **`roçada` residual cut height and regrowth lag for mechanical roadside mowing** — lag shape
   from temperate defoliation studies; residual height a pure assumption.
5. **Plateau (maximum) standing height on an unmown verge** — not found; assumed 0.6–1.2 m range.
6. **Winter growth multiplier for the specific SP-021 climate** — qualitative "≈50 %" only,
   unconfirmed; mostly to be left emergent from real weather.
7. **Measurement-noise scale of the GreenV automatic reading itself** — from
   [`docs/AUTOMATIC-HEIGHT.md`](../../../docs/AUTOMATIC-HEIGHT.md) we know it is unvalidated
   against a tape and carries `h95SpreadM` between-view disagreement; a numeric noise σ is an
   assumption until real packets exist.

---

## References (as gathered)

Full-text confirmed:

- Villa Nova, N. A. et al. (2007). *Determinação da temperatura-base inferior de plantas
  forrageiras com o uso de unidades fototérmicas.* Ciência Rural 37(2).
  DOI `10.1590/S0103-84782007000200039`.
- Tonato, F.; Barioni, L. G.; Pedreira, C. G. S. et al. (2010). *Desenvolvimento de modelos
  preditores de acúmulo de forragem em pastagens tropicais.* Pesq. Agropec. Bras. 45(7).
  <https://www.scielo.br/j/pab/a/B3fW8YhjT9jGpmMFc76t7kQ/?lang=pt>.
- McHugh, S. et al. (2020). *Data-Driven Classifiers for Predicting Grass Growth in Northern
  Ireland: A Case Study.* PMC7274299.
- Onibonoje, O. et al. (2025). *Applying Time Series Deep Learning Models to Forecast the Growth
  of Perennial Ryegrass in Ireland.* arXiv:2511.03749.
- Radiation-use efficiency: AoB PLANTS 17(4) 2025, `10.1093/aobpla/plaf036`; Agronomy Journal
  meta-analysis `10.2134/agronj2018.10.0645`.
- Drought in C4 forage grasses: AoB PLANTS 2015, `10.1093/aobpla/plv107`; Sci. Reports 2025,
  `10.1038/s41598-025-18760-x`.
- Rising Plate Meter calibration: Springer Precision Agriculture `10.1007/s11119-026-10410-0`;
  pasture.io *Rising Plate Meter Equations*.
- DNIT roadside standard: <https://www.gov.br/dnit/pt-br/rodovias/operacoes-rodoviarias/faixa-de-dominio>;
  GOINFRA *Conservação — Roçagem Manual e Mecânica*.
- Synthetic-data limits: arXiv:2506.17760; apxml.com *Limitations of Synthetic Data*; PMC12711051.

Read from search summary only (**unconfirmed**):

- *Método alternativo para cálculo da temperatura base de gramíneas forrageiras*, Ciência Rural
  (scielo `VgYt9K8ddxmCY7ST9cM6qFG`).
- Pequeno, D. N. L.; Pedreira, C. G. S.; Villa Nova, N. A. (2011). *Modelos empíricos para estimar
  o acúmulo de matéria seca de capim-marandu com variáveis agrometeorológicas.* Pesq. Agropec.
  Bras. 46(7). scielo `S0100-204X2011000700001` (full text fetch failed — certificate error).
- Paciullo, D. S. C. et al. *Crescimento de capim-braquiária influenciado pelo grau de
  sombreamento e pela estação do ano.* Pesq. Agropec. Bras.
- EMBRAPA cultivar material for cv. Marandu (precipitation requirement, drought tolerance).

Reviewed: 2026-09-09.
