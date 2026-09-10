# Phase 7 — candidate ML models: features, preprocessing, hyperparameters

Source data: `data/features/feature_row_dev.csv`, split by the Phase 5 temporal + blocked-by-trecho
scheme. This phase used **only** `train` (n=18,486) for fitting and **only** `validation`
(n=5,500) for model selection and reporting. `test` (loaded, but never scored) and the `ood` file
(never opened) were not used anywhere in this phase — see confirmation in the final report.

Frozen feature spec: `src/greenv_vegpred/features/feature_spec.py` (Phase 5). `true_height_cm` is
absent from every feature list and from every target; it never entered any model.

## 1. Feature sets evaluated

Two sets only, both validated against `feature_spec.assert_no_leakage()`:

- **`keep`** — the frozen Phase 5 `KEEP` list (9 columns): `height_cm`, `height_prev_cm`,
  `weekly_growth_cm`, `days_since_rocada`, `gdd_week_tb15_c`, `tmin_week_c`, `rain_30d_mm`,
  `water_deficit_30d`, `operational_status`.
- **`keep_plus_candidate`** — `keep` plus a small, justified extension from `CANDIDATE`:
  `height_lag2_cm` (second growth lag — tests whether one more lag helps beyond `height_prev_cm`),
  `rocada_in_new_cycle` (flags the unstable first days after a cut, where `height_prev_cm` is
  `NULL`-derived and less informative), `dry_season_flag` (coarse seasonal signal, cheaper than
  raw calendar month), `water_deficit_90d` (longer-window hydric memory alongside the 30-day one),
  `vegetation_type` (categorical, tests whether species composition matters once weather/roçada
  timing are already in the model).

No opportunistic search over dozens of combinations was run — only these two sets, per instruction.

## 2. Preprocessing (`src/greenv_vegpred/models/ml_common.py`)

- **Numeric imputation**: per-column **median learned on `train` only**
  (`FeaturePipeline.fit`), applied identically to `validation`. Never fit on validation/test/OOD.
- **Categorical encoding**: one-hot, vocabulary (categories) learned on `train` only. Any category
  unseen in `train` — including at prediction time — maps to an all-zero row (implicit
  `unknown`/`missing` bucket), rather than raising or silently dropping the row.
- **Scaling**: `StandardScaler`, fit on `train` only, applied **only** to the linear model (Ridge).
  Tree-based models (Random Forest, HistGradientBoosting) use raw feature values — scale-invariant
  by construction.
- **Leakage guards preserved from Phase 5**: `trecho_id`, `dataset`, `generator_seed`, `provenance`,
  and all administrative identifiers are in `feature_spec.EXCLUDE_LEAKAGE`/`METADATA` and were never
  passed into any `FeaturePipeline`.

## 3. Models implemented

Per the simplified Phase 7 instruction actually given for this run, three families (not the
TASK.md's original four-family text, which anticipated a separate OLS/Lasso/recursive framing —
see the honest scope note in the final report):

| File | Family | Notes |
|---|---|---|
| `src/greenv_vegpred/models/linear.py` | Ridge | `alpha=0.01` stands in for near-unregularised OLS; `alpha=1.0`/`10.0` add real shrinkage. Standardised inputs. |
| `src/greenv_vegpred/models/random_forest.py` | Random Forest | `sklearn.ensemble.RandomForestRegressor`, `n_jobs=-1`, no scaling. |
| `src/greenv_vegpred/models/gradient_boosting.py` | Gradient Boosting | `sklearn.ensemble.HistGradientBoostingRegressor` — chosen over XGBoost/LightGBM to stay pip-only, per TASK.md's own preference; no concrete need for a native alternative arose. |

Each family exposes two constructors in `ml_common.py`:

- `SklearnHeightModel` — fits **one independent regressor per horizon** (7/14/30 days), i.e. the
  **direct** per-horizon framing, not a recursive one-step-ahead model. This was a deliberate choice
  to avoid multi-step error accumulation (a risk TASK.md itself names); the recursive framing was
  not implemented in this simplified pass.
