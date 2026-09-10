#!/usr/bin/env python3
"""
Phase 5 — materialise feature_row from Phase 3 (real weather) + Phase 4 (synthetic).

Writes:
  data/features/feature_row_dev.csv          main + seed2, split in {train,validation,test,excluded}
  data/features/feature_row_ood_holdout.csv  ood only, split='excluded' (never train/val/test)
  data/features/leakage_audit.json           programmatic leakage checks (must all pass)
  data/features/build_stats.json             row counts, split counts, exclusion counts

true_height_cm / nivel_true are written ONLY under *_DIAGNOSTIC_ONLY column names, in the
dev/ood files, for EDA use — never referenced by feature_spec.KEEP/CANDIDATE.
"""
from __future__ import annotations
import csv, json, math, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from greenv_vegpred.features.build import build_all  # noqa: E402
from greenv_vegpred.features import feature_spec  # noqa: E402

MODULE_ROOT = Path(__file__).resolve().parents[1]
OUT = MODULE_ROOT / "data" / "features"


def write_csv(path: Path, rows: list[dict]):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    fields = list(rows[0].keys())
    with path.open("w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=fields)
        w.writeheader()
        for r in rows:
            w.writerow({k: ("" if r.get(k) is None or (isinstance(r.get(k), float) and math.isnan(r[k]))
                            else r[k]) for k in fields})


def leakage_audit(dev_rows, ood_rows):
    all_cols = set(dev_rows[0].keys()) if dev_rows else set()
    model_ready = feature_spec.keep_and_candidate_columns()
    checks = {}

    # 1. no EXCLUDE_LEAKAGE / TARGET name in the model-ready list
    try:
        feature_spec.assert_no_leakage(model_ready)
        checks["model_ready_list_is_clean"] = True
    except ValueError as e:
        checks["model_ready_list_is_clean"] = f"FAIL: {e}"

    # 2. every KEEP/CANDIDATE column actually exists in the built table (name-drift guard)
    missing = [c for c in model_ready if c not in all_cols]
    checks["all_keep_candidate_columns_present"] = missing or True

    # 3. diagnostic columns are physically separated by name (DIAGNOSTIC_ONLY suffix) and are
    #    NOT in KEEP/CANDIDATE
    diag_cols = [c for c in all_cols if c.endswith("_DIAGNOSTIC_ONLY")]
    leaked_diag = set(diag_cols) & set(model_ready)
    checks["diagnostic_columns_excluded_from_model_ready"] = (not leaked_diag) or sorted(leaked_diag)

    # 4. administrative identity columns (generator_*, data_source, provenance, dataset, scenario)
    #    are not in model-ready
    admin = ["generator_version", "generator_seed", "generator_params_hash",
             "data_source", "provenance", "dataset", "scenario"]
    leaked_admin = set(admin) & set(model_ready)
    checks["administrative_identity_excluded"] = (not leaked_admin) or sorted(leaked_admin)

    # 5. cycle-reset invariant: every rocada_in_new_cycle=1 row has NULL lag/growth
    bad_cycle = [r["feature_row_id"] for r in dev_rows
                if str(r.get("rocada_in_new_cycle")) == "1"
                and (r.get("height_prev_cm") not in (None, "") or r.get("weekly_growth_cm") not in (None, ""))]
    checks["cycle_reset_nulls_lag_and_growth"] = (not bad_cycle) or bad_cycle[:10]

    # 6. no row references a target date beyond the dataset horizon (structural; always true by
    #    construction, checked defensively)
    checks["targets_within_horizon"] = all(
        (r.get("target_days_until_30cm_horizon_days") == 120) for r in dev_rows[:1000]
    ) if dev_rows else True

    # 7. dev and ood are physically separate files / never share a dataset value
    dev_datasets = {r["dataset"] for r in dev_rows}
    ood_datasets = {r["dataset"] for r in ood_rows}
    checks["dev_ood_disjoint"] = (not (dev_datasets & ood_datasets)) or sorted(dev_datasets & ood_datasets)

    # 8. temporal split boundaries do not overlap in as_of_date
    from collections import defaultdict
    by_split = defaultdict(set)
    for r in dev_rows:
        by_split[r["split"]].add(r["as_of_date"])
    tr, va, te = by_split.get("train", set()), by_split.get("validation", set()), by_split.get("test", set())
    checks["temporal_splits_date_disjoint"] = not ((tr & va) or (tr & te) or (va & te))

    checks["overall_pass"] = all(
        v is True for k, v in checks.items() if k != "overall_pass"
    )
    return checks


def main():
    dev_rows, ood_rows, stats, built_at = build_all()
    write_csv(OUT / "feature_row_dev.csv", dev_rows)
    write_csv(OUT / "feature_row_ood_holdout.csv", ood_rows)

    audit = leakage_audit(dev_rows, ood_rows)
    (OUT / "leakage_audit.json").write_text(json.dumps(audit, ensure_ascii=False, indent=1), encoding="utf-8")

    from collections import Counter
    split_counts = Counter(r["split"] for r in dev_rows)
    blocked_counts = Counter(r["split_blocked_trecho"] for r in dev_rows)
    stats["dev_total_rows"] = len(dev_rows)
    stats["ood_total_rows"] = len(ood_rows)
    stats["temporal_split_counts"] = dict(split_counts)
    stats["blocked_split_counts"] = dict(blocked_counts)
    stats["built_at"] = built_at
    stats["feature_spec_version"] = "weather-v1+features-v1"
    stats["keep_columns"] = feature_spec.KEEP
    stats["candidate_columns"] = feature_spec.CANDIDATE
    stats["metadata_columns"] = feature_spec.METADATA
    stats["exclude_leakage_columns"] = feature_spec.EXCLUDE_LEAKAGE
    stats["target_columns"] = feature_spec.TARGET
    (OUT / "build_stats.json").write_text(json.dumps(stats, ensure_ascii=False, indent=1), encoding="utf-8")

    print(f"dev rows: {len(dev_rows)}   ood rows: {len(ood_rows)}")
    print("split counts:", dict(split_counts))
    print("blocked split counts:", dict(blocked_counts))
    print("leakage audit overall_pass:", audit["overall_pass"])
    for k, v in audit.items():
        if v is not True:
            print("  ATTENTION:", k, "->", v)
    print("per-dataset:", json.dumps(stats["per_dataset"], indent=1))


if __name__ == "__main__":
    main()
