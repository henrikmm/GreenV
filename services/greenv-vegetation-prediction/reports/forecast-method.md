# Forecast method (Phase 9) — individual prediction intervals for days-to-30cm

**Phase 9 artifact of [`docs/VEGETATION_PREDICTION_TASK.md`](../../../../docs/VEGETATION_PREDICTION_TASK.md).**
Everything here is synthetic (`provenance=synthetic`, same corpus as Phases 4-8). This document
describes the individual-prediction uncertainty interval added on top of the Phase 7/8 frozen
Random Forest model — it does **not** re-derive, re-tune, or contradict anything Phase 8 already
measured about that model's point-accuracy. Reproducing entry point:
`scripts/calibrate_and_evaluate_intervals.py`. Full numbers:
`data/models/interval_calibration.json` (frozen calibration) and
`data/models/phase9_interval_evaluation.json` (TEST coverage, OOD diagnostic, sanity checks).

## 1. What `days_until_critical` means, operationally

> "Quantos dias, a partir da data atual, até a vegetação atingir o limite crítico de 30 cm,
> segundo o modelo, **assumindo ausência de uma nova intervenção conhecida que resete o
> crescimento** (roçada, ou qualquer outra)."

Concretely, in order of precedence:

| Condition | `status` | `days_until_critical` | `interval` |
|---|---|---|---|
| `height_cm` (anchor) missing | `insufficient_data` | `null` | `null` |
| `height_cm >= 30` | `critical` | `0.0` | `{lower_days: 0, upper_days: 0}` (deterministic) |
| model's own point estimate exceeds the 120-day horizon | `beyond_horizon` | `null` | `null` |
| otherwise | `forecast` | finite | finite, clamped to `[0, 120]` |

**A roçada the model does not already know about can invalidate this projection.** No future
roçada is ever a feature (`feature_spec.py`'s `EXCLUDE_LEAKAGE`/frozen `KEEP`/`CANDIDATE` lists
have no such column, by design since Phase 5) — the forecast is conditional on "no further cut",
stated here rather than left implicit.

`insufficient_data` is deliberately narrow: it fires only when the anchor `height_cm` itself is
missing. Every other KEEP/CANDIDATE feature already has a documented, TRAIN-only imputation rule
from Phase 7 (`ml_common.FeaturePipeline`) — reusing it for a missing weather or history feature is
an established, defensible approximation; inventing the anchor height itself would not be.

## 2. Uncertainty method: split conformal prediction, and why

**Chosen: split conformal regression** (Vovk, Gammerman & Shafer 2005; Lei et al. 2018) — the
method this phase's own instructions named as the preference, and it fits the frozen model's
structure without any change to it.

> **On the strength of the coverage claim — read this before trusting the theory, not just the
> numbers.** Split conformal's textbook guarantee needs the calibration residuals and the
> test-time residual to be **exchangeable** (informally: draws from the same distribution, in any
> order). Two things in this specific setup make that assumption less than fully satisfied, and
> both are named here rather than left implicit:
> 1. **VALIDATION was not an untouched holdout with respect to the model.** Phase 7 used
>    VALIDATION to *select* the frozen Random Forest's hyperparameters and feature set. The
>    calibration residuals computed here therefore come from the same split that helped choose
>    the model being calibrated — a mild, textbook-recognized violation of the "fully independent
>    calibration set" ideal (the model was not *fit* on VALIDATION, but it was *chosen* using it).
> 2. **The data has temporal structure**, and Phase 8's own rolling-origin backtest already found
>    real, non-trivial temporal instability in this model (height MAE ranged 7.3–13.7 cm across
>    origins). Exchangeability is a distribution-free, order-free assumption; a model whose error
>    drifts over time is in tension with it, even when the calibration and test residuals are
>    computed on non-overlapping date ranges (as they are here).
>
> **What this means in practice:** the numbers in §5 below are **empirically measured coverage on
> TEST**, not evidence that the textbook guarantee holds *exactly*. They happen to land close to
> nominal (§5), which is reassuring, but that is an **empirical observation about this one dataset
> and this one split**, not a formal proof that the assumptions were satisfied. This report does
> not claim a finite-sample, distribution-free coverage guarantee for this prototype — it claims a
> specific method (split conformal), a specific calibration procedure, and a specific measured
> result, and asks the reader to weigh the theory's assumptions accordingly. **The intervals were
> not narrowed or widened after this correction — this section only fixes how the guarantee is
> described, not the numbers themselves**, which were already frozen before TEST was read.