- `SklearnDaysUntil30Model` — implements framing **(B)** from TASK.md (predict
  `days_until_30cm` directly), **not** framing (A) (derive it from the height curve). Framing (A)
  was not implemented in this pass; TASK.md lists both framings as something to "record", and only
  (B) was built.

### Censoring treatment for `days_until_30cm`

Some `trechos` do not reach 30 cm within the observation window (right-censored target). Rather
than building a full survival model (Cox, AFT, etc.) — which the instruction explicitly said not to
invent without necessity — censored rows are **capped at `DAYS_HORIZON = 120` days** and kept in
training as `120`, instead of being dropped. This is a documented simplification: it treats
"more than 120 days" as "120 days" for fitting purposes, which biases the regressor toward
under-estimating the true (unobserved, always ≥120) wait for the slowest-growing `trechos`, but
keeps every row usable and avoids selection bias from dropping the hardest cases outright. At
prediction time, any output over 120 is reported back with `censored: True` in the metadata rather
than a bare number.

## 4. Hyperparameter grids (chosen on `validation` only)

| Model | Grid dimension | Values tried |
|---|---|---|
| Ridge | `alpha` | `0.01`, `1.0`, `10.0` |
| Random Forest | `(n_estimators, max_depth, min_samples_leaf)` | `(100,6,20)`, `(100,None,5)`, `(300,6,5)`, `(300,None,20)` |
| HistGradientBoosting | `(learning_rate, max_iter, max_leaf_nodes)` | `(0.05,100,15)`, `(0.05,300,31)`, `(0.10,100,31)`, `(0.10,300,15)` |

Selection rule (`scripts/train_and_evaluate_ml.py`): for height, the config with the lowest **mean
validation MAE across the three horizons** is chosen; for `days_until_30cm`, the config with the
lowest validation MAE (days) among non-censored rows is chosen. All grids are small, explicit, and
enumerated — no randomized or exhaustive search.

### Chosen configuration per model × feature set

| Task | Feature set | Ridge (chosen `alpha`) | Random Forest (chosen `(n,depth,leaf)`) | HistGB (chosen `(lr,iter,leaves)`) |
|---|---|---|---|---|
| height | `keep` | `1.0` | `(300, None, 20)` | `(0.05, 300, 31)` |
| height | `keep_plus_candidate` | `0.01` | `(300, None, 20)` | `(0.1, 100, 31)` |
| days_until_30cm | `keep` | `0.01` | `(300, None, 20)` | `(0.05, 300, 31)` |
| days_until_30cm | `keep_plus_candidate` | `0.01` | `(300, None, 20)` | `(0.05, 300, 31)` |

Full per-trial validation scores are recorded in `data/models/ml_validation_metrics.json` →
`selection`.

## 5. Reproducibility

- `random_state=42` fixed on every estimator (Ridge, RandomForest, HistGradientBoosting).
- Feature spec version tag `weather-v1+features-v1` recorded in `models/artifacts/manifest.json`.
- `train`/`validation` row counts (18,486 / 5,500) recorded in both the manifest and the metrics
  JSON, so a re-run can be checked for drift.
- Fitted artifacts: `models/artifacts/{height,days_until_30cm}__{ridge,random_forest,gradient_boosting}__{keep,keep_plus_candidate}.joblib`
  (12 files), each a `functools.partial`-based estimator factory + fitted `FeaturePipeline` +
  fitted estimator(s), picklable and reloadable via `joblib.load`.
- `scripts/train_and_evaluate_ml.py` is the single reproducing entry point: run against the same
  `feature_row_dev.csv` and it regenerates the same selections (deterministic seeds throughout).

---

## 6. Addendum — closing three items from TASK.md's original Phase 7 text

