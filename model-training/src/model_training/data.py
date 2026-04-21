from __future__ import annotations

import csv
from pathlib import Path

from PIL import Image
from torch import Tensor
from torch.utils.data import Dataset
from torchvision import transforms
from torchvision.models import EfficientNet_B0_Weights

from .config import CLASS_TO_INDEX, DATASET_ROOT


def _default_resize_size(image_size: int) -> int:
    # Mantem a mesma proporcao usada pelo preset oficial: 256 / 224.
    return round(image_size * 256 / 224)


def build_eval_transform(image_size: int = 224):
    return EfficientNet_B0_Weights.DEFAULT.transforms(
        crop_size=image_size,
        resize_size=_default_resize_size(image_size),
    )


def build_train_transform(image_size: int = 224):
    # Normalizacao do preset oficial do EfficientNet-B0.
    preset = EfficientNet_B0_Weights.DEFAULT.transforms()
    mean = list(preset.mean)
    std = list(preset.std)
    resize_size = _default_resize_size(image_size)

    return transforms.Compose(
        [
            transforms.Resize(resize_size, antialias=True),
            transforms.RandomResizedCrop(
                image_size,
                scale=(0.7, 1.0),
                ratio=(0.85, 1.15),
                antialias=True,
            ),
            transforms.RandomHorizontalFlip(p=0.5),
            transforms.RandomVerticalFlip(p=0.2),
            transforms.ColorJitter(
                brightness=0.25,
                contrast=0.25,
                saturation=0.25,
                hue=0.03,
            ),
            transforms.RandAugment(num_ops=2, magnitude=7),
            transforms.ToTensor(),
            transforms.Normalize(mean=mean, std=std),
            transforms.RandomErasing(p=0.25, scale=(0.02, 0.15)),
        ]
    )


class VegetationDataset(Dataset):
    def __init__(self, csv_path: Path, transform=None, image_size: int = 224) -> None:
        self.csv_path = Path(csv_path)
        self.image_size = image_size
        self.transform = transform or build_eval_transform(image_size=image_size)
        with self.csv_path.open(newline="", encoding="utf-8") as handle:
            self.rows = list(csv.DictReader(handle))

    def __len__(self) -> int:
        return len(self.rows)

    def __getitem__(self, index: int) -> tuple[Tensor, int]:
        row = self.rows[index]
        image_path = DATASET_ROOT / row["relative_image_path"]
        image = Image.open(image_path).convert("RGB")
        label = CLASS_TO_INDEX[row["height_class"]]
        tensor = self.transform(image)
        return tensor, label
