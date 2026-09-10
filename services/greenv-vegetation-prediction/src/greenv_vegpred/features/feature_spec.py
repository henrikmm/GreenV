"""The FROZEN Phase 5 feature classification.

Phase 5 completion criteria (docs/VEGETATION_PREDICTION_TASK.md): "the final
feature list is frozen in src/greenv_vegpred/features/". This module IS that
freeze. Phase 7 MUST import `KEEP` / `CANDIDATE` from here rather than reading
raw CSV columns -- that is what stops an administrative or leakage column from
silently becoming a model input.

Categories (per instruction, not TASK.md's original four -- TARGET is added
because targets are neither a feature nor metadata and must never be confused
with either):
    KEEP             load-bearing, literature-supported or structurally
                     necessary; the Phase 6-7 baseline feature set.
    CANDIDATE        legitimate, but weaker support, redundant with a KEEP
                     column, or needs care (encoding, sweeping, target-
                     encoding on train only). Phase 7 may add these one at a
                     time and show the delta -- not include all of them by
                     default.
    METADATA         bookkeeping. Never a feature. Not dangerous, just not
                     predictive (ids, timestamps, split labels, build info).
    EXCLUDE_LEAKAGE  actively dangerous if used as a feature: future
                     information, the generator's own diagnostic
                     (true_height_cm), or an administrative identifier that
                     would let a model discover synthetic-vs-real or which
                     generator run produced a row.
    TARGET           the label. Never a feature, never metadata.
"""

KEEP = [
    "height_cm",                 # current observed height (Phase 4 observed_height_cm)
    "height_prev_cm",            # one prior observation, SAME roçada cycle only
    "weekly_growth_cm",          # height_cm - height_prev_cm, SAME cycle only
    "days_since_rocada",         # counts only past roçadas
    "gdd_week_tb15_c",           # thermal driver; Tb=15 is the params.yaml REFERENCE value
    "tmin_week_c",               # literature: Tmin is the single strongest predictor (Tonato 2010)
    "rain_30d_mm",                # corridor-common rolling rain (DEC-001)
    "water_deficit_30d",         # DEC-002 primary water-deficit metric
    "operational_status",        # current-week quality/management context only
]

CANDIDATE = [
    "height_lag2_cm",            # two observations back, same cycle; thin support at gaps
    "weeks_observed_last_8",     # data-density signal
    "rocada_in_new_cycle",       # 1 on the first observation after a cut (Phase 5 correction)
    "gdd_week_tb10_c", "gdd_week_tb17_c",   # Phase 1/DEC-003 Tb sensitivity sweep, not the default
    "tmean_week_c", "tmax_week_c",          # redundant with tmin_week_c + gdd; literature weaker
    "rain_7d_mm", "rain_14d_mm",            # redundant with rain_30d_mm at shorter windows
    "shortwave_7d_mj_m2",        # RUE path, Phase 1 secondary; corridor-common
    "et0_7d_mm",                 # component of water_deficit; may double-count if both used
    "water_deficit_90d",         # DEC-002 seasonal window (added in Phase 5)
    "relative_humidity_7d_pct",  # Phase 1: no direct effect confirmed; corridor-common; hypothesis if used
    "week_of_year", "season_sin", "season_cos", "dry_season_flag",  # DEC-001: seasonality is meant
                                                                      # to be emergent from GDD/rain;
                                                                      # these risk double-counting it
    "vegetation_type",           # HYPOTHESIS label (Phase 4 §1); encode as category, expect weak signal
    "km_mid", "centroid_lat", "centroid_lon",  # position; centroid is `hypothesis` provenance
    "grid_cell_id",               # prefer the derived climate features over this raw id
    "cell_coverage", "frame_count",  # current-observation quality draws
    "blockers",                   # raw JSON; needs encoding (e.g. one-hot of codes) before use
    "nivel_observed",             # redundant with height_cm + operational_status; current week only
]

METADATA = [
    "feature_row_id", "trecho_id", "rodovia", "sentido",
    "iso_year", "iso_week", "as_of_date",
    "split", "split_blocked_trecho", "split_scheme", "feature_spec_version",
    "week_days_present",           # DEC-004 filter already applied at build time; near-constant after
    "target_days_until_30cm_horizon_days",  # validity metadata of the target, not a feature or the target itself
    "target_height_plus_7d_actual_offset_days",
    "target_height_plus_14d_actual_offset_days",
    "target_height_plus_30d_actual_offset_days",  # the weekly grid means "+30d" is really the
                                                    # nearest observation within +-3 days; this
                                                    # records the true offset actually used
    "built_at",
]

EXCLUDE_LEAKAGE = [
    "true_height_cm",              # simulator diagnostic. NEVER a feature or a target basis.
    "nivel_true",                  # derived from true_height_cm; same rule
    "generator_version", "generator_seed", "generator_params_hash",
    "data_source", "provenance", "dataset", "scenario",   # administrative: would let a model
                                                            # discover synthetic-vs-real or which run
]

TARGET = [
    "target_height_plus_7d_cm",
    "target_height_plus_14d_cm",
    "target_height_plus_30d_cm",
    "target_days_until_30cm",
    "target_days_until_30cm_censored",
]


def keep_and_candidate_columns() -> list[str]:
    """The columns Phase 7 is allowed to read as model inputs (KEEP first)."""
    return list(KEEP) + list(CANDIDATE)


def assert_no_leakage(columns) -> None:
    """Raise if any EXCLUDE_LEAKAGE or TARGET column appears in a proposed feature list."""
    bad = (set(columns) & set(EXCLUDE_LEAKAGE)) | (set(columns) & set(TARGET))
    if bad:
        raise ValueError(f"leakage columns in feature list: {sorted(bad)}")
