# Tests (Phase 11) — verifying what Phases 1-10 already built

**Current total: 197 tests** (146 as of this phase's own completion below, historical and
unchanged; +11 from the pre-push security hardening pass → 157; +11 from the B1 target-construction
remediation → 168; +4 from a B1.1 microfix regression → 172; +17 from the B2 days-model-policy
remediation → 189; +8 from the B2.1 rolling-origin label-window-leakage fix → 197 — see
`reports/api.md`'s hardening section and `reports/target-construction.md`). The narrative below
describes Phase 11's own moment and its own count (146) — read it as history, not as the current
total.

**Phase 11 artifact of [`docs/VEGETATION_PREDICTION_TASK.md`](../../../../docs/VEGETATION_PREDICTION_TASK.md).**
This phase adds **only** tests. No model was retrained, no interval was recalibrated, no
hyperparameter/feature/metric from Phases 6-10 changed, and no HTTP contract was altered to make
it easier to test. Where a test revealed something surprising, it is classified and documented
below before any fix — see §"Bugs found".

## 1. Strategy

Pytest, the framework named by this phase's own instruction, added as a `test` extra in
`pyproject.toml` (`pytest`, `pytest-cov`, `httpx` — the last one only because FastAPI's
`TestClient` needs it as its HTTP transport since Starlette ≥0.27, not a separate choice made
here). No other test framework or lint tool was added — `ruff`/format-checking, named in
TASK.md's original text, were not installed or run, since this phase's actual instruction never
asked for them and adding lint tooling now would be new, unrequested scope.

Categories, one file per concern (not one giant file):

| File | Covers |
|---|---|
| `test_schema.py` | `schema.sql`, executed for real against a fresh, temporary SQLite database |
| `test_classification.py` | `vegetation_level` (Nível 0-3) boundary matrix |
| `test_trecho_meta.py` | `parse_trecho_id` |
| `test_features.py` | Feature-spec leakage guarantees + roçada-cycle causality in the real builder |
| `test_forecast.py` | `build_days_forecast` (all 8 required cases) + `conformal_quantile` |
| `test_ranking.py` | `rank_forecasts` ordering rule |
| `test_api.py` | Every HTTP endpoint, the model-absent scenario, and a leak/error-shape check |
| `test_openapi.py` | `app.openapi()` structure (no full-YAML snapshot diff — see §15 below) |
| `test_model_contract.py` | The small, git-committed model artifacts + an optional RF check |
| `test_pipeline_smoke.py` | Snapshot → loader → service → HTTP, end to end |

## 2. Command and results

```
cd services/greenv-vegetation-prediction
python -m pytest            # or: pytest
python -m pytest --cov=greenv_vegpred.forecast --cov=greenv_vegpred.api \
    --cov=greenv_vegpred.features.feature_spec --cov=greenv_vegpred.features.build \
    --cov-report=term-missing
./scripts/verify.sh         # pytest + OpenAPI-in-sync check
```

| Run | Passed | Skipped | Failed | Time |
|---|---:|---:|---:|---:|
| 1st (before the fix below) | 146 | 0 | 0 | 3.56s |
| Targeted re-run of the RF determinism test alone | 0 | 0 | **1** | 0.91s |
| 2nd full run (after the fix) | 146 | 0 | 0 | 3.35s |
| 3rd full run (after the fix) | 146 | 0 | 0 | 3.26s |
| RF determinism test alone, ×5 (after the fix) | 5 | 0 | 0 | ~0.76s each |

**146 tests, 0 skipped in this environment** (the large Random Forest artifact happens to be
present here because Phase 7/8's training run was done locally; see §16 for what "skipped"
depends on). 0 failed after the one fix described below.

## 3. `schema.sql` — a real, fresh SQLite database (item 4)

Closes a Phase 2 gap named explicitly for this pass: `schema.sql` was written in Phase 2 but had
never actually been executed. `test_schema.py` creates a brand-new `tmp_path` SQLite file per
test (never the project's own persistent state — there is none; this pipeline is CSV/JSON),
enables `PRAGMA foreign_keys = ON`, and runs the entire script via `executescript`.

Verified: all 8 expected tables and both views exist; the `provenance` seed rows (5 codes) are
present; `trecho`'s `sentido` CHECK and `km_end > km_start` CHECK both reject bad inserts;
`height_observation` rejects a row referencing a non-existent `trecho_id` (FK) and rejects a
`data_source`/`provenance` mismatch and a synthetic row missing its generator fields;
`prediction`'s "not-ready caps confidence at low" CHECK is enforced both ways (rejects
`not-ready`+`high`, accepts `not-ready`+`low`); one insert-then-select round trip per main table
(`weather_observation`, `rocada_event`, `feature_row`, `prediction`, `dataset_manifest`); the
`height_observation.nivel` GENERATED column reproduces the exact same 9-point boundary matrix as
`test_classification.py`, from SQL itself, not just from the Python port of the rule; `v_trecho_weekly`'s
window-function `LAG` correctly computes `height_prev_cm`/`weekly_growth_cm`; `v_trecho_latest`
returns the most recently `observed_at` row per trecho.

## 4. Classification (item 5)

Exact boundary matrix, against `api/trecho_meta.py::vegetation_level` (the schema.sql formula,
verbatim): `not-ready` → 0 (even at height 35 cm); missing height → 0; 0 cm → 1; 9.99 → 1;
**10.0 → 2** (not 1 — the frozen rule is `< 10`, so exactly 10 already falls to the next
bracket); 29.99 → 2; **30.0 → 2** (not 3 — the frozen rule is `<= 30`); 30.01 → 3; 100 → 3.

**Noted, not "fixed":** at exactly height = 30 cm, `vegetation_level` says Nível **2**, while
`build_days_forecast`'s own `critical_height_cm` threshold treats height ≥ 30 as **critical**
(`status: "critical"`, 0 days). These are two independently-frozen rules that happen to share the
number 30 as a boundary but disagree at the exact value — both individually correct per their own
definitions (schema.sql's `<= 30 -> 2` vs. the forecast's `>= 30 -> critical`). This is documented
here and in the test file's own comment, not resolved, since resolving it would mean changing one
of two already-frozen, individually-defensible rules without being asked to.

## 5. `parse_trecho_id` (item 6)

`SP-021:norte:000000` → `(SP-021, norte, 0.0, 0.5)`; `SP-021:sul:010500` → `(SP-021, sul, 10.5,
11.0)`; the corridor-end exception, `SP-021:norte:029000` **and** its `sul` counterpart → `km_end
= 29.3` (not 29.5) — the shorter final ~300 m segment, the same one verified against all 50,655
real rows during Phase 10's closure. Every other checked segment is exactly 500 m. `None`, `""`,
and five malformed strings (including a wrong-case sentido) all return `None` — never a guess.

## 6. Feature leakage + roçada-cycle causality (items 7-8)

**Leakage (item 7):** a fixed set of 9 forbidden columns (`true_height_cm`, `nivel_true`,
`generator_seed`, `generator_params_hash`, `generator_version`, `provenance`, `dataset`,
`scenario`, `data_source`) is asserted disjoint from `feature_spec.KEEP`, `.CANDIDATE`, and their
union — and confirmed to already be exactly `feature_spec.EXCLUDE_LEAKAGE`, so the test and the
module can't silently drift apart. `assert_no_leakage` is confirmed to raise on a planted leakage
column and to accept the real frozen `KEEP` list. The `CANDIDATE_EXTENSION` five columns
`scripts/train_and_evaluate_ml.py` adds for `keep_plus_candidate` are confirmed to still be a
subset of `feature_spec.CANDIDATE` (not a second, parallel list) and to still pass
`assert_no_leakage` combined. **This test will fail the moment anyone adds a leakage column to
`KEEP`/`CANDIDATE`**, without needing to know in advance which one — exactly what was asked.

**Roçada-cycle causality (item 8):** a tiny, hand-built 4-week observation series (heights
5→8→[cut]→2→6, one `rocada_event` between weeks 2 and 3) run through the REAL
`greenv_vegpred.features.build.build_trecho_rows` (not a synthetic fixture standing in for it).
Confirmed: the first observation after the cut has `height_prev_cm`/`height_lag2_cm`/
`weekly_growth_cm` all `None` (reset); the *second* observation after the cut has a valid
`height_prev_cm` but `height_lag2_cm` stays `None` (the lag-2 window would reach back across the
cut, and does not); pre-cut rows compute lags normally; `days_since_rocada` passes through
unchanged from the source observation (this function does not recompute it — it trusts the
upstream, already-causal value); `true_height_cm` is only ever written to the
`_DIAGNOSTIC_ONLY`-suffixed column, never the bare name. **The core causality check:** adding a
second `rocada_event` dated *after* every observation in the series produces byte-for-byte
identical output rows to not adding it at all — a future roçada genuinely cannot reach into any
feature.

## 7. `build_days_forecast` + `conformal_quantile` (items 9-10)

All 8 required cases (A-H) pass against a deterministic `FakeDaysModel` stub — the real
`q80=24.71`/`q90=34.57` calibration values are never read or recomputed by this file:

A) height ≥ 30 → `critical`, 0 days, interval `(0, 0)` — confirmed at 30.0 exactly and at 35.0.
B) finite model point below 30 → `forecast`, interval = `point ± q`.
C) model reports no crossing → `beyond_horizon`, `days_until_critical: null`, `interval: null`.
D) missing anchor height → `insufficient_data`, `reason: "missing_anchor_height_cm"`.
E) a point smaller than `q` → lower bound clamps to exactly `0.0`, never negative.
F) a point + q above 120 → upper clamps to `120.0` **and** `upper_is_horizon_bound: true`.
G) `not-ready` → `operational_confidence: "low"`, in both the `forecast` and `critical` branches,
   while `interval.confidence` (the statistical level) stays whatever was requested — the two
   never move together.
