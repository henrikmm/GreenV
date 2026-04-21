#!/usr/bin/env python3
"""
Prepara o dataset rotulado para o pipeline de treinamento.

Etapas:
1. remove linhas sem rótulo;
2. extrai o caminho relativo local da imagem;
3. monta o identificador de grupo (direção + lado + km);
4. reserva 20% dos grupos para teste final;
5. salva CSVs limpos e um resumo do split.
"""

from __future__ import annotations

import csv
import json
import random
import urllib.parse
from collections import Counter, defaultdict
from pathlib import Path, PurePosixPath


ROOT = Path(__file__).resolve().parents[2]
SOURCE_CSV = ROOT / "labeling" / "exports" / "labels.csv"
PROCESSED_DIR = ROOT / "model-training" / "data" / "processed"
SPLITS_DIR = ROOT / "model-training" / "data" / "splits"

LABELS = ("null", "1", "2", "3")
TEST_GROUP_FRACTION = 0.20
SEARCH_RESTARTS = 300
SEARCH_STEPS = 1200
RANDOM_SEED = 42


def image_path_from_url(url: str) -> str:
    path = urllib.parse.urlparse(url).path.lstrip("/")
    if not path:
        raise ValueError(f"URL sem caminho de imagem: {url}")
    return path


def group_id_from_image_path(image_path: str) -> str:
    filename = PurePosixPath(image_path).name
    parts = filename.split("_")
    km = next((piece for piece in parts if piece.startswith("km")), None)
    if km is None:
        raise ValueError(f"Nao foi possivel extrair km de: {filename}")
    return "_".join(parts[:3] + [km])


def load_rows() -> tuple[list[dict[str, str]], int, int]:
    with SOURCE_CSV.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))

    cleaned_rows: list[dict[str, str]] = []
    dropped_unlabeled_rows = 0
    for row in rows:
        label = row["height_class"].strip()
        if not label:
            dropped_unlabeled_rows += 1
            continue

        image_path = image_path_from_url(row["image"])
        group_id = group_id_from_image_path(image_path)

        enriched = dict(row)
        enriched["height_class"] = label
        enriched["relative_image_path"] = image_path
        enriched["group_id"] = group_id
        cleaned_rows.append(enriched)

    return cleaned_rows, len(rows), dropped_unlabeled_rows


def build_group_stats(rows: list[dict[str, str]]) -> dict[str, dict[str, object]]:
    group_rows: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in rows:
        group_rows[row["group_id"]].append(row)

    group_stats: dict[str, dict[str, object]] = {}
    for group_id, items in group_rows.items():
        class_counts = Counter(row["height_class"] for row in items)
        group_stats[group_id] = {
            "rows": items,
            "size": len(items),
            "class_counts": class_counts,
        }
    return group_stats


def score_selection(
    selected_groups: set[str],
    group_stats: dict[str, dict[str, object]],
    global_counts: Counter,
    target_test_rows: int,
) -> tuple[float, Counter, int]:
    test_counts = Counter()
    test_rows = 0

    for group_id in selected_groups:
        stats = group_stats[group_id]
        test_counts.update(stats["class_counts"])  # type: ignore[arg-type]
        test_rows += int(stats["size"])

    score = 0.0

    row_gap = abs(test_rows - target_test_rows) / target_test_rows
    score += row_gap * 2.5

    for label in LABELS:
        target = global_counts[label] * TEST_GROUP_FRACTION
        observed = test_counts[label]
        score += abs(observed - target) / max(target, 1.0)

    # Penalidade extra para não deixar a classe rara sub-representada demais.
    rare_target = global_counts["3"] * TEST_GROUP_FRACTION
    score += abs(test_counts["3"] - rare_target) / max(rare_target, 1.0) * 2.0

    # Garante que todas as classes apareçam no teste final.
    missing_labels = sum(1 for label in LABELS if test_counts[label] == 0)
    score += missing_labels * 100.0

    return score, test_counts, test_rows


