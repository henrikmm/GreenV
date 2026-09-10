#!/usr/bin/env python3
"""
Phase 3 — fetch real historical weather for the SP-021 / Rodoanel Oeste corridor.

Primary source : Open-Meteo Historical Weather API, model `era5_seamless`.
  * temperature_2m_{min,mean,max}, precipitation_sum, rain_sum,
    relative_humidity_2m_mean  -> ERA5-Land (~9-11 km, native 0.1 deg grid)
  * shortwave_radiation_sum, et0_fao_evapotranspiration
    -> ERA5 (~25 km, 0.25 deg grid), blended in by `era5_seamless`
    (era5_land alone returns NULL for these two -- verified 2026-09-09).

Provenance of every row written by build_weather.py is `weather_real`.

SPATIAL REPRESENTATION (V1, reproducible, NOT official):
  The SP-021 centre line is approximated by the anchor polyline already present
  in GreenV (`apps/web/src/utils/classification.js` ROAD_ANCHORS). Those anchors
  are approximate; every coordinate derived from them is recorded with
  provenance = 'hypothesis'. They are NOT official highway coordinates.

  The corridor is sampled every ~2 km, each sample snapped to the Open-Meteo
  grid cell that the API actually returns, then de-duplicated. ERA5(-Land) cells
  are far larger than a 500 m trecho, so many trechos share one weather cell --
  this is recorded explicitly in the trecho->cell map and in weather-eda.md.

Raw API responses are saved verbatim under data/real/weather/raw/ and never
edited. build_weather.py reads only from there.

stdlib only (no pandas / requests in this environment).
"""
from __future__ import annotations
import json, sys, time, urllib.parse, urllib.request
from datetime import date, datetime, timezone
from pathlib import Path

# --------------------------------------------------------------------------
# Config
# --------------------------------------------------------------------------
MODULE_ROOT = Path(__file__).resolve().parents[1]
RAW_DIR = MODULE_ROOT / "data" / "real" / "weather" / "raw"

OPEN_METEO_URL = "https://archive-api.open-meteo.com/v1/archive"
MODEL = "era5_seamless"
TIMEZONE = "America/Sao_Paulo"
LICENSE = "CC-BY-4.0 (Open-Meteo; data by Copernicus Climate Change Service / ECMWF ERA5, ERA5-Land)"
ATTRIBUTION = "Weather data by Open-Meteo.com (CC BY 4.0); source: Copernicus / ECMWF ERA5 & ERA5-Land reanalysis"

START_DATE = "2023-01-01"        # Phase 3 asks for at least 2023-2026; wider ranges hit
END_DATE = "2026-09-01"          # Open-Meteo free-tier call-weight limits (HTTP 429).
INTER_REQUEST_SLEEP_S = 12       # be polite to the free API
RATE_LIMIT_BACKOFF_S = 65       # HTTP 429 -> wait for the per-minute window to reset

DAILY_VARS = [
    "temperature_2m_min",
    "temperature_2m_mean",
    "temperature_2m_max",
    "precipitation_sum",
    "rain_sum",
    "shortwave_radiation_sum",
    "et0_fao_evapotranspiration",
    "relative_humidity_2m_mean",
    "wind_speed_10m_mean",
]

# Approximate SP-021 centre line, from GreenV apps/web ROAD_ANCHORS.
# provenance = 'hypothesis'. NOT official coordinates.
ROAD_ANCHORS = [
    (0.0,  -23.4080, -46.7290),
    (5.0,  -23.4310, -46.7520),
    (10.0, -23.4600, -46.7700),
    (15.0, -23.4950, -46.7850),
    (20.0, -23.5400, -46.8000),
    (25.0, -23.5850, -46.8150),
    (29.3, -23.6290, -46.8300),
]
KM_MIN, KM_MAX = 0.0, 29.3
SAMPLE_STEP_KM = 2.0


