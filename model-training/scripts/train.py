#!/usr/bin/env python3
from __future__ import annotations

import argparse
from datetime import datetime
from pathlib import Path

from model_training.config import TRAIN_VAL_CSV
from model_training.training import TrainConfig, train_with_group_cv


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", type=Path, default=TRAIN_VAL_CSV)
    parser.add_argument("--output-dir", type=Path, default=None)
    parser.add_argument("--image-size", type=int, default=224)
    parser.add_argument("--num-folds", type=int, default=5)
    parser.add_argument("--max-epochs", type=int, default=20)
    parser.add_argument("--patience", type=int, default=5)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--lr", type=float, default=1e-4)
    parser.add_argument("--weight-decay", type=float, default=1e-4)
    parser.add_argument("--scheduler", choices=["none", "plateau", "cosine"], default="none")
    parser.add_argument("--scheduler-patience", type=int, default=2)
    parser.add_argument("--scheduler-factor", type=float, default=0.5)
    parser.add_argument("--num-workers", type=int, default=4)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--max-folds", type=int, default=None)
    parser.add_argument("--limit-samples", type=int, default=None)
    parser.add_argument("--no-pretrained", action="store_true")
    parser.add_argument("--no-augment", action="store_true")
    parser.add_argument("--no-weighted-sampler", action="store_true")
    parser.add_argument(
        "--sampler-power",
        type=float,
        default=1.0,
        help="Expoente da ponderacao do sampler (1.0=inverso puro, 0.5=mais suave).",
    )
    parser.add_argument("--loss", choices=["ce", "focal"], default="ce")
    parser.add_argument("--focal-gamma", type=float, default=2.0)
    parser.add_argument("--no-class-weights", action="store_true")
    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()

    output_dir = args.output_dir
    if output_dir is None:
        run_name = datetime.now().strftime("run_%Y%m%d_%H%M%S")
        output_dir = Path("artifacts") / "runs" / run_name

    config = TrainConfig(
        csv_path=str(args.csv),
        output_dir=str(output_dir),
        image_size=args.image_size,
        num_folds=args.num_folds,
        max_epochs=args.max_epochs,
        patience=args.patience,
        batch_size=args.batch_size,
        lr=args.lr,
        weight_decay=args.weight_decay,
        scheduler=args.scheduler,
        scheduler_patience=args.scheduler_patience,
        scheduler_factor=args.scheduler_factor,
        num_workers=args.num_workers,
        seed=args.seed,
        device=args.device,
        pretrained=not args.no_pretrained,
        limit_samples=args.limit_samples,
        max_folds=args.max_folds,
        augment=not args.no_augment,
        weighted_sampler=not args.no_weighted_sampler,
        sampler_power=args.sampler_power,
        loss=args.loss,
        focal_gamma=args.focal_gamma,
        use_class_weights=not args.no_class_weights,
    )

    summary = train_with_group_cv(config)
    print(f"[ok] output_dir: {output_dir}")
    print(f"[ok] image_size: {args.image_size}")
    print(f"[ok] folds_ran: {summary['num_folds_ran']}")
    print(f"[ok] macro_f1_mean: {summary['macro_f1_mean']:.4f}")
    print(f"[ok] macro_f1_std: {summary['macro_f1_std']:.4f}")


if __name__ == "__main__":
    main()
