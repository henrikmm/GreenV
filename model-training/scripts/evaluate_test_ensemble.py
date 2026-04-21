#!/usr/bin/env python3
"""Ensemble por media de probabilidades entre checkpoints dos folds."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch
from sklearn.metrics import balanced_accuracy_score, confusion_matrix, f1_score, recall_score
from torch.utils.data import DataLoader

from model_training import CLASS_NAMES, VegetationDataset, create_efficientnet_b0
from model_training.config import NUM_CLASSES, TEST_CSV
from model_training.training import save_json


def load_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def resolve_device(device_arg: str) -> torch.device:
    if device_arg == "auto":
        return torch.device("cuda" if torch.cuda.is_available() else "cpu")
    return torch.device(device_arg)


def discover_checkpoints(run_dir: Path) -> list[Path]:
    paths = sorted(run_dir.glob("fold_*/best.pt"))
    if not paths:
        raise FileNotFoundError(f"Nenhum checkpoint encontrado em {run_dir}/fold_*/best.pt")
    return paths


def predict_probs(
    model: torch.nn.Module,
    loader: DataLoader,
    device: torch.device,
    use_tta: bool,
) -> tuple[np.ndarray, np.ndarray]:
    model.eval()
    all_probs: list[np.ndarray] = []
    all_labels: list[np.ndarray] = []

    with torch.no_grad():
        for images, labels in loader:
            images = images.to(device, non_blocking=True)

            logits = model(images)
            probs = torch.softmax(logits, dim=1)
            if use_tta:
                logits_flip = model(torch.flip(images, dims=[3]))
                probs = (probs + torch.softmax(logits_flip, dim=1)) / 2.0

            all_probs.append(probs.cpu().numpy())
            all_labels.append(labels.numpy())

    return np.concatenate(all_probs, axis=0), np.concatenate(all_labels, axis=0)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("run_dir", type=Path)
    parser.add_argument("--csv", type=Path, default=TEST_CSV)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--num-workers", type=int, default=4)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--tta", action="store_true", help="Aplica TTA com flip horizontal.")
    parser.add_argument(
        "--output-name",
        default=None,
        help="Default: test_eval_ensemble[_tta].json",
    )
    args = parser.parse_args()

    device = resolve_device(args.device)
    checkpoint_paths = discover_checkpoints(args.run_dir)

    first_ckpt = torch.load(checkpoint_paths[0], map_location="cpu")
    image_size = int(first_ckpt["config"].get("image_size", 224))

    dataset = VegetationDataset(args.csv, image_size=image_size)
    loader = DataLoader(
        dataset,
        batch_size=min(args.batch_size, len(dataset)),
        shuffle=False,
        num_workers=args.num_workers,
        pin_memory=(device.type == "cuda"),
    )

    probs_accumulator: np.ndarray | None = None
    labels_ref: np.ndarray | None = None

    for ckpt_path in checkpoint_paths:
        checkpoint = torch.load(ckpt_path, map_location="cpu")
        model = create_efficientnet_b0(pretrained=False)
        model.load_state_dict(checkpoint["model_state_dict"])
        model = model.to(device)

        probs, labels = predict_probs(model, loader, device, use_tta=args.tta)
        if probs_accumulator is None:
            probs_accumulator = probs
            labels_ref = labels
        else:
            probs_accumulator = probs_accumulator + probs
        print(f"[ok] fold checkpoint: {ckpt_path.relative_to(args.run_dir)}")

    assert probs_accumulator is not None and labels_ref is not None
    avg_probs = probs_accumulator / len(checkpoint_paths)
    preds = avg_probs.argmax(axis=1)

    macro_f1 = f1_score(labels_ref, preds, average="macro", zero_division=0)
    balanced_acc = balanced_accuracy_score(labels_ref, preds)
    recalls = recall_score(
        labels_ref, preds,
        average=None, labels=list(range(NUM_CLASSES)), zero_division=0,
    )
    cm = confusion_matrix(labels_ref, preds, labels=list(range(NUM_CLASSES)))

    metrics = {
        "macro_f1": float(macro_f1),
        "balanced_accuracy": float(balanced_acc),
        "recall_by_class": {
            CLASS_NAMES[idx]: float(recalls[idx]) for idx in range(NUM_CLASSES)
        },
        "confusion_matrix": cm.tolist(),
        "support": int(len(labels_ref)),
    }

    result = {
        "run_dir": str(args.run_dir),
        "checkpoint_paths": [str(p) for p in checkpoint_paths],
        "num_checkpoints": len(checkpoint_paths),
        "csv": str(args.csv),
        "image_size": image_size,
        "device": str(device),
        "tta": bool(args.tta),
        "class_names": CLASS_NAMES,
        "metrics": metrics,
        "note": "Ensemble por media de probabilidades entre todos os folds.",
    }

    if args.output_name:
        output_path = args.run_dir / args.output_name
    else:
        suffix = "_tta" if args.tta else ""
        output_path = args.run_dir / f"test_eval_ensemble{suffix}.json"
    save_json(output_path, result)

    print(f"[ok] folds no ensemble: {len(checkpoint_paths)}")
    print(f"[ok] tta: {args.tta}")
    print(f"[ok] macro_f1: {metrics['macro_f1']:.4f}")
    print(f"[ok] balanced_accuracy: {metrics['balanced_accuracy']:.4f}")
    print(f"[ok] recall_by_class: {metrics['recall_by_class']}")
    print(f"[ok] saved: {output_path}")


if __name__ == "__main__":
    main()
