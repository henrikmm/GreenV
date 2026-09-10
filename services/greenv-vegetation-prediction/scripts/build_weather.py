#!/usr/bin/env python3
"""
Phase 3 — process the raw Open-Meteo responses into:

  processed/weather_observation.csv        daily rows, schema.sql `weather_observation` shape
  processed/weather_weekly_features.csv     per (cell, ISO week) trailing/retrospective climate features
  processed/trecho_weather_cell.csv         trecho -> weather-cell map (coords: provenance=hypothesis)
  processed/weather_pull_manifest.json      dataset_manifest row (kind=weather_pull, is_synthetic=0)
  qc/qc_report.json                         quality controls

Reads ONLY from data/real/weather/raw/ (never edits it). stdlib + numpy only.

GDD: gdd_c in weather_observation uses Tb = 15 degC (literature_derived, Villa Nova
et al. 2007) as a REFERENCE value only. It is configurable (--tb) and the weekly
feature file also carries gdd_week for Tb in {10, 15, 17} so Phase 5/8 can sweep
the Phase-1 range without re-fetching. 15 degC is NOT asserted to be the base
temperature of SP-021 vegetation.
"""
from __future__ import annotations
import argparse, csv, json, math
from datetime import date, timedelta
from pathlib import Path

import numpy as np

MODULE_ROOT = Path(__file__).resolve().parents[1]
RAW_DIR = MODULE_ROOT / "data" / "real" / "weather" / "raw"
PROC_DIR = MODULE_ROOT / "data" / "real" / "weather" / "processed"
QC_DIR = MODULE_ROOT / "data" / "real" / "weather" / "qc"

TB_SET = (10.0, 15.0, 17.0)
TB_REF = 15.0

# SP-021 spatial representation (must match fetch_weather.py). provenance=hypothesis.
ROAD_ANCHORS = [
    (0.0, -23.4080, -46.7290), (5.0, -23.4310, -46.7520), (10.0, -23.4600, -46.7700),
    (15.0, -23.4950, -46.7850), (20.0, -23.5400, -46.8000), (25.0, -23.5850, -46.8150),
    (29.3, -23.6290, -46.8300),
]
KM_MIN, KM_MAX, STEP_KM = 0.0, 29.3, 0.5


def interp_latlon(km):
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


def daterange(d0: date, d1: date):
    d = d0
    while d <= d1:
        yield d
        d += timedelta(days=1)


def load_cells():
    cells = []
    for f in sorted(RAW_DIR.glob("openmeteo_*.json")):
        p = json.loads(f.read_text(encoding="utf-8"))
        daily = p["raw_response"]["daily"]
        cells.append({
            "file": f.name,
            "grid_cell_id": p["grid_cell_id"],
            "cell_lat": float(p["returned_cell_latitude"]),
            "cell_lon": float(p["returned_cell_longitude"]),
            "elevation_m": p.get("elevation_m"),
            "timezone": p.get("timezone"),
            "utc_offset_seconds": p.get("utc_offset_seconds"),
            "daily_units": p.get("daily_units"),
            "retrieved_at": p["retrieved_at"],
            "license": p["license"],
            "provider": p["provider"],
            "model": p["model"],
            "start_date": p["start_date"],
            "end_date": p["end_date"],
            "query_url": p["query_url"],
            "daily": daily,
        })
    return cells


VARS = {
    "temperature_2m_min": "temp_min_c",
    "temperature_2m_mean": "temp_mean_c",
    "temperature_2m_max": "temp_max_c",
    "precipitation_sum": "precip_mm",
    "rain_sum": "rain_mm",
    "shortwave_radiation_sum": "shortwave_radiation_mj_m2",
    "et0_fao_evapotranspiration": "et0_mm",
    "relative_humidity_2m_mean": "relative_humidity_pct",
    "wind_speed_10m_mean": "wind_speed_ms",
}


def to_daily_rows(cell, tb_ref):
    d = cell["daily"]
    times = d["time"]
    out = []
    for i, t in enumerate(times):
        y, m, dd = (int(x) for x in t.split("-"))
        iso = date(y, m, dd).isocalendar()
        row = {
            "grid_cell_id": cell["grid_cell_id"],
            "trecho_id": "",
            "obs_date": t,
            "iso_year": iso.year,
            "iso_week": iso.week,
        }
        for src, dst in VARS.items():
            v = d.get(src, [None] * len(times))[i]
            row[dst] = v
        tmin, tmax = row["temp_min_c"], row["temp_max_c"]
        if tmin is not None and tmax is not None:
            row["gdd_c"] = max(0.0, (tmax + tmin) / 2.0 - tb_ref)
        else:
            row["gdd_c"] = None
        row["gdd_base_temp_c"] = tb_ref
        p, e = row["precip_mm"], row["et0_mm"]
        row["water_deficit_index"] = (p - e) if (p is not None and e is not None) else None
        row["provider"] = cell["provider"]
        row["dataset"] = "ERA5-Land + ERA5 (era5_seamless)"
        row["data_source"] = "weather_real"
        row["provenance"] = "weather_real"
        row["license"] = cell["license"]
        row["retrieved_at"] = cell["retrieved_at"]
        row["raw_response_ref"] = f"data/real/weather/raw/{cell['file']}"
        out.append(row)
    return out


