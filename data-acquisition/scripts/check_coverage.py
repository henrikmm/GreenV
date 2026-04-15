"""Probe Street View coverage at a coordinate, FREE (no image quota used).

Use this before launching `capture.py` to sanity-check that the road has
Street View coverage and to see how many historical panoramas exist.

Usage (from repo root):
    python -m scripts.check_coverage <lat> <lon>
"""

import json
import sys

# Allow running as `python scripts/check_coverage.py` from data-acquisition/.
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from roadcapture.streetview import get_metadata, search_panoramas_at


def test_coverage(lat: float, lon: float):
    print(f"Testing coverage at ({lat}, {lon})...\n")

    print("--- Official Metadata API (free, no quota) ---")
    meta = get_metadata(lat, lon)
    print(json.dumps(meta, indent=2) if meta else "  No Street View coverage at this location.")

    print("\n--- Historical Panoramas (free, no quota) ---")
    panoids = search_panoramas_at(lat, lon)
    if panoids:
        for p in panoids:
            print(f"  {p['date']:>7s}  pano_id={p['pano_id'][:20]}...  ({p['lat']:.6f}, {p['lon']:.6f})")
        print(f"\n  Total: {len(panoids)} panorama(s) across different dates")
    else:
        print("  No historical panoramas found.")

    return meta, panoids


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: python scripts/check_coverage.py <lat> <lon>")
        print("Example: python scripts/check_coverage.py -23.5505 -46.6333")
        sys.exit(1)
    test_coverage(float(sys.argv[1]), float(sys.argv[2]))
