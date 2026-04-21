#!/usr/bin/env python3
"""
Roda ensemble + TTA em todos os CSVs do dataset e salva um JSON de predicoes.
Output: artifacts/predictions.json  {relative_image_path: {pred, confidence, probs}}
"""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

import numpy as np
import torch
from torch.utils.data import DataLoader, Dataset
from torchvision import transforms
from torchvision.models import EfficientNet_B0_Weights
from PIL import Image

from model_training.config import CLASS_NAMES, DATASET_ROOT, TEST_CSV, TRAIN_VAL_CSV
from model_training import create_efficientnet_b0


def build_eval_transform(image_size: int = 320):
    preset = EfficientNet_B0_Weights.DEFAULT.transforms()
    resize = round(image_size * 256 / 224)
    return EfficientNet_B0_Weights.DEFAULT.transforms(
        crop_size=image_size,
        resize_size=resize,
    )


class PathDataset(Dataset):
    def __init__(self, paths: list[Path], image_size: int) -> None:
        self.paths = paths
        self.transform = build_eval_transform(image_size)

    def __len__(self):
        return len(self.paths)

    def __getitem__(self, idx):
        img = Image.open(self.paths[idx]).convert("RGB")
        return self.transform(img)


def collect_paths(csvs: list[Path]) -> list[str]:
    seen = set()
    paths = []
    for csv_path in csvs:
        with csv_path.open(newline="", encoding="utf-8") as f:
            for row in csv.DictReader(f):
                rel = row["relative_image_path"]
                if rel not in seen:
                    seen.add(rel)
                    paths.append(rel)
    return paths


def run_ensemble_tta(
    rel_paths: list[str],
    checkpoints: list[Path],
    image_size: int,
    batch_size: int,
    num_workers: int,
    device: torch.device,
) -> np.ndarray:
    abs_paths = [DATASET_ROOT / p for p in rel_paths]
    dataset = PathDataset(abs_paths, image_size)
    loader = DataLoader(dataset, batch_size=batch_size, shuffle=False,
                        num_workers=num_workers, pin_memory=(device.type == "cuda"))

    probs_acc: np.ndarray | None = None

    with torch.no_grad():
        for ckpt_path in checkpoints:
            ckpt = torch.load(ckpt_path, map_location="cpu")
            model = create_efficientnet_b0(pretrained=False)
            model.load_state_dict(ckpt["model_state_dict"])
            model = model.to(device).eval()

            fold_probs = []
            for images in loader:
                images = images.to(device)
                p = torch.softmax(model(images), dim=1)
                p_flip = torch.softmax(model(torch.flip(images, dims=[3])), dim=1)
                fold_probs.append(((p + p_flip) / 2).cpu().numpy())

            fold_probs_arr = np.concatenate(fold_probs, axis=0)
            probs_acc = fold_probs_arr if probs_acc is None else probs_acc + fold_probs_arr
            print(f"  [ok] {ckpt_path.parent.name}")

    assert probs_acc is not None
    return probs_acc / len(checkpoints)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-dir", type=Path, default=Path("artifacts/official"))
    parser.add_argument("--image-size", type=int, default=320)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--num-workers", type=int, default=4)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--output", type=Path, default=Path("artifacts/predictions.json"))
    parser.add_argument(
        "--csvs", nargs="+", type=Path,
        default=[TRAIN_VAL_CSV, TEST_CSV],
        help="CSVs com relative_image_path para inferencia",
    )
    args = parser.parse_args()

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu") \
        if args.device == "auto" else torch.device(args.device)

    checkpoints = sorted(args.run_dir.glob("fold_*/best.pt"))
    if not checkpoints:
        raise FileNotFoundError(f"Nenhum checkpoint em {args.run_dir}/fold_*/best.pt")

    print(f"[...] {len(checkpoints)} folds, device={device}")

    rel_paths = collect_paths(args.csvs)
    print(f"[...] {len(rel_paths)} imagens unicas para inferencia")

    avg_probs = run_ensemble_tta(
        rel_paths, checkpoints, args.image_size,
        args.batch_size, args.num_workers, device,
    )

    results: dict[str, object] = {}
    for rel, probs in zip(rel_paths, avg_probs):
        pred_idx = int(probs.argmax())
        results[rel] = {
            "pred": CLASS_NAMES[pred_idx],
            "confidence": float(probs[pred_idx]),
            "probs": {CLASS_NAMES[i]: float(probs[i]) for i in range(len(CLASS_NAMES))},
        }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as f:
        json.dump(results, f, indent=2, ensure_ascii=True)
        f.write("\n")

    print(f"[ok] {len(results)} predicoes salvas em {args.output}")


if __name__ == "__main__":
    main()
