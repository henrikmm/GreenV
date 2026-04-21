#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

import torch
from torch.utils.data import DataLoader, Subset

from model_training import CLASS_NAMES, VegetationDataset, create_efficientnet_b0
from model_training.config import TRAIN_VAL_CSV


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", type=Path, default=TRAIN_VAL_CSV)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--max-samples", type=int, default=8)
    parser.add_argument("--device", default="auto")
    args = parser.parse_args()

    if args.device == "auto":
        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    else:
        device = torch.device(args.device)

    dataset = VegetationDataset(args.csv)
    subset_size = min(args.max_samples, len(dataset))
    subset = Subset(dataset, list(range(subset_size)))
    loader = DataLoader(
        subset,
        batch_size=min(args.batch_size, subset_size),
        shuffle=False,
        num_workers=0,
    )

    model = create_efficientnet_b0(pretrained=True).to(device)
    model.eval()

    images, labels = next(iter(loader))
    images = images.to(device)
    labels = labels.to(device)

    with torch.no_grad():
        logits = model(images)
        probs = torch.softmax(logits, dim=1)
        preds = probs.argmax(dim=1)

    print(f"[ok] device: {device}")
    print(f"[ok] batch_shape: {tuple(images.shape)}")
    print(f"[ok] logits_shape: {tuple(logits.shape)}")
    print(f"[ok] labels: {[CLASS_NAMES[i] for i in labels.tolist()]}")
    print(f"[ok] preds: {[CLASS_NAMES[i] for i in preds.tolist()]}")
    print(f"[ok] first_probs: {[round(float(x), 4) for x in probs[0].tolist()]}")


if __name__ == "__main__":
    main()
