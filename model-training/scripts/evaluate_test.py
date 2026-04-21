#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path

import torch
from sklearn.metrics import balanced_accuracy_score, confusion_matrix, f1_score, recall_score
from torch import nn
from torch.utils.data import DataLoader

from model_training import CLASS_NAMES, VegetationDataset, create_efficientnet_b0
from model_training.config import NUM_CLASSES, TEST_CSV
from model_training.training import evaluate, save_json


def load_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def resolve_checkpoint(run_dir: Path, checkpoint: Path | None) -> Path:
    if checkpoint is not None:
        return checkpoint

    summary = load_json(run_dir / "cv_summary.json")
    candidate = Path(summary["selected_fold"]["checkpoint_path"])
    if candidate.is_absolute():
        return candidate
    if candidate.exists():
        return candidate.resolve()
    return (run_dir / candidate).resolve()


def resolve_device(device_arg: str) -> torch.device:
    if device_arg == "auto":
        return torch.device("cuda" if torch.cuda.is_available() else "cpu")
    return torch.device(device_arg)


def evaluate_with_tta(
    model: nn.Module,
    loader: DataLoader,
    criterion: nn.Module,
    device: torch.device,
    use_tta: bool,
) -> dict[str, object]:
    model.eval()
    running_loss = 0.0
    total = 0
    y_true: list[int] = []
    y_pred: list[int] = []

    with torch.no_grad():
        for images, labels in loader:
            images = images.to(device, non_blocking=True)
            labels = labels.to(device, non_blocking=True)

            logits = model(images)
            probs = torch.softmax(logits, dim=1)

            if use_tta:
                logits_flip = model(torch.flip(images, dims=[3]))
                probs = (probs + torch.softmax(logits_flip, dim=1)) / 2.0

            loss = criterion(logits, labels)
            preds = probs.argmax(dim=1)

            batch_size = labels.size(0)
            running_loss += float(loss.item()) * batch_size
            total += batch_size

            y_true.extend(labels.cpu().tolist())
            y_pred.extend(preds.cpu().tolist())

    if not y_true:
        raise ValueError("Test sem amostras.")

    macro_f1 = f1_score(y_true, y_pred, average="macro", zero_division=0)
    balanced_acc = balanced_accuracy_score(y_true, y_pred)
    recalls = recall_score(
        y_true,
        y_pred,
        average=None,
        labels=list(range(NUM_CLASSES)),
        zero_division=0,
    )
    cm = confusion_matrix(y_true, y_pred, labels=list(range(NUM_CLASSES)))

    return {
        "loss": running_loss / max(total, 1),
        "macro_f1": float(macro_f1),
        "balanced_accuracy": float(balanced_acc),
        "recall_by_class": {
            CLASS_NAMES[idx]: float(recalls[idx]) for idx in range(NUM_CLASSES)
        },
        "confusion_matrix": cm.tolist(),
        "support": int(total),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("run_dir", type=Path)
    parser.add_argument("--checkpoint", type=Path, default=None)
    parser.add_argument("--csv", type=Path, default=TEST_CSV)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--num-workers", type=int, default=4)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--tta", action="store_true", help="Aplica TTA com flip horizontal.")
    parser.add_argument(
        "--output-name",
        default=None,
        help="Nome do arquivo de saida (default: test_eval_selected_fold[_tta].json).",
    )
    args = parser.parse_args()

    checkpoint_path = resolve_checkpoint(args.run_dir, args.checkpoint)
    checkpoint = torch.load(checkpoint_path, map_location="cpu")
    cfg = checkpoint["config"]
    image_size = int(cfg.get("image_size", 224))
    device = resolve_device(args.device)

    model = create_efficientnet_b0(pretrained=False)
    model.load_state_dict(checkpoint["model_state_dict"])
    model = model.to(device)

    dataset = VegetationDataset(args.csv, image_size=image_size)
    loader = DataLoader(
        dataset,
        batch_size=min(args.batch_size, len(dataset)),
        shuffle=False,
        num_workers=args.num_workers,
        pin_memory=(device.type == "cuda"),
    )
    criterion = nn.CrossEntropyLoss()

    if args.tta:
        metrics = evaluate_with_tta(model, loader, criterion, device, use_tta=True)
        note = "Test eval with horizontal-flip TTA using the CV-selected checkpoint."
    else:
        metrics = evaluate(model, loader, criterion, device)
        note = "Evaluation on the untouched holdout test split using the CV-selected checkpoint."

    result = {
        "run_dir": str(args.run_dir),
        "checkpoint_path": str(checkpoint_path),
        "csv": str(args.csv),
        "image_size": image_size,
        "device": str(device),
        "tta": bool(args.tta),
        "class_names": CLASS_NAMES,
        "metrics": metrics,
        "note": note,
    }

    if args.output_name:
        output_path = args.run_dir / args.output_name
    else:
        suffix = "_tta" if args.tta else ""
        output_path = args.run_dir / f"test_eval_selected_fold{suffix}.json"
    save_json(output_path, result)

    print(f"[ok] run_dir: {args.run_dir}")
    print(f"[ok] checkpoint: {checkpoint_path}")
    print(f"[ok] image_size: {image_size}")
    print(f"[ok] tta: {args.tta}")
    print(f"[ok] macro_f1: {metrics['macro_f1']:.4f}")
    print(f"[ok] balanced_accuracy: {metrics['balanced_accuracy']:.4f}")
    print(f"[ok] recall_by_class: {metrics['recall_by_class']}")
    print(f"[ok] saved: {output_path}")


if __name__ == "__main__":
    main()