H) `ready` + `forecast` → `"medium"`; `ready` + `critical` → `"high"`.

Plus: blockers are parsed from the JSON-array string and an empty list becomes `None` (never an
empty list); `data_provenance` defaults to `"synthetic"` when absent from the row; no
administrative field (`generator_seed`, `dataset`, `true_height_cm`, ...) ever appears in the
output object.

`conformal_quantile`: the formula `k = ⌈(n+1)(1-α)⌉`-th smallest `|residual|` is confirmed by
hand-computed arithmetic on `[1..9]` (80% → 8th smallest = 8; 90% → 9th = 9), on `[1..99]` (90% →
90th = 90), confirmed to use absolute value (negatives give the same result), and confirmed to
return `+inf` — an honestly-unusable interval, not a falsely narrow one — when the calibration
sample is too small for the requested confidence (`n=2`, 90% needs `k=3`).

## 8. Ranking (item 11)

`rank_forecasts` on a small, artificial, hand-built list confirms: the exact bucket order
(`critical` → `forecast` by ascending `days_until_critical` → `beyond_horizon` →
`insufficient_data`); ties within a bucket break by ascending `trecho_id`; **`operational_confidence`
never changes the order** (two otherwise-identical entries differing only there keep the
`trecho_id` tie-break order); **interval width never changes the order** (same test, with wildly
different interval widths instead); no hidden field is added beyond the documented `rank`; the
input list is never mutated in place; an empty list returns an empty list.