def choose_test_groups(
    group_stats: dict[str, dict[str, object]],
    global_counts: Counter,
    total_rows: int,
) -> tuple[list[str], Counter, int, float]:
    all_groups = sorted(group_stats)
    target_group_count = round(len(all_groups) * TEST_GROUP_FRACTION)
    target_test_rows = round(total_rows * TEST_GROUP_FRACTION)

    best_groups: set[str] | None = None
    best_score = float("inf")
    best_counts = Counter()
    best_rows = 0

    for restart in range(SEARCH_RESTARTS):
        rng = random.Random(RANDOM_SEED + restart)
        current = set(rng.sample(all_groups, target_group_count))
        current_score, current_counts, current_rows = score_selection(
            current, group_stats, global_counts, target_test_rows
        )

        improved = True
        while improved:
            improved = False
            for _ in range(SEARCH_STEPS):
                out_group = rng.choice(sorted(current))
                in_group = rng.choice(all_groups)
                if in_group in current:
                    continue

                candidate = set(current)
                candidate.remove(out_group)
                candidate.add(in_group)

                candidate_score, candidate_counts, candidate_rows = score_selection(
                    candidate, group_stats, global_counts, target_test_rows
                )

                if candidate_score < current_score:
                    current = candidate
                    current_score = candidate_score
                    current_counts = candidate_counts
                    current_rows = candidate_rows
                    improved = True
                    break

        if current_score < best_score:
            best_groups = set(current)
            best_score = current_score
            best_counts = current_counts
            best_rows = current_rows

    assert best_groups is not None
    return sorted(best_groups), best_counts, best_rows, best_score


def write_csv(path: Path, rows: list[dict[str, str]]) -> None:
    if not rows:
        raise ValueError(f"Nenhuma linha para gravar em {path}")

    fieldnames = list(rows[0].keys())
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def class_distribution(rows: list[dict[str, str]]) -> dict[str, dict[str, float]]:
    counts = Counter(row["height_class"] for row in rows)
    total = len(rows)
    return {
        label: {
            "count": counts[label],
            "pct": round((counts[label] / total) * 100.0, 2) if total else 0.0,
        }
        for label in LABELS
    }


def main() -> None:
    PROCESSED_DIR.mkdir(parents=True, exist_ok=True)
    SPLITS_DIR.mkdir(parents=True, exist_ok=True)

    rows, source_row_count, dropped_unlabeled_rows = load_rows()
    global_counts = Counter(row["height_class"] for row in rows)
    group_stats = build_group_stats(rows)

    test_groups, test_counts, test_rows, score = choose_test_groups(
        group_stats, global_counts, len(rows)
    )
    test_group_set = set(test_groups)

    train_val_rows = [row for row in rows if row["group_id"] not in test_group_set]
    test_rows_list = [row for row in rows if row["group_id"] in test_group_set]

    write_csv(PROCESSED_DIR / "labels_clean.csv", rows)
    write_csv(SPLITS_DIR / "train_val.csv", train_val_rows)
    write_csv(SPLITS_DIR / "test.csv", test_rows_list)

    summary = {
        "source_csv": str(SOURCE_CSV.relative_to(ROOT)),
        "source_row_count": source_row_count,
        "random_seed": RANDOM_SEED,
        "search_restarts": SEARCH_RESTARTS,
        "search_steps": SEARCH_STEPS,
        "cleaned_rows": len(rows),
        "dropped_unlabeled_rows": dropped_unlabeled_rows,
        "group_count": len(group_stats),
        "test_group_fraction": TEST_GROUP_FRACTION,
        "test_group_count": len(test_groups),
        "test_row_count": test_rows,
        "train_val_row_count": len(train_val_rows),
        "selection_score": round(score, 6),
        "class_distribution_total": class_distribution(rows),
        "class_distribution_train_val": class_distribution(train_val_rows),
        "class_distribution_test": class_distribution(test_rows_list),
        "test_class_counts_raw": dict(test_counts),
        "test_groups": test_groups,
    }

    with (SPLITS_DIR / "split_summary.json").open("w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=2, ensure_ascii=True)
        handle.write("\n")

    print(f"[ok] Linhas rotuladas: {len(rows)}")
    print(f"[ok] Linhas sem rotulo removidas: {dropped_unlabeled_rows}")
    print(f"[ok] Grupos totais: {len(group_stats)}")
    print(f"[ok] Grupos reservados para teste: {len(test_groups)}")
    print(f"[ok] Imagens em train_val: {len(train_val_rows)}")
    print(f"[ok] Imagens em test: {len(test_rows_list)}")
    print(f"[ok] Resumo salvo em: {SPLITS_DIR / 'split_summary.json'}")


if __name__ == "__main__":
    main()
