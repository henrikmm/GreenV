"""Trace a road route via Directions API and sample equidistant points."""

import math
import requests
import polyline as pl
from geopy.distance import geodesic

from roadcapture.config import GOOGLE_MAPS_API_KEY


def get_route_polyline(origin: str, destination: str) -> list[tuple[float, float]]:
    """Return the decoded polyline (list of lat,lon) along the road."""
    url = "https://maps.googleapis.com/maps/api/directions/json"
    params = {"origin": origin, "destination": destination, "key": GOOGLE_MAPS_API_KEY}
    resp = requests.get(url, params=params, timeout=30)
    resp.raise_for_status()
    data = resp.json()

    if data["status"] != "OK":
        raise RuntimeError(f"Directions API error: {data['status']}")

    encoded = data["routes"][0]["overview_polyline"]["points"]
    return pl.decode(encoded)


def calculate_bearing(p1: tuple[float, float], p2: tuple[float, float]) -> float:
    """Azimuth in degrees from p1 to p2."""
    lat1, lon1 = math.radians(p1[0]), math.radians(p1[1])
    lat2, lon2 = math.radians(p2[0]), math.radians(p2[1])
    d_lon = lon2 - lon1
    x = math.sin(d_lon) * math.cos(lat2)
    y = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(d_lon)
    return (math.degrees(math.atan2(x, y)) + 360) % 360


def interpolate_point(p1, p2, fraction):
    return (p1[0] + fraction * (p2[0] - p1[0]), p1[1] + fraction * (p2[1] - p1[1]))


def sample_equidistant_points(points, interval_m: float = 100) -> list[dict]:
    """Walk the polyline and emit a sample every `interval_m` meters.

    Each sample is dict(lat, lon, road_bearing, km).
    """
    sampled = []
    accumulated = 0.0
    total_distance = 0.0

    for i in range(len(points) - 1):
        seg_dist = geodesic(points[i], points[i + 1]).meters
        bearing = calculate_bearing(points[i], points[i + 1])

        while accumulated <= seg_dist:
            fraction = accumulated / seg_dist if seg_dist > 0 else 0
            lat, lon = interpolate_point(points[i], points[i + 1], fraction)
            km = (total_distance + accumulated) / 1000
            sampled.append({"lat": lat, "lon": lon, "road_bearing": bearing, "km": round(km, 3)})
            accumulated += interval_m

        accumulated -= seg_dist
        total_distance += seg_dist

    return sampled