## 9. API (item 12)

Every required case from the phase's own list, via FastAPI's `TestClient` against the REAL Phase
9 snapshot: `/health` → 200; `/api/v1/summary` → 200, and its four counts sum to `total_trechos`;
`/api/v1/forecasts/ranking?limit=3` → exactly 3 rows, ranks `[1,2,3]`; `limit=0`/`-1`/`501` → 422;
an existing trecho → 200; a nonexistent one → 404; `/api/v1/forecasts?status=forecast` and
`?operational_status=not-ready` both return only matching, non-empty results; a valid
`POST /predict` at height ≥ 30 → 200 (never touches the model); a valid one below 30 → 200 or 503
depending on whether the model happens to be present; `generator_seed`/`true_height_cm` in the
body → 422 either way; a response is confirmed to never contain `generator_seed`,
`generator_params_hash`, `generator_version`, a Python `Traceback`, or a `site-packages` path;
and a deliberately-planted `RuntimeError` inside `service.get_summary` (via `monkeypatch`) is
confirmed to surface as exactly `{"detail": "internal error"}`, HTTP 500 — never a stack trace.

## 10. Model artifact absent (item 13)

Simulated **without ever moving, deleting, or overwriting the real `.joblib`**: the test
monkeypatches `loader.DAYS_MODEL_PATH` to a `tmp_path` file that doesn't exist, then clears the
process-wide `lru_cache` singleton (`loader.get_store.cache_clear()`) so a fresh `ArtifactStore`
re-checks that (fake) path. Confirmed: `/health` reports `model_available: false`; the snapshot
endpoints (`ranking`, `summary`) still return 200; `POST /predict` below the threshold → 503 with
a clear regeneration message; `POST /predict` at/above the threshold → still 200, because that
branch never consults the model at all. The fixture restores the real cache state afterward, and
nothing on disk was ever touched.

