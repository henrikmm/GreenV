# Synthetic vegetation-growth model — SP-021 (Phase 4, Part A)

**Phase 4 artifact of [`docs/VEGETATION_PREDICTION_TASK.md`](../../../../docs/VEGETATION_PREDICTION_TASK.md).**
This is the **scientific specification of the generator**, written before any CSV is produced. The
generator is implemented in [`src/greenv_vegpred/synth/`](../src/greenv_vegpred/synth/) to match
this document exactly.

> ## ⚠️ EVERYTHING THIS GENERATOR PRODUCES IS SYNTHETIC
> No output of this generator is a measurement. It is a mechanistic simulation driven by **real**
> Phase 3 weather and by literature-derived and hypothesised parameters. `true_height_cm` is a
> simulator diagnostic and is **never** a training target. Models train on `observed_height_cm`.
> Every row carries `data_source = synthetic`, `provenance = synthetic`. Every figure is
> watermarked.

It respects, without re-opening:

- [`reports/methodology-decisions.md`](methodology-decisions.md) — the frozen `weather-v1` climate
  decisions (DEC-001…DEC-007);
- [`data/reference/params.yaml`](../data/reference/params.yaml) — parameter values, ranges and
  `confidence` tags. Parameters marked `literature-unconfirmed` (LU) or `hypothesis` (H) are
  treated as **uncertainties to be swept**, never facts.

---

## 0. Simulation frame

- **Unit:** one structural `trecho` (118 of them: SP-021 km 0–29.3, 500 m, both `sentidos` —
  `seeds/trecho_sp021.sql`).
- **Time:** simulate **daily** from 2023-01-01 to 2026-09-01 (1 340 days); **observe weekly** on
  each ISO-week `as_of_date` (the last day of the ISO week — aligns with
  `weather_weekly_features.csv`).
- **Weather:** per `weather-v1` (DEC-001) — corridor-common `rain`, `shortwave`, `et0`,
  `water_deficit`; per-cell `temperature` and `GDD`. Each `trecho` takes its mapped cell from
  `trecho_weather_cell.csv`.
- **RNG:** one `numpy.random.Generator` seeded from `--seed`, split into independent sub-streams
  (per-trecho draws, daily process noise, roçada policy, observation noise/missingness/quality) so
  changing one does not shift the others.

Notation: `sigmoid(x) = 1/(1+e^-x)`; `smoothstep(x) = x²(3−2x)` for `x∈[0,1]`.

---

## 1. State per `trecho`

| State | Symbol | Meaning |
|---|---|---|
| current true height | `H(t)` | latent simulator height, cm, day `t` |
| days since last `roçada` | `τ(t)` | integer; 0 on the cut day |
| recent climate | — | from the mapped weather cell: daily `GDD_d` (at the scenario `Tb`), `SW_d`, 30-day rolling `def30_d = Σ(P−ET0)` |
| persistent trecho growth effect | `m_i` | dimensionless multiplier on the growth rate, **drawn once**, fixed for the whole run |
| persistent unobserved local condition | `s_i` | water-sensitivity multiplier, **drawn once**, **hidden** (never a feature) |
| vegetation type | `v_i` | categorical ∈ {`braquiaria`, `capim_gordura`, `ruderal_mix`}, drawn once, **hypothesis**; exposed as a feature, but its numeric effects are hidden |
| mowing trigger height | `trig_i` | cm, drawn once |
| plateau height | `Hmax_i` | cm, drawn once |

### Per-`trecho` draws (once per run, from the seed)

| Draw | Distribution (baseline) | Source param | Confidence |
|---|---|---|---|
| `m_i` | `LogNormal(μ=0, σ)` scaled to `mean 1`, `CV = CV_m` | `trecho_random_effect_cv` = 0.25 (0.15–0.40) | **H** |
| `s_i` | `clip(LogNormal(mean 1, CV 0.30), 0.5, 1.8)` | — | **H** (hidden) |
| `v_i` | `Categorical([0.55, 0.20, 0.25])` (braquiaria-dominant) | Phase 1 §1 (SP roadside verge is C4-dominant, unsurveyed) | **H** |
| `Hmax_i` | `clip(Normal(H0_plateau + veg_shift(v_i), 12), 55, 140)` | `plateau_height_cm` = 90 (60–120); `veg_shift` = {+8, +15, −10} cm | **H** |
| `trig_i` | `clip(Normal(trig0, 8), 25, 70)` | `mowing_trigger_height_cm` = 50 (30–60) | **LC** (DNIT/GOINFRA 0.50 m) |
| `H(0)` | `clip(Normal(20, 10), 3, Hmax_i)` | — warm-up | **H** |
| `τ(0)` | `randint(5, 60)` | — warm-up | **H** |