The first Phase 7 pass (sections 1–5 above) deliberately scoped down TASK.md's original text to a
simpler, explicitly-instructed shape. This addendum closes the three gaps that were left open,
**without retraining or reselecting anything already valid**: it reads the existing Ridge/Random
Forest numbers straight out of `data/models/ml_validation_metrics.json` and reuses the existing
`height__random_forest__keep.joblib` / `height__random_forest__keep_plus_candidate.joblib`
artifacts as-is. New fitting is limited to OLS and Lasso. Reproducing entry point:
`scripts/train_and_evaluate_ml_addendum.py`; results:
`data/models/ml_validation_metrics_phase7_addendum.json` (new file, does not overwrite or alter
the original `ml_validation_metrics.json`).

### 6.1 OLS vs Ridge vs Lasso (same preprocessing, same frozen feature set)

Compared on `keep_plus_candidate` only — the feature set behind the currently frozen Random
Forest recommendation, so this directly answers "does the linear family have anything better to
offer *there*", rather than re-running the two-feature-set sweep already done for Ridge.
`LinearRegression` (true OLS, no hyperparameter) and `Lasso` (`alpha` grid `[0.001, 0.01, 0.1]`,
`max_iter=5000`, chosen on VALIDATION) were added to `linear.py` alongside the existing Ridge.

| | avg height MAE (7/14/30d) | days_until_30cm MAE |
|---|---|---|
| OLS | 14.709 | 18.48 |
| Ridge (`alpha=0.01`, already chosen) | 14.709 | 18.48 |
| Lasso (`alpha=0.1` chosen for height, `0.1` for days) | 14.707 | 18.45 |
| *(reference)* Random Forest `keep_plus_candidate` | **11.369** | **11.80** |

**Verdict: OLS and Lasso add essentially no value over Ridge** (differences of ≤0.03 cm / ≤0.03
days — noise, not a real improvement), and none of the three linear models comes anywhere close
to Random Forest. This is expected given the height @+7d comparison already available in section
5 of the original pass: Ridge's own `alpha` sweep there was already flat (`14.686` at all three
values on the `keep` set), meaning the problem's non-linearity — not the regularisation strength —
is the limiting factor for every member of the linear family. **The frozen Random Forest choice
is unchanged.**

### 6.2 Direct vs. recursive one-step height model

**Before implementing:** the recursive model steps 7 days at a time, reusing the *already-fitted*
`height__random_forest__keep.joblib` 7-day submodel (`src/greenv_vegpred/models/recursive.py`) —
no new training. Between steps, every covariate is handled by one explicit, documented rule, and
no real future information is ever used:

- `height_cm` / `height_prev_cm` / `weekly_growth_cm` / `days_since_rocada` update mechanically
  from the model's **own prior-step prediction** (never the true future height, never
  `true_height_cm`).
- `days_since_rocada` advances by exactly `+7` per step, under the explicit assumption that **no
  roçada occurs during the projected window** — the future roçada schedule is exactly the kind of
  real future information this pass was told not to use. This is the recursive model's main
  documented limitation: a trecho actually roçado inside the +14d/+30d window will see its
  projection diverge from the truth.
- `gdd_week_tb15_c`, `tmin_week_c`, `rain_30d_mm`, `water_deficit_30d`, `operational_status` are
  **frozen at the anchor row's own values** for every projected step ("persistence of climate" —
  the same simplification already used for the Phase 6 mechanistic baseline's GDD multiplier, not
  a new kind of approximation).
- Only the `keep` feature set is used (not `keep_plus_candidate`): recursively tracking a second
  lag, a cycle-start flag that itself depends on the never-simulated future roçada, and a
  calendar-derived season flag would add bookkeeping without changing the methodological question.

**Result (VALIDATION, same algorithm + feature set, direct vs. recursive):**

| Horizon | Direct MAE | Recursive MAE | Recursive worse by |
|---|---|---|---|
| +7d | 9.515 | 9.515 | 0.0 cm (identical — one step both ways, a correctness sanity check) |
| +14d | 11.385 | 12.467 | +1.08 cm (+9.5%) |
| +30d | 13.960 | 17.933 | +3.97 cm (+28.5%) |