def weekly_features(daily_rows):
    """Per (cell, ISO week) row anchored at the last day of the ISO week, with
    trailing/retrospective windows ending at that as_of_date."""
    by_cell = {}
    for r in daily_rows:
        by_cell.setdefault(r["grid_cell_id"], []).append(r)

    feats = []
    for cell_id, rows in by_cell.items():
        rows.sort(key=lambda r: r["obs_date"])
        dates = [date.fromisoformat(r["obs_date"]) for r in rows]
        idx = {d: i for i, d in enumerate(dates)}

        def arr(key):
            return np.array([np.nan if r[key] is None else float(r[key]) for r in rows], dtype=float)

        a = {k: arr(k) for k in ("temp_min_c", "temp_mean_c", "temp_max_c",
                                 "precip_mm", "rain_mm", "shortwave_radiation_mj_m2",
                                 "et0_mm", "relative_humidity_pct")}

        def trailing_sum(end_i, days, x):
            lo = max(0, end_i - days + 1)
            seg = x[lo:end_i + 1]
            return float(np.nansum(seg)) if seg.size else np.nan

        def trailing_mean(end_i, days, x):
            lo = max(0, end_i - days + 1)
            seg = x[lo:end_i + 1]
            return float(np.nanmean(seg)) if seg.size and not np.all(np.isnan(seg)) else np.nan

        weeks = sorted({(r["iso_year"], r["iso_week"]) for r in rows})
        for (yy, ww) in weeks:
            wk_idx = [i for i, r in enumerate(rows) if (r["iso_year"], r["iso_week"]) == (yy, ww)]
            end_i = wk_idx[-1]
            as_of = dates[end_i]
            wk_days = len(wk_idx)

            doy = as_of.timetuple().tm_yday
            # SP wet season Oct-Mar; dry season Apr-Sep (weather-eda.md climatology).
            dry_season_flag = 1 if as_of.month in (4, 5, 6, 7, 8, 9) else 0
            f = {
                "grid_cell_id": cell_id,
                "iso_year": yy, "iso_week": ww,
                "as_of_date": as_of.isoformat(),
                "week_days_present": wk_days,
                # deterministic calendar features (no leakage: fixed by the date)
                "month": as_of.month,
                "week_of_year": ww,
                "day_of_year": doy,
                "season_sin": math.sin(2 * math.pi * doy / 365.25),
                "season_cos": math.cos(2 * math.pi * doy / 365.25),
                "dry_season_flag": dry_season_flag,
                # weekly aggregates over the ISO week itself
                "tmin_week_c": float(np.nanmean(a["temp_min_c"][wk_idx])),
                "tmean_week_c": float(np.nanmean(a["temp_mean_c"][wk_idx])),
                "tmax_week_c": float(np.nanmean(a["temp_max_c"][wk_idx])),
                "tmin_week_abs_c": float(np.nanmin(a["temp_min_c"][wk_idx])),
                "tmax_week_abs_c": float(np.nanmax(a["temp_max_c"][wk_idx])),
                "rh_week_pct": float(np.nanmean(a["relative_humidity_pct"][wk_idx])),
                # GDD for the ISO week, for each candidate Tb (Phase 1 sweep 10-17)
                **{f"gdd_week_tb{int(tb)}_c":
                   float(np.nansum(np.maximum(0.0,
                        (a["temp_max_c"][wk_idx] + a["temp_min_c"][wk_idx]) / 2.0 - tb)))
                   for tb in TB_SET},
                # trailing / retrospective windows ending at as_of_date
                "rain_7d_mm": trailing_sum(end_i, 7, a["rain_mm"]),
                "rain_14d_mm": trailing_sum(end_i, 14, a["rain_mm"]),
                "rain_30d_mm": trailing_sum(end_i, 30, a["rain_mm"]),
                "precip_7d_mm": trailing_sum(end_i, 7, a["precip_mm"]),
                "precip_30d_mm": trailing_sum(end_i, 30, a["precip_mm"]),
                "shortwave_7d_mj_m2": trailing_sum(end_i, 7, a["shortwave_radiation_mj_m2"]),
                "et0_7d_mm": trailing_sum(end_i, 7, a["et0_mm"]),
                "et0_30d_mm": trailing_sum(end_i, 30, a["et0_mm"]),
                "water_deficit_7d": trailing_sum(end_i, 7, a["rain_mm"]) - trailing_sum(end_i, 7, a["et0_mm"]),
                "water_deficit_30d": trailing_sum(end_i, 30, a["rain_mm"]) - trailing_sum(end_i, 30, a["et0_mm"]),
                "tmin_7d_c": trailing_mean(end_i, 7, a["temp_min_c"]),
                "tmean_7d_c": trailing_mean(end_i, 7, a["temp_mean_c"]),
                "gdd_week_c": None,  # filled below with the reference Tb
                "gdd_base_temp_c_ref": TB_REF,
                "provenance": "weather_real",
            }
            f["gdd_week_c"] = f[f"gdd_week_tb{int(TB_REF)}_c"]
            feats.append(f)
    return feats


