"""Shared configuration and capture-strategy constants."""

import os
from pathlib import Path
from dotenv import load_dotenv

# .env lives at the repo root (Challenge_MOTIVA/.env), shared across modules.
PROJECT_ROOT = Path(__file__).resolve().parents[2]
load_dotenv(PROJECT_ROOT / ".env")

# Dataset output is a top-level sibling, so other modules (e.g. model-training)
# can consume it without depending on data-acquisition.
DATASET_DIR = PROJECT_ROOT / "dataset"

GOOGLE_MAPS_API_KEY = os.getenv("GOOGLE_API_KEY") or os.getenv("GOOGLE_MAPS_API_KEY")

if not GOOGLE_MAPS_API_KEY:
    raise ValueError("GOOGLE_API_KEY (or GOOGLE_MAPS_API_KEY) not found in .env file")

DEFAULT_INTERVAL_METERS = 100
DEFAULT_IMAGE_SIZE = "640x640"

# Two-pass capture strategy for a divided highway. Each pass photographs the
# roadside vegetation on its own near side. Heading offsets are measured
# empirically (see scripts/check_coverage.py) as the angle between the Google
# car's travel direction and the perpendicular view of the roadside.
PASS_SP_RIO = {
    "heading_offset": 89,   # pano_heading=131.45 -> camera_yaw=220.78
    "pitch": 0,
    "fov": 90,
}
PASS_RIO_SP = {
    "heading_offset": 99,   # pano_heading=311.06 -> camera_yaw=49.88
    "pitch": -2,
    "fov": 75,
}
