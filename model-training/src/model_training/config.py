from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[3]
DATASET_ROOT = PROJECT_ROOT / "dataset"
TRAIN_VAL_CSV = PROJECT_ROOT / "model-training" / "data" / "splits" / "train_val.csv"
TEST_CSV = PROJECT_ROOT / "model-training" / "data" / "splits" / "test.csv"

CLASS_NAMES = ["null", "1", "2", "3"]
CLASS_TO_INDEX = {name: idx for idx, name in enumerate(CLASS_NAMES)}
NUM_CLASSES = len(CLASS_NAMES)
