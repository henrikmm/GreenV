"""Logic for handling a divided highway's two carriageways.

A divided highway has two independent roadways, one per travel direction. Google
Street View captures each as its own set of panoramas, keyed by the direction
the Google car was driving (the pano's `heading`). This module provides:

  * `perpendicular_offset` — shift a sample point ~20m sideways so Street View
    returns panoramas from the *opposite* carriageway (otherwise a search at the
    median only returns panoramas from the nearer lane).
  * `classify_pano_carriageway` — given a pano's heading and the road's bearing,
    decide which carriageway (SP_RIO / RIO_SP) it belongs to.
  * `build_filename` — canonical naming convention for the output JPEGs.
"""

import math

from roadcapture.config import PASS_SP_RIO, PASS_RIO_SP


# Sideways shift applied to sample points when searching for panoramas, so the
# streetview library picks up coverage from the opposite carriageway too.
PERPENDICULAR_OFFSET_M = 20

# A pano is assigned to a carriageway if its heading is within this tolerance
# of either the road bearing (SP_RIO) or the reversed bearing (RIO_SP).
HEADING_TOLERANCE_DEG = 60

PASS_CONFIGS = {
    "SP_RIO": {**PASS_SP_RIO, "side": "L", "direction": "SP→Rio"},
    "RIO_SP": {**PASS_RIO_SP, "side": "R", "direction": "Rio→SP"},
}


def perpendicular_offset(
    lat: float, lon: float, road_bearing: float, offset_m: float, side: int
) -> tuple[float, float]:
    """Return a point offset `offset_m` meters perpendicular to `road_bearing`.

    side: +1 = right of travel direction, -1 = left.
    """
    perp_bearing = (road_bearing + 90 * side) % 360
    br = math.radians(perp_bearing)
    dlat = (offset_m * math.cos(br)) / 111320
    dlon = (offset_m * math.sin(br)) / (111320 * math.cos(math.radians(lat)))
    return lat + dlat, lon + dlon


def angular_diff(a: float, b: float) -> float:
    """Minimum unsigned angular difference between two headings in degrees."""
    d = abs(a - b) % 360
    return min(d, 360 - d)


def classify_pano_carriageway(pano_heading: float, road_bearing: float) -> str | None:
    """Return 'SP_RIO', 'RIO_SP', or None.

    'SP_RIO' if the pano was captured going in the road's bearing direction,
    'RIO_SP' if reversed, None if heading doesn't fit either (±HEADING_TOLERANCE_DEG).
    """
    if angular_diff(pano_heading, road_bearing) <= HEADING_TOLERANCE_DEG:
        return "SP_RIO"
    if angular_diff(pano_heading, (road_bearing + 180) % 360) <= HEADING_TOLERANCE_DEG:
        return "RIO_SP"
    return None


def build_filename(
    pass_name: str, side: str, km: float, date: str,
    heading: float, pitch: float, fov: int,
) -> str:
    """Canonical filename: {pass}_{side}_km{km}_{date}_h{heading}_p{pitch}_f{fov}.jpg."""
    return (
        f"{pass_name}_{side}"
        f"_km{km:07.3f}"
        f"_{date}"
        f"_h{heading:.0f}"
        f"_p{pitch:.0f}"
        f"_f{fov}"
        ".jpg"
    )
