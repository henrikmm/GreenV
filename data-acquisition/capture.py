"""Entry point: build a roadside vegetation dataset from Google Street View.

Pipeline:
  1. Trace the road from origin to destination via the Directions API.
  2. Sample equidistant points along the resulting polyline.
  3. At each sample point, search panoramas (current + historical) — also searching
     ~20m to each side to pick up the opposite carriageway on divided highways.
  4. Classify each panorama by carriageway using its capture heading vs. the road
     bearing, then compute the off-road camera heading (perpendicular to travel).
  5. Write metadata.csv, then download the images (unless --dry-run).
"""

import argparse
import os
import pandas as pd

from roadcapture.config import DEFAULT_INTERVAL_METERS, DATASET_DIR
from roadcapture.route import get_route_polyline, sample_equidistant_points
from roadcapture.streetview import search_panoramas_at, download_image
from roadcapture.carriageway import (
    PASS_CONFIGS, PERPENDICULAR_OFFSET_M,
    perpendicular_offset, classify_pano_carriageway, build_filename,
)


def run(origin, destination, output_dir, interval_m, dry_run, passes, max_downloads):
    if passes is None:
        passes = ["SP_RIO", "RIO_SP"]

    os.makedirs(output_dir, exist_ok=True)

    print("[1/4] Tracing route ...")
    polyline_pts = get_route_polyline(origin, destination)
    print(f"       {len(polyline_pts)} polyline vertices")

    print(f"[2/4] Sampling every {interval_m}m ...")
    sample_pts = sample_equidistant_points(polyline_pts, interval_m)
    print(
        f"       {len(sample_pts)} capture points over {sample_pts[-1]['km']:.2f} km"
        if sample_pts else "       (no points)"
    )

    print("[3/4] Searching panoramas and classifying by carriageway ...")
    rows = []
    stats = {"SP_RIO": 0, "RIO_SP": 0}
    unclassified = 0

    for i, pt in enumerate(sample_pts):
        # Search at the sample point + two perpendicular offsets so we pick up
        # coverage from the opposite carriageway on divided highways. Dedupe by pano_id.
        seen_ids = set()
        panos = []
        left = perpendicular_offset(pt["lat"], pt["lon"], pt["road_bearing"], PERPENDICULAR_OFFSET_M, -1)
        right = perpendicular_offset(pt["lat"], pt["lon"], pt["road_bearing"], PERPENDICULAR_OFFSET_M, +1)
        for search_lat, search_lon in [(pt["lat"], pt["lon"]), left, right]:
            for p in search_panoramas_at(search_lat, search_lon):
                if p["pano_id"] not in seen_ids:
                    seen_ids.add(p["pano_id"])
                    panos.append(p)

        if not panos:
            print(f"  pt {i:3d}  km {pt['km']:7.3f}: no coverage")
            continue

        by_pass = {"SP_RIO": 0, "RIO_SP": 0, "unclass": 0}
        for pano in panos:
            if pano["heading"] is None:
                by_pass["unclass"] += 1
                continue

            carriageway = classify_pano_carriageway(pano["heading"], pt["road_bearing"])
            if carriageway is None or carriageway not in passes:
                by_pass["unclass"] += 1
                continue

            cfg = PASS_CONFIGS[carriageway]
            capture_heading = (pano["heading"] + cfg["heading_offset"]) % 360
            fname = build_filename(
                carriageway, cfg["side"], pt["km"],
                pano["date"], capture_heading, cfg["pitch"], cfg["fov"],
            )
            rows.append({
                "pass": carriageway,
                "direction": cfg["direction"],
                "side": cfg["side"],
                "km": pt["km"],
                "sample_lat": pt["lat"],
                "sample_lon": pt["lon"],
                "road_bearing": round(pt["road_bearing"], 2),
                "pano_id": pano["pano_id"],
                "pano_date": pano["date"],
                "pano_lat": pano["lat"],
                "pano_lon": pano["lon"],
                "pano_heading": round(pano["heading"], 2),
                "heading": round(capture_heading, 2),
                "heading_offset": cfg["heading_offset"],
                "pitch": cfg["pitch"],
                "fov": cfg["fov"],
                "size": "640x640",
                "filename": fname,
            })
            by_pass[carriageway] += 1
            stats[carriageway] += 1

        unclassified += by_pass["unclass"]
        print(f"  pt {i:3d}  km {pt['km']:7.3f}: "
              f"SP_RIO={by_pass['SP_RIO']:2d}  RIO_SP={by_pass['RIO_SP']:2d}  "
              f"unclass={by_pass['unclass']:2d}")

    df = pd.DataFrame(rows)
    csv_path = os.path.join(output_dir, "metadata.csv")
    df.to_csv(csv_path, index=False)

    print(f"\n{'='*60}\n  SUMMARY\n{'='*60}")
    print(f"  Total entries:   {len(df)}")
    if len(df) > 0:
        print(f"  Unique points:   {df['km'].nunique()}")
        print(f"  Date range:      {df['pano_date'].min()} → {df['pano_date'].max()}")
        for p in passes:
            sub = df[df['pass'] == p]
            print(f"  {p:>8s}:   {len(sub)} images across {sub['km'].nunique()} points")
        print(f"  Unclassified:    {unclassified} panos (heading outside tolerance)")
    print(f"  Metadata:        {csv_path}")

    if dry_run:
        cost = len(df) * 0.007
        print(f"\n  [4/4] DRY RUN — no images downloaded.")
        print(f"         Estimated cost if downloaded: ~${cost:.2f}")
        return df

    budget = max_downloads if max_downloads is not None else len(df)
    if max_downloads is not None:
        print(f"\n  >> Download budget: {max_downloads} hard cap <<")
    to_dl = df.iloc[:budget]
    if len(to_dl) < len(df):
        print(f"  Budget will cap download to first {len(to_dl)} of {len(df)} entries.")

    print(f"\n[4/4] Downloading {len(to_dl)} images ...")
    downloaded = 0
    for _, row in to_dl.iterrows():
        out_path = os.path.join(output_dir, row["pass"], row["filename"])
        if download_image(
            pano_id=row["pano_id"],
            heading=row["heading"],
            output_path=out_path,
            pitch=row["pitch"],
            fov=row["fov"],
        ):
            downloaded += 1
    print(f"       Done. Downloaded {downloaded}/{len(to_dl)} images to {output_dir}/")

    return df