`m_i`, `s_i`, `Hmax_i`, `trig_i` are **fixed for the entire simulation** — this is the persistent
between-`trecho` heterogeneity (§5).

---

## 2. Growth dynamics (not a single linear formula)

Daily true-height update. Growth is **non-negative every day**; height only ever falls through an
explicit `roçada` event (§3).

```
# (a) post-roçada lag / recovery ramp
phi_lag = LAG_FLOOR + (1 - LAG_FLOOR) * smoothstep( clip(τ / LAG, 0, 1) )

# (e) thermal effect — emergent seasonality, per weather-v1 (no hard-coded season term here)
g_temp  = clip( GDD_d / GDD_REF , 0, 1.8 )          # 0 when GDD_d = 0 (daily mean temp < Tb)

# (f) hydric effect — HIDDEN non-linearity: a logistic in the 30-day climatic water balance
z       = ( def30_d + DEF_SHIFT ) / DEF_SCALE
g_water = W_MIN + (1 - W_MIN) * sigmoid( s_i * z )  # -> W_MIN under deep sustained deficit; ~1 when wet

# tiny photoperiod residual (NOT the seasonal signal — that is g_temp). Disable-able.
g_seas  = 1 + A_S * sin( 2*pi*(doy - PHI_S) / 365.25 )

# HIDDEN slow radiation modulation of the ceiling (more light since the cut -> slightly taller potential)
Hmax_eff = Hmax_i * ( 1 + K_RAD * clip( SWcum_since_rocada / SW_CUM_REF - 1, -0.15, 0.15 ) )

# (b)+(c)+(d) lag -> acceleration -> deceleration via a logistic in H
r_daily = R0 * m_i * phi_lag * g_temp * g_water * g_seas
eps     = exp( Normal(0, SIGMA_PROC) )              # HIDDEN multiplicative process noise
dH      = r_daily * eps * max(H, H_FLOOR) * ( 1 - H / Hmax_eff )
dH      = clip( dH, 0, DH_MAX )                     # physical cap on one day's growth
H       = clip( H + dH, 0, H_HARDCAP )
τ       = τ + 1
```

**How this produces the required shape.** With `H` small after a cut and `phi_lag` ramping from
`LAG_FLOOR` to 1 over `LAG` days: the first ~`LAG` days are suppressed (**lag phase**); then
`dH ∝ H·(1−H/Hmax)` gives near-exponential growth while `H ≪ Hmax` (**acceleration**), a roughly
linear middle, and `dH → 0` as `H → Hmax` (**deceleration / plateau**). This is the sigmoid
regrowth curve of Phase 1 §3 (`literature-confirmed` shape). `g_temp` scales the whole thing by
that day's real heat, so growth is fast in a wet summer and near-zero in a dry cold July —
seasonality is **emergent from the real weather**, not stamped on (DEC-001, DEC-003).

**Parameters**