## 11. Model contract / serialisation (item 14)

The 8 small, git-committed artifacts (Ridge/OLS/Lasso/HistGB, height + days, all under 1 MB)
each: load via `joblib.load` without error; expose the expected protocol
(`predict_height`/`predict_height_batch` or `predict`/`predict_batch`); produce a deterministic
prediction on the same input (bit-exact `==`, since none of these use parallel execution); and,
for the height models, stay within the physical `[0, 150]` cm bound.

The large Random Forest artifact is tested **only** under `@pytest.mark.requires_rf_model`, with
an explicit `pytest.skip(...)` when the file is absent — the standard suite never depends on it
being in the checkout, per the phase's explicit requirement. In this development environment the
file happens to be present (Phase 7/8's own training run left it on disk locally), so that one
test actually ran rather than skipped — see §16 for what changes on a fresh clone.

## 12. Pipeline smoke test (item 16)

`test_pipeline_smoke.py`: the real `data/forecast/ranking_current.json` (118 rows, the whole
corridor) is read straight from disk, confirmed to flow through `loader.get_store().snapshot()`
(re-ranked, ranks `1..118` exactly), through `service.get_ranking()` (every row enriched with
`rodovia`/`sentido`/`vegetation_level`), and out through the live HTTP app — a ranking request and
a single-trecho request for the same `trecho_id` are confirmed to agree on `days_until_critical`.
No training, no TEST/OOD file opened anywhere in this suite.

## 13. Coverage (item 18)

`pytest-cov`, focused on the modules this phase asked to cover well — not chased to 100%:

| Module | Coverage | Note |
|---|---:|---|
| `api/app.py` | 100% | |
| `api/routes.py` | 100% | |
| `api/schemas.py` | 100% | |
| `api/trecho_meta.py` | 100% | |
| `forecast/ranking.py` | 100% | |
| `forecast/__init__.py` | 100% | |
| `features/feature_spec.py` | 100% | |
| `api/service.py` | 96% | 2 lines uncovered (a minor branch not exercised) |
| `api/loader.py` | 85% | uncovered lines are the corrupt-file/invalid-JSON `except` branches for calibration/snapshot loading — not deliberately triggered |
| `forecast/interval.py` | 82% | uncovered lines are inside `build_height_forecast` (the secondary height-interval structure, Phase 9's own lower-priority path) — not tested here at the same depth as `build_days_forecast` |
| `features/build.py` | 55% | only `build_trecho_rows` (item 8's target) was exercised in depth; `load_corridor_extras`/`load_dataset`/`build_all` (file-I/O orchestration reading real weather CSVs) were left untested — deliberately, as invoking them meaningfully would mean re-reading the full weather/synthetic corpus rather than a small fixture |
| **Total (these modules)** | **81%** | |

No test was added purely to move a percentage — every gap above is named, not hidden.

## 14. Bugs found (item 19)

**One, found and fixed — classified before touching anything:**

> `test_model_contract.py`'s Random Forest determinism test initially asserted `p1 == p2` (bit
> -exact) for two calls to the frozen `RandomForestRegressor` (`n_jobs=-1`) on the same row. Run
> in isolation, it failed once: `26.495973624766556 != 26.495973624766552` — a ~1e-15 relative
> difference in the 15th significant digit.
>
> **Classification: (B) test incorrect**, not (A) an implementation bug. `n_jobs=-1` (a Phase 7,
> frozen hyperparameter — unchanged here) parallelises prediction across trees, and
> floating-point summation is not associative under a parallel reduction whose thread/process
> schedule can vary slightly between calls. The model's predictions are not "wrong" or unstable
> at any practically meaningful precision — the test's own tolerance was simply tighter than
> floating-point arithmetic can honestly promise under parallel execution.
>
> **Fix:** changed the assertion to `pytest.approx(p2, rel=1e-6)` — a tolerance many orders of
> magnitude looser than the observed ~1e-15 noise, while still meaningfully testing determinism
> at any precision that matters. **No model, hyperparameter (including `n_jobs`), or calibration
> value was changed.** Re-run 2 full suites + 5 standalone targeted runs after the fix: all green.
>
> Not used to justify this: TEST, OOD, or any Phase 6-9 metric — the fix is purely about
> floating-point comparison tolerance in a test, unrelated to model accuracy.

No other bug, of any of the four classes, was found in this pass.

## 15. On not snapshotting the full OpenAPI YAML (item 15)

`test_openapi.py` checks `app.openapi()` structurally (expected paths present, the `Forecast`/
`Interval`/`PredictRequest` schemas carry the fields this whole contract depends on,
`operational_confidence` and `interval.confidence` stay two separate fields, `data_provenance` is
pinned to `"synthetic"`, `PredictRequest` forbids extra properties and never declares a leakage
field) — never a byte-for-byte diff against the committed `api/openapi.v1.yaml`. A full-file
snapshot test would break on any cosmetic FastAPI/Pydantic version bump (a reordered key, a
changed `$ref` format) with no real information about whether the CONTRACT changed — exactly the
brittle-maintenance risk this phase's own instruction named. `scripts/verify.sh` still checks the
committed YAML is byte-identical to a fresh `app.openapi()` dump, as a separate, deliberate
concern (drift detection), not duplicated inside the pytest suite.

## 16. Reproducibility, and what "skipped" depends on (item 17)

Run twice (plus a third run, plus 5 standalone runs of the one previously-flaky test): **146
passed, 0 skipped, 0 failed each time**, ~3.3-3.6s total. `./scripts/verify.sh` (pytest +
OpenAPI-in-sync check) passes end to end.

**0 tests were skipped in THIS environment**, because the large Random Forest `.joblib` happens
to be present on disk here (left over from this session's own Phase 7/8 training run). On a
**fresh `git clone`** of this repository, that file would be absent (it is excluded by
`.gitignore`, >5 MB, per `AGENTS.md`) — in that case
`TestLargeRandomForestArtifactOptional::test_random_forest_days_model_is_deterministic` would
report **1 skipped**, with the reason
`"days_until_30cm__random_forest__keep_plus_candidate.joblib not present in this checkout (not committed to git, >5MB per AGENTS.md) -- optional test"`,
and every other test (145 of 146) would still pass unchanged — confirmed by inspection of the
`if not path.exists(): pytest.skip(...)` guard, which is the only thing standing between "run" and
"skip" for that one test.

## 17. Confirmations

- **No model retrained, no interval recalibrated.** Every model-bearing test uses either the
  already-frozen, git-committed small artifacts (read-only, `joblib.load`), a `FakeDaysModel`
  stub, or — for the one optional RF test — the already-frozen large artifact, also read-only.
  `data/models/interval_calibration.json`'s real `q80`/`q90` values are never read by any test in
  this suite (only hand-picked round numbers, `{0.80: 5.0, 0.90: 10.0}`, are used for
  `build_days_forecast` unit tests).
- **No other service or app was altered.** `apps/web`, `apps/mobile`, `measurement/`,
  `infrastructure/`, `compose.yaml`: untouched, verified by `git status` before finishing.
- **No secret, token, key, personal email, or personal absolute path** appears in any new `.py`
  test file (checked by direct grep); the only match for a personal path is inside `__pycache__/`
  bytecode files, which are already `.gitignore`d and not a new concern introduced here.

## 18. Limitations

- This suite verifies the module's own internal contracts and behaviour — it is not a load test,
  a security audit, or a real-world field validation (none of Phases 1-10 were, either).
- `features/build.py`'s file-I/O orchestration (`load_dataset`, `load_corridor_extras`,
  `build_all`) is untested here — only its core per-trecho row-building logic
  (`build_trecho_rows`) was, which is where the causality property this phase asked about
  actually lives.
- `build_height_forecast` (Phase 9's secondary height-interval structure) has lighter test depth
  than `build_days_forecast` — the primary deliverable got the fuller treatment, matching how
  Phase 9 itself prioritised the two.
