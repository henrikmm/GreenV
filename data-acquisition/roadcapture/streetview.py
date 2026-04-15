"""Street View API access: panorama search (free) and image download (paid)."""

import os
import requests
from streetview import search_panoramas

from roadcapture.config import GOOGLE_MAPS_API_KEY, DEFAULT_IMAGE_SIZE


def search_panoramas_at(lat: float, lon: float) -> list[dict]:
    """Return all panoramas (current + historical) near a coordinate.

    Uses the robolyst/streetview library (free, no quota consumed). Each result
    includes `heading` — the direction the Google car was travelling — which we
    later use to tell which carriageway of a divided highway the panorama belongs to.
    """
    try:
        panos = search_panoramas(lat=lat, lon=lon)
    except Exception as e:
        print(f"  Warning: failed to search panoramas at ({lat}, {lon}): {e}")
        return []

    return [
        {"pano_id": p.pano_id, "date": p.date, "lat": p.lat, "lon": p.lon, "heading": p.heading}
        for p in panos
        if p.pano_id and p.date
    ]


def get_metadata(lat: float, lon: float) -> dict | None:
    """Query the official Street View metadata endpoint (free) to validate coverage."""
    url = "https://maps.googleapis.com/maps/api/streetview/metadata"
    params = {"location": f"{lat},{lon}", "key": GOOGLE_MAPS_API_KEY}
    resp = requests.get(url, params=params, timeout=15)
    resp.raise_for_status()
    data = resp.json()

    if data.get("status") != "OK":
        return None
    return {
        "pano_id": data.get("pano_id"),
        "date": data.get("date"),
        "lat": data["location"]["lat"],
        "lon": data["location"]["lng"],
        "status": data["status"],
    }


def download_image(
    pano_id: str,
    heading: float,
    output_path: str,
    pitch: float = 0,
    fov: int = 90,
    size: str = DEFAULT_IMAGE_SIZE,
) -> bool:
    """Download a single Street View image by pano_id. CONSUMES QUOTA (~$7/1000)."""
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    url = "https://maps.googleapis.com/maps/api/streetview"
    params = {
        "pano": pano_id,
        "heading": heading,
        "pitch": pitch,
        "fov": fov,
        "size": size,
        "key": GOOGLE_MAPS_API_KEY,
    }
    resp = requests.get(url, params=params, timeout=30)
    if resp.status_code != 200 or resp.headers.get("content-type", "").startswith("application/json"):
        print(f"  Failed to download pano {pano_id}: HTTP {resp.status_code}")
        return False
    with open(output_path, "wb") as f:
        f.write(resp.content)
    return True