Given a calibration sample's absolute residuals `{|yᵢ - ŷᵢ|}`, the `(1-α)`-quantile band is

```
q = the ⌈(n+1)(1-α)⌉-th smallest |residual|
interval = [point - q, point + q]
```

Under exchangeability between the calibration residuals and a new point's residual, this
construction targets **marginal** `(1-α)` coverage — see the callout above for why that assumption
is only approximately, not exactly, satisfied here, and why §5's measured TEST coverage is the
number to trust, not the theorem by itself. It requires no new training, no distributional
assumption on the residuals, and adds a single scalar (`q`) per confidence level and per target —
about as simple as an interval method gets while still being a real, citable method rather than an
ad hoc rule of thumb.

**Why not condition the band on Nível/season/etc. (locally-weighted or grouped conformal)?** The
instructions explicitly asked to try the **global** band first and only add group-conditional
bands "se houver amostra suficiente e justificativa clara" — not to chase perfect coverage across
many variations. The global band's TEST coverage (below) already lands close to nominal in every
stratum checked, so a conditional band was not pursued; this is recorded as a decision, not an
oversight.

**Why the interval is symmetric and not asymmetric/skewed:** the plain split-conformal band is
symmetric by construction. `days_until_30cm`'s residual distribution is very unlikely to be
symmetric (slow-growing trechos have more room to be very wrong on the high side than the low
side, since `days_until_critical >= 0`), which is exactly why the lower bound needs its own clamp
(§3) — the interval construction does not pretend the underlying error is symmetric, it just uses
a symmetric raw band and then clips it into the physically valid range.

**Why not quantile regression or a bootstrap ensemble:** both would need retraining variants of
the frozen Random Forest (multiple quantile forests, or many bootstrap-resampled refits) —
exactly the kind of "método desnecessariamente complexo" and re-touching-the-frozen-model this
phase was told to avoid. Split conformal needs no retraining at all: it only needs the *already
-fitted* model's residuals on a held-out split.

## 3. Confidence levels, clamping, and the forecast object

Two nominal levels implemented: **80%** (`α=0.20`) and **90%** (`α=0.10`, the primary product
level). For a `forecast`-status row:

```
lower_days = max(0, point - q)
upper_raw  = point + q
upper_days = min(120, upper_raw)
upper_is_horizon_bound = upper_raw > 120     # true upper could be beyond 120; not a precise bound
```

`lower_days` is **never negative** (clamped) and `upper_days` is **never above 120** when the
status is `forecast` (clamped, with the clamp itself flagged via `upper_is_horizon_bound` rather
than silently hidden — see sanity check F). `height_cm >= 30` always yields exactly `0.0` days with
a trivial `(0, 0)` interval, never routed through the model or the conformal band at all.

### The forecast object

```json
{
 "trecho_id": "SP-021:norte:000000",
 "as_of_date": "2025-11-23",
 "current_height_cm": 15.96,
 "critical_height_cm": 30.0,
 "operational_status": "ready",
 "blockers": null,
 "days_until_critical": 27.0,
 "interval": {"lower_days": 0.0, "upper_days": 61.6, "confidence": 0.9, "upper_is_horizon_bound": false},
 "status": "forecast",
 "reason": null,
 "operational_confidence": "medium",
 "model": "random_forest__keep_plus_candidate__framing_b",
 "data_provenance": "synthetic"
}
```