def interp_latlon(km: float) -> tuple[float, float]:
    """Linear interpolation of (lat, lon) along the anchor polyline at a given km."""
    a = ROAD_ANCHORS
    if km <= a[0][0]:
        return a[0][1], a[0][2]
    if km >= a[-1][0]:
        return a[-1][1], a[-1][2]
    for i in range(len(a) - 1):
        k0, lat0, lon0 = a[i]
        k1, lat1, lon1 = a[i + 1]
        if k0 <= km <= k1:
            t = (km - k0) / (k1 - k0)
            return lat0 + t * (lat1 - lat0), lon0 + t * (lon1 - lon0)
    return a[-1][1], a[-1][2]


def sample_points() -> list[tuple[float, float, float]]:
    pts, km = [], KM_MIN
    while km < KM_MAX:
        lat, lon = interp_latlon(km)
        pts.append((round(km, 2), round(lat, 4), round(lon, 4)))
        km += SAMPLE_STEP_KM
    lat, lon = interp_latlon(KM_MAX)
    pts.append((KM_MAX, round(lat, 4), round(lon, 4)))
    return pts


def fetch(lat: float, lon: float) -> dict:
    q = {
        "latitude": lat, "longitude": lon,
        "start_date": START_DATE, "end_date": END_DATE,
        "daily": ",".join(DAILY_VARS),
        "timezone": TIMEZONE,
        "models": MODEL,
        "windspeed_unit": "ms",           # ask for m/s directly
        "precipitation_unit": "mm",
    }
    url = OPEN_METEO_URL + "?" + urllib.parse.urlencode(q)
    for attempt in range(6):
        try:
            with urllib.request.urlopen(url, timeout=180) as r:
                return {"url": url, "query": q, "response": json.load(r)}
        except urllib.error.HTTPError as e:  # noqa: PERF203
            if e.code == 429 and attempt < 5:
                print(f"    HTTP 429; backing off {RATE_LIMIT_BACKOFF_S}s (attempt {attempt + 1}/6)")
                time.sleep(RATE_LIMIT_BACKOFF_S)
                continue
            raise
        except Exception:  # noqa: BLE001
            if attempt == 5:
                raise
            time.sleep(5 * (attempt + 1))
    raise RuntimeError("unreachable")


