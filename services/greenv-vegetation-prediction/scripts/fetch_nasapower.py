#!/usr/bin/env python3
"""
Phase 3 — independent cross-check source: NASA POWER (MERRA-2 + CERES/SYN1deg).

INMET was the intended secondary source but its API is unreachable from this
environment (connection reset on apitempo.inmet.gov.br and portal.inmet.gov.br
- verified 2026-09-09). NASA POWER is used instead: also real, and reanalysis-
independent of ERA5, so a genuine cross-check rather than a second view of the
same model.

POWER meteorology resolution is ~0.5 x 0.625 deg -- the whole 29 km corridor
falls in ONE POWER cell, so a single point at the corridor midpoint is fetched.

Raw response saved verbatim under data/real/weather/raw/. stdlib only.
"""
from __future__ import annotations
import json, sys, urllib.parse, urllib.request
from datetime import datetime, timezone
from pathlib import Path

MODULE_ROOT = Path(__file__).resolve().parents[1]
RAW_DIR = MODULE_ROOT / "data" / "real" / "weather" / "raw"

URL = "https://power.larc.nasa.gov/api/temporal/daily/point"
LAT, LON = -23.50, -46.80          # corridor midpoint (from GreenV anchors; provenance=hypothesis)
START, END = "20230101", "20260901"
PARAMS = "T2M_MIN,T2M,T2M_MAX,PRECTOTCORR,ALLSKY_SFC_SW_DWN,RH2M,EVPTRNS"
LICENSE = "NASA POWER — free and open (no restriction); cite NASA/POWER"


def main() -> int:
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    q = {
        "parameters": PARAMS, "community": "AG",
        "longitude": LON, "latitude": LAT,
        "start": START, "end": END, "format": "JSON",
    }
    url = URL + "?" + urllib.parse.urlencode(q)
    with urllib.request.urlopen(url, timeout=180) as r:
        resp = json.load(r)

    retrieved_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    coords = resp["geometry"]["coordinates"]
    payload = {
        "provider": "nasa-power",
        "role": "independent cross-check (INMET unreachable)",
        "grid_cell_id": f"nasa-power:{coords[1]}:{coords[0]}",
        "query_url": url,
        "query_params": q,
        "queried_latitude": LAT, "queried_longitude": LON,
        "returned_lon_lat_elev": coords,
        "sources": resp.get("header", {}).get("sources"),
        "api_version": resp.get("header", {}).get("api"),
        "fill_value": resp.get("header", {}).get("fill_value"),
        "time_standard": resp.get("header", {}).get("time_standard"),
        "parameter_units": resp.get("parameters"),
        "spatial_resolution": "~0.5 x 0.625 deg (meteorology); whole corridor = 1 cell",
        "temporal_resolution": "daily",
        "start_date": "2023-01-01", "end_date": "2026-09-01",
        "retrieved_at": retrieved_at,
        "license": LICENSE,
        "provenance": "weather_real",
        "raw_response": resp,
    }
    fname = f"nasapower_{coords[1]}_{coords[0]}_{START}_{END}.json"
    (RAW_DIR / fname).write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")
    par = resp["properties"]["parameter"]
    n = len(par["T2M"])
    print(f"saved {fname}  ({n} days)  sources={payload['sources']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
