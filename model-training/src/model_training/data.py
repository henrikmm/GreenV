from __future__ import annotations

import csv
from pathlib import Path

from PIL import Image
from torch import Tensor
from torch.utils.data import Dataset
from torchvision.models import EfficientNet_B0_Weights

from .config import CLASS_TO_INDEX, DATASET_ROOT


def build_eval_transform():
    return EfficientNet_B0_Weights.DEFAULT.transforms()


class VegetationDataset(Dataset):
    def __init__(self, csv_path: Path, transform=None) -> None:
        self.csv_path = Path(csv_path)
        self.transform = transform or build_eval_transform()
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
