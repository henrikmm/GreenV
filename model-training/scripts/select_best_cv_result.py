#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def load_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("run_dir", type=Path)
    args = parser.parse_args()

    summary_path = args.run_dir / "cv_summary.json"
    if not summary_path.exists():
        raise FileNotFoundError(f"Arquivo nao encontrado: {summary_path}")

    summary = load_json(summary_path)
    selected = summary["selected_fold"]

    print(f"[ok] run_dir: {args.run_dir}")
    print(f"[ok] folds_ran: {summary['num_folds_ran']}")
    print(
        f"[ok] macro_f1_mean/std: "
        f"{summary['macro_f1_mean']:.4f} / {summary['macro_f1_std']:.4f}"
    )
    print(
        f"[ok] balanced_accuracy_mean/std: "
        f"{summary['balanced_accuracy_mean']:.4f} / {summary['balanced_accuracy_std']:.4f}"
    )
    print(
        f"[ok] class3_recall_mean/std: "
        f"{summary['class3_recall_mean']:.4f} / {summary['class3_recall_std']:.4f}"
    )
    print(f"[ok] selected_fold: {selected['fold']}")
    print(f"[ok] selected_checkpoint: {selected['checkpoint_path']}")
    print(f"[ok] selected_epoch: {selected['best_epoch']}")
    print(f"[ok] selected_macro_f1: {selected['macro_f1']:.4f}")
    print(f"[ok] selected_class3_recall: {selected['class3_recall']:.4f}")
    print(f"[ok] selected_balanced_accuracy: {selected['balanced_accuracy']:.4f}")
    print(f"[ok] selection_rule: {selected['selection_rule']}")


if __name__ == "__main__":
    main()