def build_trecho_cell_map(cells):
    cell_pts = [(c["cell_lat"], c["cell_lon"], c["grid_cell_id"]) for c in cells]
    rows, km = [], KM_MIN
    while km < KM_MAX:
        km_start = round(km, 4)
        km_end = round(min(km + STEP_KM, KM_MAX), 4)
        km_mid = round((km_start + km_end) / 2, 4)
        lat, lon = interp_latlon(km_mid)
        # nearest returned cell
        best = min(cell_pts, key=lambda cp: (cp[0] - lat) ** 2 + (cp[1] - lon) ** 2)
        for sentido in ("norte", "sul"):
            tid = f"SP-021:{sentido}:{int(round(km_start*1000)):06d}"
            rows.append({
                "trecho_id": tid, "rodovia": "SP-021", "sentido": sentido,
                "km_start": km_start, "km_end": km_end, "km_mid": km_mid,
                "anchor_lat": round(lat, 5), "anchor_lon": round(lon, 5),
                "coord_provenance": "hypothesis",
                "grid_cell_id": best[2], "cell_lat": best[0], "cell_lon": best[1],
                "spatial_note": "ERA5(-Land) cell >> 500 m trecho; many trechos share one cell",
            })
        km += STEP_KM
    return rows


def qc(daily_rows, cells):
    report = {"per_cell": {}, "overall": {}}
    all_dates_expected = None
    n_cells = len({r["grid_cell_id"] for r in daily_rows})

    for c in cells:
        rows = [r for r in daily_rows if r["grid_cell_id"] == c["grid_cell_id"]]
        rows.sort(key=lambda r: r["obs_date"])
        ds = [date.fromisoformat(r["obs_date"]) for r in rows]
        d0, d1 = date.fromisoformat(c["start_date"]), min(date.fromisoformat(c["end_date"]), ds[-1])
        expected = list(daterange(date.fromisoformat(c["start_date"]), ds[-1]))
        present = set(ds)
        missing_dates = [d.isoformat() for d in expected if d not in present]
        dup = len(ds) - len(present)

        # impossible / suspect values
        bad = {"tmin_gt_tmax": 0, "tmean_out_of_range": 0, "precip_negative": 0,
               "rh_out_of_0_100": 0, "shortwave_negative": 0, "et0_negative": 0,
               "temp_out_of_-15_50": 0, "wind_negative": 0}
        nulls = {k: 0 for k in VARS.values()}
        nulls["gdd_c"] = 0
        for r in rows:
            for k in nulls:
                if r.get(k) is None:
                    nulls[k] += 1
            tmn, tmx, tme = r["temp_min_c"], r["temp_max_c"], r["temp_mean_c"]
            if tmn is not None and tmx is not None and tmn > tmx:
                bad["tmin_gt_tmax"] += 1
            if tmn is not None and tmx is not None and tme is not None and not (tmn - 0.5 <= tme <= tmx + 0.5):
                bad["tmean_out_of_range"] += 1
            for v in (tmn, tmx, tme):
                if v is not None and not (-15 <= v <= 50):
                    bad["temp_out_of_-15_50"] += 1
            if r["precip_mm"] is not None and r["precip_mm"] < 0:
                bad["precip_negative"] += 1
            if r["rain_mm"] is not None and r["rain_mm"] < 0:
                bad["precip_negative"] += 1
            if r["relative_humidity_pct"] is not None and not (0 <= r["relative_humidity_pct"] <= 100):
                bad["rh_out_of_0_100"] += 1
            if r["shortwave_radiation_mj_m2"] is not None and r["shortwave_radiation_mj_m2"] < 0:
                bad["shortwave_negative"] += 1
            if r["et0_mm"] is not None and r["et0_mm"] < 0:
                bad["et0_negative"] += 1
            if r["wind_speed_ms"] is not None and r["wind_speed_ms"] < 0:
                bad["wind_negative"] += 1

        n = len(rows)
        report["per_cell"][c["grid_cell_id"]] = {
            "file": c["file"],
            "cell_lat": c["cell_lat"], "cell_lon": c["cell_lon"], "elevation_m": c["elevation_m"],
            "timezone": c["timezone"], "utc_offset_seconds": c["utc_offset_seconds"],
            "units": c["daily_units"],
            "first_date": ds[0].isoformat(), "last_date": ds[-1].isoformat(),
            "n_days": n,
            "n_days_expected_to_last": len(expected),
            "missing_dates_count": len(missing_dates),
            "missing_dates_sample": missing_dates[:10],
            "duplicate_date_rows": dup,
            "temporal_continuity_ok": (len(missing_dates) == 0 and dup == 0),
            "null_counts": nulls,
            "null_pct": {k: round(100.0 * v / n, 3) for k, v in nulls.items()},
            "impossible_value_counts": bad,
        }
        if all_dates_expected is None:
            all_dates_expected = (ds[0], ds[-1])

    # aggregate null% across all cells/rows, per variable
    tot = len(daily_rows)
    agg_null = {k: 0 for k in list(VARS.values()) + ["gdd_c"]}
    for r in daily_rows:
        for k in agg_null:
            if r.get(k) is None:
                agg_null[k] += 1
    report["overall"] = {
        "n_distinct_weather_cells": n_cells,
        "n_daily_rows": tot,
        "date_range": [all_dates_expected[0].isoformat(), all_dates_expected[1].isoformat()],
        "missing_pct_by_variable": {k: round(100.0 * v / tot, 3) for k, v in agg_null.items()},
        "units_consistent_across_cells": len({json.dumps(c["daily_units"], sort_keys=True) for c in cells}) == 1,
        "timezones_consistent": len({c["timezone"] for c in cells}) == 1,
        "utc_offsets_consistent": len({c["utc_offset_seconds"] for c in cells}) == 1,
    }
    return report