def main():
    parser = argparse.ArgumentParser(
        description="Build a roadside vegetation dataset from Google Street View (current + historical)."
    )
    parser.add_argument("--origin", required=True, help="Start of road (SP→Rio direction): 'lat,lon'")
    parser.add_argument("--destination", required=True, help="End of road (SP→Rio direction): 'lat,lon'")
    parser.add_argument("--interval", type=float, default=DEFAULT_INTERVAL_METERS,
                        help="Distance between capture points in meters (default: 100)")
    parser.add_argument("--output", default=str(DATASET_DIR),
                        help="Output directory (default: <repo>/dataset)")
    parser.add_argument("--dry-run", action="store_true",
                        help="Only collect metadata, don't download images (FREE)")
    parser.add_argument("--passes", nargs="+", default=None, choices=["SP_RIO", "RIO_SP"],
                        help="Which carriageways to capture (default: both)")
    parser.add_argument("--max-downloads", type=int, default=None,
                        help="Hard cap on total image downloads (safety rail)")
    args = parser.parse_args()

    run(
        origin=args.origin,
        destination=args.destination,
        output_dir=args.output,
        interval_m=args.interval,
        dry_run=args.dry_run,
        passes=args.passes,
        max_downloads=args.max_downloads,
    )


if __name__ == "__main__":
    main()