(Real example from TEST, `scripts/calibrate_and_evaluate_intervals.py` output — this specific
row's interval is unusually wide because the global q90 = 34.6 days dominates for a trecho this
far from the threshold; see §9's honest reading of interval widths.) No `generator_seed`,
`dataset`, or other administrative column is ever included — only what `build_days_forecast`
(`src/greenv_vegpred/forecast/interval.py`) explicitly assembles.

### `interval.confidence` vs. `operational_confidence` — two different questions, never merged

- **`interval["confidence"]`** (0.80 or 0.90) is the **statistical nominal coverage level of the
  method** — identical for every row evaluated at that level, a property of the conformal band,
  not of any individual trecho's data quality. Maps to `prediction.interval_nominal_coverage` in
  `schema.sql`.
- **`operational_confidence`** (`"low" | "medium" | "high"`) is a **per-row judgement of how much
  to trust this row's own source observation** — derived only from `operational_status` (and,
  structurally, from whether a model was even needed at all):
  - `operational_status == "not-ready"` → **always `"low"`**, regardless of `status` or how narrow
    the interval looks — matching `schema.sql`'s own `prediction` table CHECK constraint,
    `operational_status <> 'not-ready' OR confidence = 'low'`, verbatim.
  - `status == "critical"` (from a `ready` source) → `"high"` — no model uncertainty is even
    involved; the anchor observation alone already answers the question.
  - Every other `ready`, `forecast`-status row → `"medium"`.
  This is a small, fixed, three-branch, fully-documented rule (`_operational_confidence` in
  `interval.py`) — not a learned or opaque score, and not something item 1 of this pass's
  ranking rule was allowed to reintroduce as a hidden second ranking key (the ranking uses
  `status` and `days_until_critical` only — see §11).

**A `not-ready` row keeps its point estimate and interval** (the frozen model and its VALIDATION
calibration already saw a real mix of `ready`/`not-ready` rows — 583 of 3 282, 17.8%, in the actual
calibration population used, see §4) — suppressing the number outright would have been a new,
undiscussed behaviour change. What changes is only `operational_confidence`. Concretely, from a
real VALIDATION row:

```json
{
 "trecho_id": "SP-021:norte:000000", "as_of_date": "2024-01-28", "current_height_cm": 14.76,
 "operational_status": "not-ready", "blockers": ["depth-degraded"],
 "days_until_critical": 23.1,
 "interval": {"lower_days": 0.0, "upper_days": 57.7, "confidence": 0.9, "upper_is_horizon_bound": false},
 "status": "forecast", "reason": null,
 "operational_confidence": "low"
}
```

