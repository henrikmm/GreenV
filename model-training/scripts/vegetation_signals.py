#!/usr/bin/env python3
"""
Calcula sinais de densidade de vegetacao por imagem e compara entre classes.
Nao usa modelo — so processamento de pixel (RGB).

Uso:
    PYTHONPATH=src python scripts/vegetation_signals.py
    PYTHONPATH=src python scripts/vegetation_signals.py --save-csv artifacts/signals.csv
"""
from __future__ import annotations

import argparse
import csv
from collections import defaultdict
from pathlib import Path

import numpy as np
from PIL import Image

from model_training.config import CLASS_NAMES, DATASET_ROOT, TEST_CSV, TRAIN_VAL_CSV


# ---------------------------------------------------------------------------
# Sinais de vegetacao
# ---------------------------------------------------------------------------

def compute_signals(image_path: Path) -> dict[str, float]:
    img = Image.open(image_path).convert("RGB")
    # Reduz para 256x256 para velocidade (nao afeta os ratios)
    img = img.resize((256, 256), Image.BILINEAR)
    arr = np.asarray(img, dtype=np.float32)  # H x W x 3

    R, G, B = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2]
    H, W = arr.shape[:2]

    # ExG: Excess Green = 2G - R - B  (positivo = verde domina)
    exg = 2 * G - R - B  # range teorico: -510 a 510

    # Mascara de pixels "verdes": ExG > threshold e G > R e G > B
    green_mask = (exg > 10) & (G > R) & (G > B)
    green_ratio = float(green_mask.mean())

    # ExG medio nos pixels verdes (intensidade do verde)
    exg_mean_green = float(exg[green_mask].mean()) if green_mask.any() else 0.0

    # ExG medio na imagem toda (inclui fundo)
    exg_mean_all = float(exg.mean())

    # Centroide vertical do verde (0 = topo, 1 = base)
    # Vegetacao alta tende a aparecer mais no centro/topo da imagem
    if green_mask.any():
        rows = np.where(green_mask)[0]  # indices das linhas (0 = topo)
        vert_centroid = float(rows.mean() / H)
    else:
        vert_centroid = 0.5

    # Variancia de textura no canal verde (vegetacao densa = mais variada)
    green_channel = G / 255.0
    texture_var = float(green_channel.std())

    # Proporcao da metade superior da imagem que e verde
    # (vegetacao alta ocupa a parte superior)
    upper_half = green_mask[: H // 2, :]
    upper_green_ratio = float(upper_half.mean())

    # Proporcao da metade inferior
    lower_half = green_mask[H // 2 :, :]
    lower_green_ratio = float(lower_half.mean())

    # Razao superior/total (evita divisao por zero)
    upper_lower_ratio = upper_green_ratio / (lower_green_ratio + 1e-6)

    return {
        "green_ratio": green_ratio,
        "exg_mean_all": exg_mean_all,
        "exg_mean_green": exg_mean_green,
        "vert_centroid": vert_centroid,
        "texture_var": texture_var,
        "upper_green_ratio": upper_green_ratio,
        "lower_green_ratio": lower_green_ratio,
        "upper_lower_ratio": upper_lower_ratio,
    }


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def load_rows(csvs: list[Path]) -> list[dict]:
    rows = []
    seen = set()
    for csv_path in csvs:
        with csv_path.open(newline="", encoding="utf-8") as f:
            for row in csv.DictReader(f):
                rel = row["relative_image_path"]
                if rel not in seen:
                    seen.add(rel)
                    rows.append(row)
    return rows


def print_stats(signals_by_class: dict[str, list[dict]]) -> None:
    signal_keys = list(next(iter(signals_by_class.values()))[0].keys())

    col_w = 22
    class_order = [c for c in CLASS_NAMES if c in signals_by_class]

    print(f"\n{'Sinal':<{col_w}}", end="")
    for cls in class_order:
        n = len(signals_by_class[cls])
        print(f"  {f'cls={cls} (n={n})':>18}", end="")
    print()
    print("-" * (col_w + 20 * len(class_order)))

    for key in signal_keys:
        print(f"{key:<{col_w}}", end="")
        for cls in class_order:
            vals = [s[key] for s in signals_by_class[cls]]
            mean = np.mean(vals)
            std = np.std(vals)
            print(f"  {mean:>8.3f} ±{std:>6.3f}", end="")
        print()

    # Separa linha de correlacao
    print(f"\n{'Sinal':<{col_w}}  {'correlacao com classe (spearman)':>30}")
    print("-" * (col_w + 32))

    # Monta arrays para correlacao
    all_classes_idx = []
    all_signal_vals = defaultdict(list)
    for cls in class_order:
        if cls == "null":
            continue
        cls_idx = CLASS_NAMES.index(cls)
        for s in signals_by_class[cls]:
            all_classes_idx.append(cls_idx)
            for k, v in s.items():
                all_signal_vals[k].append(v)

    from scipy.stats import spearmanr
    y = np.array(all_classes_idx)
    for key in signal_keys:
        x = np.array(all_signal_vals[key])
        corr, pval = spearmanr(x, y)
        stars = "***" if pval < 0.001 else ("**" if pval < 0.01 else ("*" if pval < 0.05 else ""))
        print(f"{key:<{col_w}}  rho={corr:+.3f}  p={pval:.4f}  {stars}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--csvs", nargs="+", type=Path,
        default=[TRAIN_VAL_CSV, TEST_CSV],
    )
    parser.add_argument("--save-csv", type=Path, default=None)
    parser.add_argument("--limit", type=int, default=None,
                        help="Limita numero de imagens por debug")
    args = parser.parse_args()

    rows = load_rows(args.csvs)
    if args.limit:
        rows = rows[: args.limit]

    print(f"[...] Calculando sinais para {len(rows)} imagens...")

    signals_by_class: dict[str, list[dict]] = defaultdict(list)
    all_records = []
    errors = 0

    for i, row in enumerate(rows):
        if (i + 1) % 200 == 0:
            print(f"  {i + 1}/{len(rows)}")
        cls = row["height_class"]
        path = DATASET_ROOT / row["relative_image_path"]
        try:
            sigs = compute_signals(path)
        except Exception as e:
            errors += 1
            continue
        signals_by_class[cls].append(sigs)
        if args.save_csv:
            all_records.append({
                "relative_image_path": row["relative_image_path"],
                "height_class": cls,
                **sigs,
            })

    if errors:
        print(f"[!] {errors} imagens com erro (corrompidas ou nao encontradas)")

    print_stats(signals_by_class)

    if args.save_csv:
        args.save_csv.parent.mkdir(parents=True, exist_ok=True)
        fieldnames = ["relative_image_path", "height_class"] + list(
            next(iter(signals_by_class.values()))[0].keys()
        )
        with args.save_csv.open("w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=fieldnames)
            w.writeheader()
            w.writerows(all_records)
        print(f"\n[ok] CSV salvo em {args.save_csv}")


if __name__ == "__main__":
    main()
