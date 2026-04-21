from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
from sklearn.metrics import balanced_accuracy_score, confusion_matrix, f1_score, recall_score
from sklearn.model_selection import StratifiedGroupKFold
from torch import nn
from torch.utils.data import DataLoader, Subset, WeightedRandomSampler

from .config import CLASS_NAMES, CLASS_TO_INDEX, NUM_CLASSES
from .data import VegetationDataset, build_train_transform
from .models import create_efficientnet_b0


@dataclass
class TrainConfig:
    csv_path: str
    output_dir: str
    image_size: int = 224
    num_folds: int = 5
    max_epochs: int = 20
    patience: int = 5
    batch_size: int = 32
    lr: float = 1e-4
    weight_decay: float = 1e-4
    scheduler: str = "none"
    scheduler_patience: int = 2
    scheduler_factor: float = 0.5
    num_workers: int = 4
    seed: int = 42
    device: str = "auto"
    pretrained: bool = True
    limit_samples: int | None = None
    max_folds: int | None = None
    augment: bool = True
    weighted_sampler: bool = True
    sampler_power: float = 1.0
    loss: str = "ce"
    focal_gamma: float = 2.0
    use_class_weights: bool = True


class FocalLoss(nn.Module):
    def __init__(self, weight: torch.Tensor | None = None, gamma: float = 2.0) -> None:
        super().__init__()
        self.gamma = gamma
        self.register_buffer("weight", weight if weight is not None else None, persistent=False)

    def forward(self, logits: torch.Tensor, targets: torch.Tensor) -> torch.Tensor:
        ce = F.cross_entropy(logits, targets, weight=self.weight, reduction="none")
        pt = torch.exp(-ce)
        focal = ((1.0 - pt) ** self.gamma) * ce
        return focal.mean()


def set_seed(seed: int) -> None:
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)


def resolve_device(device_arg: str) -> torch.device:
    if device_arg == "auto":
        return torch.device("cuda" if torch.cuda.is_available() else "cpu")
    return torch.device(device_arg)


def build_sample_arrays(dataset: VegetationDataset) -> tuple[np.ndarray, np.ndarray]:
    labels = np.asarray(
        [CLASS_TO_INDEX[row["height_class"]] for row in dataset.rows],
        dtype=np.int64,
    )
    groups = np.asarray([row["group_id"] for row in dataset.rows], dtype=object)
    return labels, groups


def maybe_limit_indices(indices: np.ndarray, limit_samples: int | None) -> np.ndarray:
    if limit_samples is None:
        return indices
    return indices[: min(limit_samples, len(indices))]


def build_sampler(labels: np.ndarray, power: float) -> WeightedRandomSampler:
    counts = np.bincount(labels, minlength=NUM_CLASSES).astype(np.float64)
    class_w = np.zeros_like(counts)
    nz = counts > 0
    class_w[nz] = (1.0 / counts[nz]) ** power
    sample_w = class_w[labels]
    sample_w = sample_w / sample_w.sum() * len(labels)
    return WeightedRandomSampler(
        weights=torch.as_tensor(sample_w, dtype=torch.double),
        num_samples=len(labels),
        replacement=True,
    )


def build_loader(
    dataset: VegetationDataset,
    indices: np.ndarray,
    batch_size: int,
    shuffle: bool,
    num_workers: int,
    pin_memory: bool,
    sampler=None,
) -> DataLoader:
    subset = Subset(dataset, indices.tolist())
    effective_bs = min(batch_size, len(subset)) if len(subset) else batch_size
    return DataLoader(
        subset,
        batch_size=effective_bs,
        shuffle=shuffle if sampler is None else False,
        sampler=sampler,
        num_workers=num_workers,
        pin_memory=pin_memory,
    )


def compute_class_weights(labels: np.ndarray) -> torch.Tensor:
    counts = np.bincount(labels, minlength=NUM_CLASSES).astype(np.float32)
    weights = np.zeros(NUM_CLASSES, dtype=np.float32)
    non_zero = counts > 0
    weights[non_zero] = counts.sum() / (NUM_CLASSES * counts[non_zero])
    return torch.tensor(weights, dtype=torch.float32)


def build_criterion(config: TrainConfig, class_weights: torch.Tensor | None) -> nn.Module:
    weight = class_weights if config.use_class_weights else None
    if config.loss == "ce":
        return nn.CrossEntropyLoss(weight=weight)
    if config.loss == "focal":
        return FocalLoss(weight=weight, gamma=config.focal_gamma)
    raise ValueError(f"Loss invalida: {config.loss}")


