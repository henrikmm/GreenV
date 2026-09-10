#!/usr/bin/env python3
"""
Phase 4 Part B — generate the synthetic vegetation-height datasets for SP-021.

EVERYTHING PRODUCED HERE IS SYNTHETIC. Driven by the real Phase 3 weather; every
row carries data_source=synthetic, provenance=synthetic, generator_version,
generator_seed, generator_params_hash. See reports/synthetic-model.md.

Datasets:
  main   seed 42    scenario baseline
  seed2  seed 1337  scenario baseline   (second seed; seed-variance reference + 2nd training set)
  ood    seed 2024  scenario ood        (different MECHANISM, not just a seed -- see synthetic-model.md)

Usage:  python scripts/generate_synthetic.py [--only main|seed2|ood]
"""
from __future__ import annotations
import argparse, csv, json, math, sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from greenv_vegpred.synth.generator import simulate  # noqa: E402

MODULE_ROOT = Path(__file__).resolve().parents[1]
OUT = MODULE_ROOT / "data" / "synthetic"

DATASETS = [
    ("main", 42, "baseline"),
    ("seed2", 1337, "baseline"),
    ("ood", 2024, "ood"),
]


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


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", choices=[d[0] for d in DATASETS])
    args = ap.parse_args()

    created = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    for name, seed, scenario in DATASETS:
        if args.only and name != args.only:
            continue
        print(f"\n=== {name}  seed={seed}  scenario={scenario} ===")
        obs, roc, meta = simulate(seed=seed, scenario=scenario)
        d = OUT / name
        write_csv(d / "height_observations.csv", obs)
        write_csv(d / "rocada_events.csv", roc)

        manifest = dict(
            kind="synthetic_heights",
            is_synthetic=1,
            dataset=name,
            created_at=created,
            generator_version=meta["generator_version"],
            seed=meta["seed"],
            scenario=meta["scenario"],
            generator_params_hash=meta["generator_params_hash"],
            period=meta["period"],
            n_trechos=meta["n_trechos"],
            n_observation_weeks=meta["n_obs_dates"],
            n_height_observations=len(obs),
            n_rocada_events=len(roc),
            data_source="synthetic",
            provenance="synthetic",
            driven_by="data/real/weather/processed/ (real ERA5-seamless; provenance weather_real)",
            weather_feature_spec="weather-v1 (reports/methodology-decisions.md)",
            note="SYNTHETIC. Not a measurement. true_height_cm is a simulator diagnostic, never a training target.",
            resolved_params=meta["resolved_params"],
            literature_params=meta["literature_params"],
            outputs=[
                f"data/synthetic/{name}/height_observations.csv",
                f"data/synthetic/{name}/rocada_events.csv",
            ],
        )
        (d / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"  observations : {len(obs):>7}")
        print(f"  rocada events: {len(roc):>7}")
        print(f"  params_hash  : {meta['generator_params_hash']}")
        print(f"  -> {d}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