`interval.confidence` stayed `0.9` (the method didn't change); `operational_confidence` is `"low"`
(the source did). A consumer that only reads one of the two fields will misread this row —
that is exactly why both are exposed, under unambiguous, differently-named keys.

## 4. Calibration

| | Value |
|---|---|
| Calibration split | **VALIDATION only** (n=5 500 rows total; see restriction below) |
| Population restriction (days) | `height_cm < 30` at the anchor AND non-censored, known truth (n=3 282) |
| Excluded from calibration | Censored VALIDATION rows (unknown true value — a residual against an unknown number cannot be computed); 0 rows excluded for the model's own point estimate sitting at the 120-day cap (none did) |
| Method | Split conformal, symmetric absolute-residual band |
| `q` (days, 80%) | **24.71** |
| `q` (days, 90%) | **34.57** |
| Height `q` (cm) | +7d: 13.27 (80%) / 27.13 (90%); +14d: 17.40 / 28.17; +30d: 21.75 / 29.18 — n=4 622/4 306/4 172 |
| Model frozen from | Phase 7 (`random_forest__keep_plus_candidate`, `n_estimators=300, max_depth=None, min_samples_leaf=20, random_state=42`) — unchanged |
| Feature spec version | `weather-v1+features-v1` |

No temporal leakage: VALIDATION (2025-02 → 2025-08) is entirely before TEST (2025-10 → 2026-08),
with the same 30-day embargo Phase 5 already established at that boundary — this phase reuses that
boundary, it does not redraw it. A global (not group-conditional) band was used, per §2.

## 5. TEST coverage (frozen interval, measured once)

| Confidence | n | Empirical coverage | Nominal | Mean width (days) | Median width (days) | Not covered |
|---|---:|---:|---:|---:|---:|---:|
| 80% | 4 562 | **83.2%** | 80% | 47.88 | 49.42 | 767 |
| 90% | 4 562 | **90.8%** | 90% | 63.26 | 66.43 | 420 |

Both levels land close to nominal, slightly on the conservative side (over-covering by 3.2 and 0.8
points respectively) — exactly the direction a defensible interval should err on if it errs at
all. 644 TEST rows are `target_days_until_30cm_censored=1` (true value unknown, >120 days) and are
**not** included in this coverage denominator — checking "does the interval cover an unknown
number" is not a computable statement; their count is reported, not folded into the percentage.

**Stratified coverage (90%, TEST):**

| Nível | n | Coverage | | Season | n | Coverage | | Roçada proximity | n | Coverage |
|---|---:|---:|---|---|---:|---:|---|---|---:|---:|
| 0 | 882 | 91.2% | | wet (Oct–Mar) | 2 642 | 93.0% | | recent (<14d) | 1 554 | 88.3% |
| 1 | 1 275 | 87.1% | | dry (Apr–Sep) | 1 920 | 87.8% | | mid (14–60d) | 2 570 | 92.2% |
| 2 | 2 405 | 92.6% | | | | | | long (>60d) | 438 | 91.6% |

Nível 3 has **no rows here by construction** — it means `height_cm >= 30`, which routes to
`status="critical"` and never enters the modelled forecast population at all. Every stratum sits
within ±3 points of the 90% nominal target except Nível 1 and "recent roçada" (both ≈87%) — a mild
under-coverage on the freshly-cut, fast-changing cases, worth watching but not large enough to
justify a group-conditional band per §2's stated bar.

**Height interval coverage (TEST):**

| Horizon | 80% coverage (nominal 80%) | Mean/median width (cm) | 90% coverage (nominal 90%) | Mean/median width (cm) |
|---|---:|---|---:|---|
| +7d | 75.8% | 25.9 / 26.5 | 87.5% | 48.8 / 54.3 |
| +14d | 75.7% | 34.0 / 34.8 | 87.4% | 51.6 / 56.0 |
| +30d | 76.4% | 42.4 / 43.5 | 86.4% | 54.4 / 58.4 |

**Read honestly:** height intervals under-cover by 3–4 points at both nominal levels, at every
horizon — the opposite direction from `days_until_30cm`'s (slightly conservative) result. Likely
cause: VALIDATION and TEST residuals are not perfectly exchangeable (Phase 8's rolling-origin
backtest already found real temporal instability in this model — height MAE swung 7.3–13.7 cm
across origins), so a calibration-period residual quantile understates TEST's true spread. This
is reported as a real, mild limitation, not smoothed over — see §13.

## 6. OOD (diagnostic only — calibration was frozen before this ran)

| Confidence | n | OOD coverage | TEST coverage | Δ |
|---|---:|---:|---:|---:|
| 80% | 6 104 | 87.2% | 83.2% | **+4.0 pts** |
| 90% | 6 104 | 94.3% | 90.8% | **+3.5 pts** |

**This is not presented as robustness without investigating why**, per instruction. Two candidate
explanations were checked:

1. **Composition** (Phase 8's explanation for the point-accuracy "improvement"): 62.7% of OOD rows
   already have `height_cm >= 30` vs. 38.7% on TEST. This does **not** directly explain the
   coverage numbers above, because both TEST's and OOD's coverage denominators here are *already*
   restricted to the same forecast-eligible subpopulation (`height_cm < 30`, non-censored) — the
   composition difference changes how many rows are *outside* this table (routed to `critical`
   instead), not the coverage computed within it.
2. **Residual spread inside that same subpopulation, checked directly**: mean/median/p90 absolute
   residual for `days_until_30cm`, restricted to `height_cm < 30` and non-censored —
   VALIDATION (the calibration source): mean 16.42, median 12.81, p90 34.52 (≈ the frozen q90);
   TEST: mean 15.16, median 11.37, p90 33.19; **OOD: mean 13.92, median 11.40, p90 27.46.** OOD's
   own residuals, within this comparable subpopulation, are genuinely smaller than VALIDATION's —
   which is why a VALIDATION-derived fixed-width band covers OOD *more* often. This looks like a
   real property of the still-growing (<30 cm) slice of the monomolecular OOD mechanism in this
   synthetic setup, not a composition artifact.

**What this does NOT support:** a claim that the interval or the model "transfers well" under
mechanism shift, in general. Phase 8 already found the same frozen model's raw height-prediction
accuracy degrades 22–28% under this same OOD mechanism. This section only says that, restricted to
the specific slice of trechos still below 30 cm, this one holdout's residual spread for
*days-until-30cm specifically* happened to be narrower — a single-dataset diagnostic observation,
not a validated robustness property, and not re-used to touch the frozen calibration in any way.

## 7. Height intervals — implemented (secondary structure)

TASK.md's original Phase 9 text names "+7/+14/+30 heights" as part of the operational output, so
the same split-conformal philosophy was applied to them (§4–§5 above) using the frozen height
model, unchanged. **`days_until_30cm` remains the primary deliverable** — height intervals are
reported on TEST only; an OOD diagnostic for height-interval coverage specifically was not run, to
keep this phase's scope bounded to what was explicitly asked (the `days_until_30cm` OOD check).
Physical bounds enforced: `lower_cm >= 0`, `upper_cm <= 150` (`ml_common.HEIGHT_CLIP`), matching
Phase 4's own synthetic-model hard cap.

## 8. Sanity checks (real VALIDATION/TEST rows and forced overrides, `phase9_interval_evaluation.json` → `sanity_checks`)

| Case | Result |
|---|---|
| A. height 35 cm | `days_until_critical=0.0`, `status="critical"` ✓ |
| B. height 29 cm, strong growth (forced override) | `days_until_critical=14.1`, interval `[0.0, 48.7]`, `status="forecast"` — short point estimate as expected; the interval is still wide because it uses the *global* q90 (34.6 d), not a growth-rate-conditional one (§2) |
| C. height 4 cm, slow growth (forced override) | `days_until_critical=40.4`, interval `[5.9, 75.0]`, `status="forecast"` — distant but not censored |
| D. missing anchor height | `status="insufficient_data"`, `days_until_critical=null`, `interval=null` ✓ — no invented number |
| E. lower-bound clamp | real VALIDATION row, point=11.16, q90=34.57 → raw lower = **-23.41**, clamped interval lower = **0.0** ✓ |
| F. upper-bound horizon handling | real VALIDATION row, point=92.76, q90=34.57 → raw upper = **127.33** (>120), clamped `upper_days=120.0`, `upper_is_horizon_bound=true` ✓ — flagged, not silently truncated |

## 9. Limitations (must-read before using any of this operationally)

- **The `days_until_30cm` intervals are wide — 80% mean width ≈ 47.9 days, 90% mean width ≈ 63.3
  days (§5) — and that is a real limitation of the precision this model+data currently support,
  not an implementation defect.** No attempt was made to narrow these artificially (a
  growth-rate-conditional band, a tighter global quantile chosen after peeking at coverage, etc.)
  at the cost of the coverage already measured in §5. A ≈2-month-wide band around an ≈11-30 day
  point estimate is a genuinely weak operational signal for same-week crew scheduling on its own
  — it is still useful as a ranking input (§11) and as an honest bound, but it should not be read
  as a precise date.
- **Every row in every split — TRAIN, VALIDATION, TEST, OOD — is `provenance=synthetic`.** Nothing
  here is a field-validated guarantee for the SP-021/Rodoanel Oeste corridor.
- **The interval measures the model's own uncertainty inside this simulated methodology.** A 90%
  interval means "in 90% of cases like this one, drawn from the same synthetic generator family,
  the true value falls in this band" — not "there is a 90% real-world chance."
- **This is not a field-validated statistical guarantee.** Split conformal's coverage guarantee is
  marginal and asymptotic in the exchangeability assumption; TEST's own measured coverage (§5)
  already shows small deviations from nominal (over-covering for days, under-covering for height).
- **OOD already showed 22-28% height-MAE degradation under a mechanism change (Phase 8).** The
  interval widths here were calibrated entirely on the in-distribution (VALIDATION) mechanism; §6
  is a diagnostic, not a claim that intervals hold up under a mechanism the model has never seen
  calibrated against.
- **An unknown future roçada is a first-order source of error**, not a tail case — the entire
  `days_until_critical` projection is conditional on no further cut, by construction (§1).
- **Real field data, when it arrives, will require recalibrating these intervals from scratch** —
  the calibration set, the model, and the residual distribution are all specific to this synthetic
  corpus; nothing here transfers automatically to a differently-behaved real signal.

## 10. What Phase 10 can reuse

`src/greenv_vegpred/forecast/interval.py` exposes:
- `build_days_forecast(row, days_model, q_by_confidence, model_name, confidence=0.90) -> dict` —
  the exact object shape in §3, ready to serialize as an API response.
- `build_height_forecast(row, height_model, q_by_horizon_confidence, horizon_days, confidence=0.90) -> dict`
  — the secondary height-interval structure.
- `conformal_quantile(residuals, alpha)` — reusable if Phase 10 (or a future recalibration) needs
  to recompute `q` from a refreshed calibration sample.

Phase 10's API layer only needs to: load the two frozen Phase 7 model artifacts, load
`data/models/interval_calibration.json` for `q_by_confidence` / `q_by_horizon_confidence`, and call
these two functions per request — no model fitting, no calibration logic, at request time.
`rank_forecasts` (§11) is also exported from the same package for the same reason.

## 11. Operational ranking

`src/greenv_vegpred/forecast/ranking.py` → `rank_forecasts(forecasts: list[dict]) -> list[dict]`.
No new score is introduced — the ordering key is exactly:

1. `status == "critical"` — maximum priority (already at/above 30 cm).
2. `status == "forecast"` — ascending `days_until_critical` (soonest first).
3. `status == "beyond_horizon"` — after every finite forecast.
4. `status == "insufficient_data"` — last.

Ties within a bucket break on `trecho_id` ascending (string comparison) — deterministic,
documented, and carries no additional information.

**Reproducible demo** (`scripts/build_current_forecast_batch.py`): for each of the 118 SP-021
trechos, the row with the latest `as_of_date` in `dataset == "main"` (seed=42; `seed2` is excluded
from this one canonical "current state" snapshot only to avoid mixing two alternate simulated
histories for the same `trecho_id`, not because it is invalid data) — built into a forecast object
at the primary 90% confidence level, then ranked. Writes
`data/forecast/ranking_current.json` / `.csv` (118 rows). Status distribution in this snapshot:
**61 `critical`, 57 `forecast`, 0 `beyond_horizon`, 0 `insufficient_data`.**

Top of the ranking (ties broken by `trecho_id`):

| rank | trecho_id | status | days_until_critical | operational_status | operational_confidence |
|---:|---|---|---:|---|---|
| 1 | SP-021:norte:000000 | critical | 0.0 | ready | high |
| 2 | SP-021:norte:001000 | critical | 0.0 | not-ready | **low** |
| 3 | SP-021:norte:001500 | critical | 0.0 | ready | high |

Row 2 shows the ranking rule and the confidence rule interacting exactly as intended: a
`not-ready` source is still ranked by its own `status`/`days_until_critical` (the ranking rule
never looks at `operational_confidence`), but its `operational_confidence` field flags "low" right
next to it — a crew consumer sees both "this is urgent" and "trust this one less" simultaneously,
rather than one silently overriding the other.

`ranking_current.csv` columns: `rank, trecho_id, as_of_date, current_height_cm,
operational_status, days_until_critical, lower_days, upper_days, confidence_level,
operational_confidence, status, data_provenance`. Two separately-named confidence columns are
included on purpose (§3) — a single ambiguous "confidence" column would recreate exactly the
conflation this phase was told to avoid. No `generator_seed`.

## 12. Batch artifact — `schema.sql`'s `prediction` table, checked directly (not assumed)

`src/greenv_vegpred/schema.sql` **already defines a `prediction` table** (Phase 2, lines
356-406) — checked directly before deciding anything, not assumed absent. Its columns already
distinguish `interval_nominal_coverage` (statistical) from `confidence` (operational,
`CHECK (... IN ('low','medium','high'))`) and already enforce
`operational_status <> 'not-ready' OR confidence = 'low'` — i.e., §3's `operational_confidence`
rule is not a new invention here, it is this pre-existing schema's own rule, now actually
implemented in code for the first time.

**No schema migration was made** — the table definition is untouched. This V1 prototype has no
running database anywhere in Phases 1-9 (every phase's output has been CSV/JSON, by established
convention), so "populating the `prediction` table" is realized as a **schema-shaped CSV**,
`data/forecast/prediction_batch.csv` (118 rows, from the same current-snapshot batch as §11),
whose columns map directly onto `prediction`'s:

| `prediction` column | Source in this batch |
|---|---|
| `prediction_id` | a fresh UUID4 per row (schema comment says "UUIDv7"; no UUIDv7 generator exists in the standard library, and adding a dependency for this alone wasn't judged justified — a UUID4 still satisfies the column's role as a unique key, a documented, harmless deviation) |
| `trecho_id`, `as_of_date` | from the source row |
| `model_kind`, `model_version` | `"random_forest"`, `"keep_plus_candidate"` |
| `h_plus_{7,14,30}_cm` (+ `_low`/`_high`) | left blank — this batch only forecasts `days_to_30cm`; §7's height intervals were evaluated but not run into this particular batch export, to keep this demo the same scope as §11's ranking (`days_until_30cm`-focused) |
| `days_to_30cm`, `_low`, `_high` | `days_until_critical`, `interval.lower_days`, `interval.upper_days` |
| `days_to_30cm_censored` | `1` iff `status == "beyond_horizon"` |
| `interval_method` | `"conformal"` |
| `interval_nominal_coverage` | `0.9` (the statistical level — §3) |
| `confidence` | `operational_confidence` (the operational level — §3), **not** the statistical one |
| `operational_status`, `blockers` | from the source row, verbatim |
| `input_data_source`, `dataset_is_synthetic` | `"synthetic"`, `1` — always, for this whole project |
| `source_observation_id` | left blank — there is no `height_observation` table behind this CSV-based V1 pipeline to reference |

Rows with `status == "insufficient_data"` are **excluded** from this export (0 in the current
118-trecho snapshot) — there is no source `height_cm` to build a `prediction` row from at all; a
row with every numeric field blank would not be a meaningful prediction record.

## 13. Weather-forecast input — task closed by architectural decision, not left open

TASK.md's original Phase 9 text asked for a "weather-forecast input" (climatological normals
and/or a short real forecast) feeding a forward-simulation to the 10 cm/30 cm thresholds. Checked
against what was actually built:

**The frozen V1 model (framing B, direct regression) does not consume a forward weather
trajectory at all.** It regresses `days_until_30cm` directly from the anchor row's own
already-observed features (current height, history, current-week weather aggregates) — there is
no forward-simulation loop for a weather forecast to feed into. The one framing that *did* walk
forward step-by-step (the recursive height model, Phase 7 addendum) was evaluated against the
direct framing and found strictly worse at every horizon beyond +7d (`reports/hyperparameters.md`
§6.2) — reopening it here, only to have something for a weather forecast to feed, would mean
reintroducing an already-rejected, worse-performing framing purely to satisfy a text mismatch.

**Decision: this task is closed as "not applicable to the selected V1 primary framing; a future
weather trajectory belongs to the secondary trajectory model (framing A) or a V2 recursive
redesign, neither of which is the frozen production path."** Concretely:
- The frozen model was **not** altered to force a weather-forecast input it cannot use.
- No inert weather-forecast field was added that no prediction actually depends on.
- The recursive framing was **not** reinstated to manufacture a place for this input.

Framing A (`HeightTrajectoryDaysModel`, already implemented and evaluated as a secondary analysis
in Phase 7/8) is the one place in this codebase that *does* consume a multi-step height trajectory
internally — but even it derives that trajectory from the frozen height model's own +7/+14/+30
predictions, not from an external weather forecast. If a real forward weather input is wanted, it
belongs there, in a future pass explicitly scoped to it — not retrofitted onto the frozen V1
primary model in this pass.