def write_csv(path: Path, rows: list[dict]):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    fields = list(rows[0].keys())
    with path.open("w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=fields)
        w.writeheader()
        for r in rows:
            w.writerow({k: ("" if r.get(k) is None or (isinstance(r.get(k), float) and math.isnan(r[k]))
                            else r[k]) for k in fields})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tb", type=float, default=TB_REF, help="reference base temperature for gdd_c (degC)")
    args = ap.parse_args()

    cells = load_cells()
    assert cells, f"no raw files in {RAW_DIR}"
    daily_rows = []
    for c in cells:
        daily_rows.extend(to_daily_rows(c, args.tb))

    feats = weekly_features(daily_rows)
    tmap = build_trecho_cell_map(cells)
    report = qc(daily_rows, cells)

    PROC_DIR.mkdir(parents=True, exist_ok=True)
    QC_DIR.mkdir(parents=True, exist_ok=True)
    write_csv(PROC_DIR / "weather_observation.csv", daily_rows)
    write_csv(PROC_DIR / "weather_weekly_features.csv", feats)
    write_csv(PROC_DIR / "trecho_weather_cell.csv", tmap)
    (QC_DIR / "qc_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")

    per_cell_counts = {}
    for r in tmap:
        per_cell_counts[r["grid_cell_id"]] = per_cell_counts.get(r["grid_cell_id"], 0) + 1

    manifest = {
        "kind": "weather_pull",
        "is_synthetic": 0,
        "created_at": cells[0]["retrieved_at"],
        "provider": "open-meteo",
        "model": "era5_seamless",
        "license": cells[0]["license"],
        "provenance": "weather_real",
        "date_range": report["overall"]["date_range"],
        "n_daily_rows": report["overall"]["n_daily_rows"],
        "n_distinct_weather_cells": report["overall"]["n_distinct_weather_cells"],
        "trechos_per_cell": per_cell_counts,
        "gdd_base_temp_c_ref": args.tb,
        "gdd_base_temp_c_note": "reference only (Villa Nova et al. 2007); sweep 10-17; NOT the SP-021 base temperature",
        "spatial_representation_provenance": "hypothesis (GreenV apps/web ROAD_ANCHORS; not official)",
        "outputs": [
            "data/real/weather/processed/weather_observation.csv",
            "data/real/weather/processed/weather_weekly_features.csv",
            "data/real/weather/processed/trecho_weather_cell.csv",
            "data/real/weather/qc/qc_report.json",
        ],
        "raw_inputs": [f"data/real/weather/raw/{c['file']}" for c in cells],
    }
    (PROC_DIR / "weather_pull_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")

    print(f"daily rows           : {len(daily_rows)}")
    print(f"weekly feature rows  : {len(feats)}")
    print(f"weather cells        : {report['overall']['n_distinct_weather_cells']}")
    print(f"date range           : {report['overall']['date_range']}")
    print(f"missing % by variable : {report['overall']['missing_pct_by_variable']}")
    print(f"trechos per cell     : {per_cell_counts}")


if __name__ == "__main__":
    main()