| Symbol | Baseline value | Meaning | Confidence |
|---|---|---|---|
| `LAG` | 8 d (sweep 5–14) | regrowth lag length | **LU** (`regrowth_lag_days`) |
| `LAG_FLOOR` | 0.10 | residual growth fraction during the lag | **H** |
| `GDD_REF` | 5.0 °C·d | daily-GDD normaliser (≈ corridor mean daily GDD at Tb15) | **H** (normaliser) |
| `R0` | **0.085** /day (logistic); **0.020** /day (OOD monomolecular) | intrinsic rate; **calibrated** so a mid-recovery trecho reaches ~30 cm in ~3–4 weeks in peak summer and ~12–20 weeks in a dry winter — the order of magnitude implied by Tonato et al. 2010 converted through bulk density. Lowered from a first-pass 0.11 after QC (see §11). | **H** (calibration target is LU) |
| `W_MIN` | 0.65 (sweep 0.4–1.0) | growth multiplier floor under deep sustained deficit | **LC** (`water_deficit_growth_multiplier`; AoB PLANTS 2015, −35 % shoot DM) |
| `DEF_SHIFT` | +15 mm | centres the logistic so a mild deficit barely bites | **H** |
| `DEF_SCALE` | 40 mm | logistic steepness in `def30` | **H** |
| `A_S` | 0.03 | amplitude of the tiny photoperiod residual (≈ ±3 %) | **H** |
| `PHI_S` | 20 (day-of-year) | phase of the residual | **H** |
| `K_RAD` | 0.10 | max ±10 % radiation modulation of `Hmax` | **H** (hidden) |
| `SW_CUM_REF` | 250 MJ/m² | radiation-since-cut normaliser | **H** |
| `SIGMA_PROC` | 0.15 | log-sd of daily multiplicative process noise | **H** (hidden) |
| `H_FLOOR` | 2 cm | floor inside `max(H, H_FLOOR)` so growth restarts after a hard cut | **H** |
| `DH_MAX` | 5 cm/day | physical cap on one day's true growth (peak tropical grass height growth) | **H** |
| `H_HARDCAP` | 150 cm | absolute height ceiling, never exceeded | **H** |
| `Tb` | scenario ∈ {10, 15, 17} °C, **ref 15** | GDD base temperature | **LC** for *Pennisetum*/*Cynodon* (Villa Nova 2007); **H** transferred to the SP-021 sward |

---

## 3. `roçada` (mowing) events

`roçada` is an **explicit event**, never natural negative growth.

**Scheduling** (weekly inspection on each ISO-week `as_of_date`, only if no cut is already pending
for the trecho):

```
season_factor = 1.0 if month in {Oct..Mar} else 0.55         # fewer campaigns in the dry/cold season
u ~ U(0,1)
if   H_true >= trig_i        and u < 0.90:  schedule, reason = 'trigger'
elif 30 <= H_true < trig_i   and u < 0.05 * season_factor:  schedule, reason = 'proactive'

on schedule:
    delay_days = clip( round( Gamma(shape=2.5, scale=DELAY_MEAN/2.5) ), 2, 45 )
    cut_date   = inspection_date + delay_days
```

**Cut** (on `cut_date`):

```
H_before  = H_true
residual  ~ U(RES_LO, RES_HI)          # from rocada_residual_height_cm
H_true    = residual
τ         = 0
SWcum_since_rocada = 0
emit rocada_event(trecho_id, occurred_on = cut_date, height_before_cm = H_before,
                  height_after_cm = residual, source = 'synthetic', reason, delay_days,
                  trigger_height_cm = trig_i)
```

**Why not "cut exactly at 30 cm":** the trigger is a per-`trecho` draw `~N(50, 8)` cm (DNIT's
0.50 m, `literature-confirmed`), the cut lands `Gamma(mean DELAY_MEAN)` days later (by which time
the `trecho` is taller), 10 % of qualifying inspections do nothing, ~5 % of inspections cut
proactively below trigger, and the whole rhythm is seasonally modulated. So height-at-cut and
`days_since_rocada` vary widely — no clean threshold rule for a model to exploit.

**Parameters**

| Symbol | Baseline | Meaning | Confidence |
|---|---|---|---|
| `trig0` | 50 cm | mean mowing trigger (per-trecho `N(50, 8)`) | **LC** (DNIT/GOINFRA `roçagem` at 0.50 m) |
| `DELAY_MEAN` | **8 d** (baseline); 16 d (OOD) | mean operational delay trigger → cut. Lowered from 14 d after QC (§11). | **H** |
| Gamma shape | 2.5 | delay distribution shape | **H** |
| `RES_LO, RES_HI` | 3, 8 cm | residual cut-height range | **H** (`rocada_residual_height_cm`) |
| inspection hit-rate 0.90 / proactive 0.05 / `season_factor` 1.0/0.55 | — | operational rhythm | **H** |

---

## 4. Climate coupling (`weather-v1`, frozen)

- **Corridor-common** (one series, all 118 trechos): `rain_7d/14d/30d`, `shortwave_7d`, `et0_7d`,
  `water_deficit_30d` (= `Σ_30d (P − ET0)`, DEC-002). Source cell `-23.5:-46.8`.
- **Per weather cell** (4 series): daily `GDD_d`, `tmin/tmean/tmax_week`. Each trecho uses its
  mapped cell (`trecho_weather_cell.csv`).
- **Water term:** `P − ET0` rolling balance only (DEC-002). No `ETR/ETP`, no soil model.
- **`Tb`:** the generator runs a **scenario** `Tb` (default 15 °C, the *reference*). QC and the
  emitted features carry `gdd_week` at Tb ∈ {10, 15, 17} so Phase 5/8 can sweep. **No `Tb` is
  asserted to be the SP-021 base temperature** (DEC-003).
- The daily `GDD_d` used inside the growth loop is recomputed from that cell's daily `Tmin`/`Tmax`
  at the scenario `Tb` — `max(0, (Tmax+Tmin)/2 − Tb)`.

---

## 5. Between-`trecho` heterogeneity (persistent; all `hypothesis`)

Drawn once per run, fixed for all 1 340 days:

| Effect | How | Exposed? |
|---|---|---|
| growth multiplier `m_i` | `LogNormal`, `CV_m = 0.25` (baseline unimodal) | **hidden** |
| water sensitivity `s_i` | `LogNormal(mean 1, CV 0.30)`, clipped `[0.5, 1.8]` — scales the deficit logistic | **hidden** |
| plateau `Hmax_i` | `N(90 + veg_shift, 12)` clipped `[55, 140]` | **hidden** (numeric); `vegetation_type` category is exposed |
| trigger `trig_i` | `N(50, 8)` clipped `[25, 70]` | **hidden** |
| vegetation type `v_i` | Categorical, braquiaria-dominant | **exposed** as `vegetation_type` (its numeric shifts are hidden) |

`CV_m = 0.25` is a **hypothesis**, motivated only by the observation that climate explains < 60 %
of forage-accumulation variance in Tonato et al. 2010 (Panicum R² < 0.40) — i.e. large
between-site variance is expected. It is swept in the OOD holdout (§9, §OOD) and should be swept in
sensitivity runs.

---

## 6. Noise separation

| Quantity | Definition | Role |
|---|---|---|
| `true_height_cm` | `H(as_of_date)` — the latent simulator state | **diagnostic only. NEVER a training target.** Emitted for QC. |
| `observed_height_cm` | `clip( true_height_cm + ξ , 0, 160 )`, `ξ ~ Normal(0, SIGMA_MEAS)`; with prob 0.02, `ξ *= 3` (gross outlier / bad frame) | **the training signal.** Models use this. |

`SIGMA_MEAS` = 4 cm baseline (sweep 2–8) — **hypothesis**, anchored only to the qualitative fact
in `docs/AUTOMATIC-HEIGHT.md` that the real instrument is unvalidated against a tape and carries
between-view disagreement. The 2 % gross-outlier rate mimics a bad reconstruction frame.

`weekly_growth_cm` and `previous_observed_height_cm` are computed from **observed** heights of
consecutive *emitted* observations (which may be > 1 week apart when weeks are missing);
`weeks_since_prev_obs` records the gap.

---

## 7. Missingness

Each `trecho`-week: emit an observation with probability `1 − miss_p_effective`, else emit
**nothing** (a real gap; never silently filled).

```
miss_p_effective = clip( MISS_P + (0.35 if previous week was missing else 0), 0, 0.85 )
```

`MISS_P` = 0.25 baseline (sweep 0.1–0.5) — **hypothesis**. The `+0.35` after a missed week makes
gaps **cluster** (a drive skipped for 2–3 weeks) rather than being i.i.d. Missing weeks are gaps
in `observation_date`; nothing is imputed.

---

## 8. Operational status / blockers

Each emitted observation draws its own quality, independent of any future state:

```
cell_coverage ~ clip( Normal(0.82, 0.12), 0.05, 1.0 )
frame_count   ~ Poisson(90)
degraded      ~ Bernoulli(0.08)                    # depth service degraded / mock-ish

operational_status = 'not-ready' if (cell_coverage < 0.50 or frame_count < 20 or degraded)
                     else 'ready'
blockers = e.g. ['low-coverage'] / ['insufficient-frames'] / ['depth-degraded'] / []
```

Tuned so the overall `not-ready` share ≈ `not_ready_fraction` = 0.20 (**LC-qualitative**,
`docs/AUTOMATIC-HEIGHT.md`). `nivel_observed` is `0` whenever `operational_status = 'not-ready'`
(matches the `schema.sql` generated `nivel` column). `nivel_true` is always the raw 1/2/3 of
`true_height_cm` (diagnostic).

**No future information** enters `operational_status` or `blockers`: they depend only on that
observation's own coverage/frame/degraded draws.

> **Difference from the real system today.** Every *real* packet currently carries
> `operational_status = not-ready` (the `road-metadata-missing` blanket blocker,
> `docs/AUTOMATIC-HEIGHT.md`). The synthetic dataset models a **mix** (~20 % not-ready) so the
> Phase 5/8 pipeline learns to *carry* the status through. This is a deliberate, documented
> departure — the synthetic `ready` rows are **not** a claim that the real pipeline produces
> ready readings.

---

## 9. Anti-circularity — hidden mechanisms

The generator must not be a re-statement of any Phase 7 model (naïve, linear/multiple regression,
Random Forest, Gradient Boosting).

**Exposed as features (Phase 5, `weather-v1`):** `observed_height_cm`, `previous_observed_height_cm`,
`weekly_growth_cm`, `days_since_rocada`, `gdd_week_tb{10,15,17}` (per cell), `tmin/tmean/tmax_week`,
`rain_7d/14d/30d`, `shortwave_7d`, `et0_7d`, `water_deficit_30d`, `week_of_year`, `season_sin/cos`,
`dry_season_flag`, `vegetation_type` (category), `km_mid`, `centroid_lat/lon`, `operational_status`.

**Hidden (never a feature):**

1. the per-`trecho` growth multiplier `m_i` and water sensitivity `s_i`;
2. the **logistic height dependence** `H·(1 − H/Hmax_eff)` — models see `height` but not the ODE;
3. the **lag ramp** `phi_lag(τ)` (smoothstep) — models see `days_since_rocada` but not its shape;
4. the **sigmoid transform of the 30-day deficit** — models see `water_deficit_30d` *linearly*;
5. multiplicative daily **process noise** `eps`;
6. the **radiation → plateau** slow modulation;
7. the small **photoperiod residual** `g_seas`;
8. per-`trecho` `Hmax_i`, `trig_i` numeric values, and `veg_type` numeric shifts;
9. the **operational delay** distribution between trigger and cut;
10. the `GDD_REF` / `DEF_SCALE` normalisers.

Consequence: a linear model on the exposed features cannot recover (i) the saturating
non-linearity in deficit, (ii) the height-dependent deceleration, (iii) per-`trecho` multipliers,
(iv) the lag ramp. A tree model can approximate (i)–(ii) but not (iii) without over-fitting
`trecho_id`. **That gap is the point** — Phase 8 must report it in the headline (synthetic
evaluation favours models that mimic the generator; here they structurally cannot fully).

---

## 10. Parameter provenance summary

| Confidence | Parameters |
|---|---|
| **literature-confirmed (LC)** | nível thresholds 10/30 cm (Motiva + DNIT); `W_MIN` water-deficit floor 0.65 (AoB PLANTS 2015); mowing trigger `trig0` ≈ 50 cm (DNIT/GOINFRA); regrowth curve **shape** = sigmoid/logistic (Phase 1 §3); `not_ready` share ≈ 0.20 (qualitative, AUTOMATIC-HEIGHT.md); `Tb = 15 °C` **for *Pennisetum*/*Cynodon* in SP** (Villa Nova 2007) |
| **literature-unconfirmed (LU)** | `LAG` regrowth lag ≈ 8 d and time-to-near-max ≈ 21 d (temperate defoliation studies, not C4); the order-of-magnitude growth rate that `R0` is calibrated to (Tonato et al. 2010, via an unconfirmed bulk-density conversion) |
| **hypothesis (H)** | `R0`, `GDD_REF`, `DEF_SHIFT`, `DEF_SCALE`, `A_S`, `PHI_S`, `K_RAD`, `SW_CUM_REF`, `SIGMA_PROC`, `H_FLOOR`, `DH_MAX`, `H_HARDCAP`; `H0_plateau` 90 cm and `veg_shift`; `RES_LO/RES_HI`; `CV_m` 0.25 and the `m_i`/`s_i` distributions; `SIGMA_MEAS` 4 cm and the 2 % outlier rate; `MISS_P` 0.25 and the gap-clustering `+0.35`; `DELAY_MEAN`, Gamma shape, inspection hit-rates, `season_factor`; `Tb` transferred to the SP-021 sward; the DM→height bulk density implicit in `R0` |