def train_one_epoch(
    model: nn.Module,
    loader: DataLoader,
    optimizer: torch.optim.Optimizer,
    criterion: nn.Module,
    device: torch.device,
) -> dict[str, float]:
    model.train()
    running_loss = 0.0
    total = 0

    for images, labels in loader:
        images = images.to(device, non_blocking=True)
        labels = labels.to(device, non_blocking=True)

        optimizer.zero_grad(set_to_none=True)
        logits = model(images)
        loss = criterion(logits, labels)
        loss.backward()
        optimizer.step()

        batch_size = labels.size(0)
        running_loss += float(loss.item()) * batch_size
        total += batch_size

    return {"loss": running_loss / max(total, 1)}


def evaluate(
    model: nn.Module,
    loader: DataLoader,
    criterion: nn.Module,
    device: torch.device,
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
            loss = criterion(logits, labels)
            preds = logits.argmax(dim=1)

            batch_size = labels.size(0)
            running_loss += float(loss.item()) * batch_size
            total += batch_size

            y_true.extend(labels.cpu().tolist())
            y_pred.extend(preds.cpu().tolist())

    if not y_true:
        raise ValueError("Validacao sem amostras.")

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


def save_json(path: Path, payload: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2, ensure_ascii=True)
        handle.write("\n")


def build_scheduler(
    optimizer: torch.optim.Optimizer,
    config: TrainConfig,
):
    if config.scheduler == "none":
        return None
    if config.scheduler == "plateau":
        return torch.optim.lr_scheduler.ReduceLROnPlateau(
            optimizer,
            mode="max",
            factor=config.scheduler_factor,
            patience=config.scheduler_patience,
        )
    if config.scheduler == "cosine":
        return torch.optim.lr_scheduler.CosineAnnealingLR(
            optimizer,
            T_max=config.max_epochs,
        )
    raise ValueError(f"Scheduler invalido: {config.scheduler}")


def train_fold(
    train_dataset: VegetationDataset,
    eval_dataset: VegetationDataset,
    train_indices: np.ndarray,
    val_indices: np.ndarray,
    fold_index: int,
    config: TrainConfig,
    device: torch.device,
    output_dir: Path,
) -> dict[str, object]:
    pin_memory = device.type == "cuda"

    train_labels = np.asarray(
        [CLASS_TO_INDEX[train_dataset.rows[i]["height_class"]] for i in train_indices]
    )

    sampler = None
    if config.weighted_sampler:
        sampler = build_sampler(train_labels, power=config.sampler_power)

    train_loader = build_loader(
        train_dataset,
        train_indices,
        batch_size=config.batch_size,
        shuffle=True,
        num_workers=config.num_workers,
        pin_memory=pin_memory,
        sampler=sampler,
    )
    val_loader = build_loader(
        eval_dataset,
        val_indices,
        batch_size=config.batch_size,
        shuffle=False,
        num_workers=config.num_workers,
        pin_memory=pin_memory,
    )

    model = create_efficientnet_b0(pretrained=config.pretrained).to(device)
    class_weights = compute_class_weights(train_labels).to(device)
    criterion = build_criterion(config, class_weights).to(device)
    optimizer = torch.optim.AdamW(
        model.parameters(),
        lr=config.lr,
        weight_decay=config.weight_decay,
    )
    scheduler = build_scheduler(optimizer, config)

    fold_dir = output_dir / f"fold_{fold_index:02d}"
    fold_dir.mkdir(parents=True, exist_ok=True)
    history: list[dict[str, object]] = []
    best_macro_f1 = float("-inf")
    best_epoch = -1
    best_balanced_accuracy = float("-inf")
    best_class3_recall = float("-inf")
    epochs_without_improvement = 0

    for epoch in range(1, config.max_epochs + 1):
        train_metrics = train_one_epoch(model, train_loader, optimizer, criterion, device)
        val_metrics = evaluate(model, val_loader, criterion, device)

        record = {
            "epoch": epoch,
            "train_loss": train_metrics["loss"],
            "val_loss": val_metrics["loss"],
            "val_macro_f1": val_metrics["macro_f1"],
            "val_balanced_accuracy": val_metrics["balanced_accuracy"],
            "val_recall_by_class": val_metrics["recall_by_class"],
            "lr": float(optimizer.param_groups[0]["lr"]),
        }
        history.append(record)

        if float(val_metrics["macro_f1"]) > best_macro_f1:
            best_macro_f1 = float(val_metrics["macro_f1"])
            best_balanced_accuracy = float(val_metrics["balanced_accuracy"])
            best_class3_recall = float(val_metrics["recall_by_class"]["3"])
            best_epoch = epoch
            epochs_without_improvement = 0
            checkpoint = {
                "epoch": epoch,
                "model_state_dict": model.state_dict(),
                "optimizer_state_dict": optimizer.state_dict(),
                "config": asdict(config),
                "val_metrics": val_metrics,
                "class_names": CLASS_NAMES,
            }
            torch.save(checkpoint, fold_dir / "best.pt")
            save_json(fold_dir / "best_metrics.json", val_metrics)
        else:
            epochs_without_improvement += 1

        print(
            f"[fold {fold_index:02d}] epoch {epoch:02d} "
            f"train_loss={train_metrics['loss']:.4f} "
            f"val_loss={val_metrics['loss']:.4f} "
            f"val_macro_f1={val_metrics['macro_f1']:.4f} "
            f"lr={optimizer.param_groups[0]['lr']:.6f}"
        )

        if scheduler is not None:
            if config.scheduler == "plateau":
                scheduler.step(float(val_metrics["macro_f1"]))
            else:
                scheduler.step()

        if epochs_without_improvement >= config.patience:
            print(
                f"[fold {fold_index:02d}] early stopping "
                f"(sem melhora por {config.patience} epochs)"
            )
            break

    fold_summary = {
        "fold": fold_index,
        "best_epoch": best_epoch,
        "best_macro_f1": best_macro_f1,
        "best_balanced_accuracy": best_balanced_accuracy,
        "best_class3_recall": best_class3_recall,
        "checkpoint_path": str(fold_dir / "best.pt"),
        "history": history,
        "train_size": int(len(train_indices)),
        "val_size": int(len(val_indices)),
    }
    save_json(fold_dir / "history.json", fold_summary)
    return fold_summary


def summarize_folds(output_dir: Path, fold_summaries: list[dict[str, object]]) -> dict[str, object]:
    macro_scores = [float(item["best_macro_f1"]) for item in fold_summaries]
    balanced_scores = [float(item["best_balanced_accuracy"]) for item in fold_summaries]
    class3_scores = [float(item["best_class3_recall"]) for item in fold_summaries]
    best_fold = max(
        fold_summaries,
        key=lambda item: (
            float(item["best_macro_f1"]),
            float(item["best_class3_recall"]),
            float(item["best_balanced_accuracy"]),
        ),
    )
    summary = {
        "folds": fold_summaries,
        "macro_f1_mean": float(np.mean(macro_scores)),
        "macro_f1_std": float(np.std(macro_scores)),
        "balanced_accuracy_mean": float(np.mean(balanced_scores)),
        "balanced_accuracy_std": float(np.std(balanced_scores)),
        "class3_recall_mean": float(np.mean(class3_scores)),
        "class3_recall_std": float(np.std(class3_scores)),
        "selected_fold": {
            "fold": int(best_fold["fold"]),
            "checkpoint_path": str(best_fold["checkpoint_path"]),
            "best_epoch": int(best_fold["best_epoch"]),
            "macro_f1": float(best_fold["best_macro_f1"]),
            "class3_recall": float(best_fold["best_class3_recall"]),
            "balanced_accuracy": float(best_fold["best_balanced_accuracy"]),
            "selection_rule": "highest macro_f1, then highest class3 recall, then highest balanced accuracy",
        },
        "num_folds_ran": len(fold_summaries),
    }
    save_json(output_dir / "cv_summary.json", summary)
    return summary


def train_with_group_cv(config: TrainConfig) -> dict[str, object]:
    set_seed(config.seed)
    device = resolve_device(config.device)
    output_dir = Path(config.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    train_transform = build_train_transform(config.image_size) if config.augment else None
    train_dataset = VegetationDataset(
        Path(config.csv_path),
        transform=train_transform,
        image_size=config.image_size,
    )
    eval_dataset = VegetationDataset(
        Path(config.csv_path),
        image_size=config.image_size,
    )
    labels, groups = build_sample_arrays(eval_dataset)

    splitter = StratifiedGroupKFold(
        n_splits=config.num_folds,
        shuffle=True,
        random_state=config.seed,
    )

    fold_summaries: list[dict[str, object]] = []
    for fold_index, (train_idx, val_idx) in enumerate(
        splitter.split(np.zeros(len(labels)), labels, groups),
        start=1,
    ):
        if config.max_folds is not None and fold_index > config.max_folds:
            break

        train_idx = maybe_limit_indices(train_idx, config.limit_samples)
        val_idx = maybe_limit_indices(val_idx, config.limit_samples)

        fold_summary = train_fold(
            train_dataset=train_dataset,
            eval_dataset=eval_dataset,
            train_indices=train_idx,
            val_indices=val_idx,
            fold_index=fold_index,
            config=config,
            device=device,
            output_dir=output_dir,
        )
        fold_summaries.append(fold_summary)

    run_summary = {
        "config": asdict(config),
        "device": str(device),
        **summarize_folds(output_dir, fold_summaries),
    }
    save_json(output_dir / "run_config.json", run_summary)
    return run_summary