def main() -> int:
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    retrieved_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    pts = sample_points()
    cells: dict[tuple[float, float], dict] = {}   # returned cell -> record
    point_to_cell: list[dict] = []

    # Predict the 0.1-degree grid cell for each sample point so the run is
    # resumable: only fetch a cell whose raw file is missing. (Verified: the
    # Open-Meteo cell equals round(lat,1), round(lon,1) for this corridor.)
    predicted: dict[tuple[float, float], tuple[float, float]] = {}
    for km, lat, lon in pts:
        predicted[(round(lat, 1), round(lon, 1))] = (lat, lon)

    for km, lat, lon in pts:
        pcell = (round(lat, 1), round(lon, 1))
        pfile = RAW_DIR / f"openmeteo_{MODEL}_{pcell[0]}_{pcell[1]}_{START_DATE}_{END_DATE}.json"
        if pfile.exists():
            existing = json.loads(pfile.read_text(encoding="utf-8"))
            cl = (round(float(existing["returned_cell_latitude"]), 4),
                  round(float(existing["returned_cell_longitude"]), 4))
            point_to_cell.append({"sample_km": km, "query_lat": lat, "query_lon": lon,
                                  "cell_lat": cl[0], "cell_lon": cl[1]})
            if cl not in cells:
                cells[cl] = {"grid_cell_id": existing["grid_cell_id"], "file": pfile.name,
                             "cell_lat": cl[0], "cell_lon": cl[1],
                             "elevation_m": existing.get("elevation_m")}
                print(f"  km {km:>5}  cell ({cl[0]},{cl[1]})  [cached {pfile.name}]")
            else:
                print(f"  km {km:>5}  cell ({cl[0]},{cl[1]})  [cached, dedup]")
            continue

        rec = fetch(lat, lon)
        time.sleep(INTER_REQUEST_SLEEP_S)
        resp = rec["response"]
        cell_lat = round(float(resp["latitude"]), 4)
        cell_lon = round(float(resp["longitude"]), 4)
        key = (cell_lat, cell_lon)
        point_to_cell.append({
            "sample_km": km, "query_lat": lat, "query_lon": lon,
            "cell_lat": cell_lat, "cell_lon": cell_lon,
        })
        if key not in cells:
            grid_cell_id = f"open-meteo:era5_seamless:{cell_lat}:{cell_lon}"
            fname = f"openmeteo_{MODEL}_{cell_lat}_{cell_lon}_{START_DATE}_{END_DATE}.json".replace(" ", "")
            payload = {
                "provider": "open-meteo",
                "model": MODEL,
                "grid_cell_id": grid_cell_id,
                "query_url": rec["url"],
                "query_params": rec["query"],
                "queried_latitude": lat,
                "queried_longitude": lon,
                "returned_cell_latitude": cell_lat,
                "returned_cell_longitude": cell_lon,
                "elevation_m": resp.get("elevation"),
                "timezone": resp.get("timezone"),
                "timezone_abbreviation": resp.get("timezone_abbreviation"),
                "utc_offset_seconds": resp.get("utc_offset_seconds"),
                "daily_units": resp.get("daily_units"),
                "spatial_resolution_note": (
                    "temperature_2m_*, precipitation_sum, rain_sum, relative_humidity_2m_mean "
                    "from ERA5-Land (~9-11 km, 0.1 deg). shortwave_radiation_sum and "
                    "et0_fao_evapotranspiration from ERA5 (~25 km, 0.25 deg), blended by era5_seamless."
                ),
                "temporal_resolution": "daily",
                "start_date": START_DATE,
                "end_date": END_DATE,
                "retrieved_at": retrieved_at,
                "license": LICENSE,
                "attribution": ATTRIBUTION,
                "provenance": "weather_real",
                "raw_response": resp,
            }
            (RAW_DIR / fname).write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")
            cells[key] = {"grid_cell_id": grid_cell_id, "file": fname,
                          "cell_lat": cell_lat, "cell_lon": cell_lon,
                          "elevation_m": resp.get("elevation")}
            print(f"  km {km:>5}  query ({lat},{lon}) -> cell ({cell_lat},{cell_lon})  saved {fname}")
        else:
            print(f"  km {km:>5}  query ({lat},{lon}) -> cell ({cell_lat},{cell_lon})  [dedup]")

    manifest = {
        "provider": "open-meteo",
        "model": MODEL,
        "api": OPEN_METEO_URL,
        "timezone": TIMEZONE,
        "start_date": START_DATE,
        "end_date": END_DATE,
        "daily_variables": DAILY_VARS,
        "windspeed_unit": "ms",
        "precipitation_unit": "mm",
        "retrieved_at": retrieved_at,
        "license": LICENSE,
        "attribution": ATTRIBUTION,
        "provenance": "weather_real",
        "spatial_representation": {
            "source": "GreenV apps/web ROAD_ANCHORS (approximate); provenance=hypothesis; NOT official",
            "road_anchors_km_lat_lon": ROAD_ANCHORS,
            "sample_step_km": SAMPLE_STEP_KM,
            "sample_points": [{"km": k, "lat": la, "lon": lo} for k, la, lo in pts],
            "point_to_cell": point_to_cell,
            "distinct_cells": [c for c in cells.values()],
            "n_distinct_cells": len(cells),
        },
    }
    (RAW_DIR / "query_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\n{len(pts)} sample points -> {len(cells)} distinct weather cells")
    print(f"manifest: {RAW_DIR / 'query_manifest.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