**The load-bearing anchors (nível cut-offs, ~50 cm trigger, −35 % drought effect, sigmoid regrowth)
have literature support. The dynamics glue (rates, normalisers, noise, heterogeneity) is
hypothesis — swept, and disclosed here.**

---

## OOD holdout — a different mechanism, not just a different seed

The out-of-distribution holdout (`--scenario ood`) changes **parts of the mechanism**, all kept
biologically plausible:

| Change | Baseline | OOD | Why plausible |
|---|---|---|---|
| **Hydric response** | logistic in `def30` (smooth saturation), floor `W_MIN = 0.65` | **piecewise-linear** in `def30`, harsher floor `W_MIN = 0.45` (still inside the Phase 1 0.4–1.0 range), different `DEF_SCALE` | a drier soil / shallower rooting stretch would feel deficit more sharply and more linearly |
| **Trecho-effect distribution** | unimodal `LogNormal`, `CV_m = 0.25` | **bimodal** 50/50 mixture of a "slow" and a "fast" sub-population (similar overall spread) | a stretch straddling two soil types / land uses |
| **Maintenance regime** | `DELAY_MEAN = 14 d`, `trig0 = 50`, year-round campaigns | `DELAY_MEAN = 25 d`, `trig0 = 58`, **no roçadas in Jun/Jul/Aug** (winter freeze) | a contractor with fewer crews and a winter stand-down |
| **Regrowth curve** | logistic approach to plateau, `LAG = 8 d` | **monomolecular / Mitscherlich** approach to plateau, `LAG = 13 d` | a different sward with slower canopy closure after cutting |