Sanity check on the +30d recursive predictions: 0 negative, 0 over 150 cm (min 8.47, max 62.99) —
each step reuses the same clipped direct submodel, so the physical bounds hold by construction.

**Verdict: the recursive model is worse than the direct one at every horizon beyond +7d, and the
gap widens with the horizon** — exactly the "recursive multi-step error accumulation" risk
TASK.md itself names. **The direct per-horizon model is NOT replaced.**

### 6.3 Framing (A) — days-until-30cm derived from the height trajectory

**Before implementing:** framing (A) reuses the *already-fitted*
`height__random_forest__keep_plus_candidate.joblib` height model
(`src/greenv_vegpred/models/height_trajectory_days.py`) — no new training. It never reads real
future weather, future roçada, or `true_height_cm`; it only ever calls that height model's own
`+7d`/`+14d`/`+30d` predictions. Rule, in order:

1. `height_cm` already ≥ 30 cm at the anchor row → **0 days**.
2. Piecewise-**linear interpolation** across the four known points (day 0 = current height; days
   7/14/30 = the height model's own predictions) finds the day inside `[0, 30]` where the curve
   first reaches 30 cm — the well-supported part of framing (A).
3. If the curve has not reached 30 cm by day 30, **one linear extrapolation** of the day-14→day-30
   segment projects forward, capped at the same 120-day horizon framing (B) uses — the most
   speculative part: a straight line drawn past the model's only two informative points, on a
   process (Phase 4's logistic/monomolecular growth) known to *decelerate*, not stay linear, as
   height approaches its ceiling. Only attempted while that segment's slope is still positive.
4. A non-positive last-segment slope, or an extrapolated day past 120, is reported as **censored**
   — the same convention framing (B) uses.

**Result (VALIDATION):**

| | MAE (days) | median abs. error | %±3d | %±7d | n scored / not-censored |
|---|---|---|---|---|---|
| Framing (A) — height trajectory | 13.75 | **6.29** | **39.6%** | **55.8%** | 5093/5237 |
| Framing (B) — direct regression (frozen) | **11.80** | 7.51 | 38.0% | 48.7% | 5237/5237 |
| Mechanistic baseline (Phase 6) | 14.39 | 6.54 | 41.3% | 55.0% | 4755/5237 |

Method distribution on the 5,237 non-censored validation rows: 1,955 already above threshold;
2,070 cleanly interpolated; 1,068 extrapolated (accepted); 135 extrapolated past the 120-day
horizon (reported censored); 9 with a non-increasing tail (reported censored). 144 rows where
framing (A) predicted censored but the truth had an answer (vs. 0 for framing B — framing B never
refuses an answer on a non-censored row). 0 negative day predictions.

**Reading this honestly, not the way that flatters the newer piece:** framing (A)'s **mean** MAE
(13.75) is worse than framing (B)'s (11.80) — the 1,068 extrapolated cases and the 135 pushed past
the horizon carry the large errors that pull the mean up, exactly the weak point predicted in the
design. But framing (A)'s **median** (6.29) and its **%±3d/%±7d** (39.6% / 55.8%) are actually
*better* than framing (B)'s (7.51 / 38.0% / 48.7%) — a heavy-tailed error distribution: framing (A)
is typically at least as precise as framing (B) on the bulk of rows, and worse only on the tail
where it has to extrapolate. This nuance is recorded here rather than collapsed into a single
"worse" verdict.

**Verdict: framing (B) (direct regression) remains the better choice by mean MAE and by full
coverage (it never refuses an answer), and stays the frozen configuration for Phase 8.** Framing
(A) is not discarded as worthless — it is a legitimate, fully-derived-from-existing-models second
opinion whose typical-case precision is competitive — but it is not adopted as the primary
approach given TASK.md's own preference for the simpler, directly-validated framing when both are
viable.