Everything else (weather, physical caps, roçada-as-event, emergent seasonality, noise separation,
provenance) is unchanged. Heights still ∈ [0, 150], daily growth still ∈ [0, 5] cm, `roçada` still
resets. Result: OOD has more Nível 3, longer regrowth tails, and a between-`trecho` distribution a
model tuned on the baseline will not have seen.

---

## 11. Post-QC calibration adjustments (audit trail)

The mechanism above was specified first. The first generated run was then QC'd (Part B §7) and a
few `hypothesis` constants were adjusted for **operational / biological realism** — not for any
downstream metric. The exact resolved parameter set and `generator_params_hash` of the shipped
datasets are in each `data/synthetic/<name>/manifest.json`.

| Constant | First pass | Adjusted | Why |
|---|---|---|---|
| `R0` (logistic) | 0.11 /day | **0.085** /day | first pass: corridor at ~48 % Nível 3, median cut height ~78 cm — i.e. maintenance chronically ~1 month behind DNIT's 50 cm trigger. Slower growth → more time in Nível 1/2. |
| `DELAY_MEAN` (baseline) | 14 d | **8 d** | same cause — a faster trigger→cut response brings the median cut height down toward the 50–68 cm band. |
| `R0` for the OOD **monomolecular** curve | (used logistic 0.11) | **0.020** /day (new `R0_OVERRIDE`) | `dH ∝ (Hmax−H)` is large early, so it saturated the 5 cm/day physical cap constantly (~13 cm/day uncapped) → grass exploded, 81 % Nível 3, 4 implausible jumps. The rate constant for a `(Hmax−H)` form must be ~5× smaller than for the `H·(1−H/Hmax)` form. |
| OOD winter freeze | Jun–Aug | **Jul only** | Jun–Aug stand-down stacked with the other 3 OOD changes made OOD a caricature; July-only keeps it a genuine distribution shift. |
| quality draws (`DEGRADED_P`, `COVERAGE_*`) | not-ready ≈ 8 % | not-ready ≈ **19 %** | first pass under-shot the `not_ready_fraction` = 0.20 target of §8. |

After adjustment: baseline ~40 % Nível 3, median cut ~67 cm, not-ready ~19 %; OOD ~63 % Nível 3,
median height ~39 cm; 0–1 implausible jumps per 17 k rows. **No parameter was moved to improve a
model's future score.**

## What Part B produces

- `data/synthetic/main/` — seed 42, baseline scenario
- `data/synthetic/seed2/` — seed 1337, baseline scenario
- `data/synthetic/ood/` — seed 2024, OOD scenario
- each: `height_observations.csv`, `rocada_events.csv`, `manifest.json`
  (`kind = synthetic_heights`, `is_synthetic = 1`, `generator_version`, `seed`,
  `generator_params_hash`)
- `data/synthetic/qc/qc_synthetic_<name>.json` — the §7-of-Part-B QC battery
- `reports/figures/synthetic/*.svg` — watermarked sanity figures

Reviewed: 2026-09-09.
