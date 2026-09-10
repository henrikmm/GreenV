#!/usr/bin/env python3
"""
Phase 5 — EDA on feature_row_dev.csv (main+seed2 only; OOD is never touched here).

Writes reports/figures/eda/*.svg (watermarked SYNTHETIC) and data/features/eda_stats.json.
reports/eda.md is written by hand from these numbers (kept as prose + tables, not
regenerated verbatim, so the write-up can explain what each number means).
"""
from __future__ import annotations
import csv, json
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np

MODULE_ROOT = Path(__file__).resolve().parents[1]
FEAT = MODULE_ROOT / "data" / "features" / "feature_row_dev.csv"
FIGS = MODULE_ROOT / "reports" / "figures" / "eda"
OUT = MODULE_ROOT / "data" / "features" / "eda_stats.json"


# ----------------------------------------------------------------- tiny SVG (same style as qc_synthetic.py)
def _svg(w, h, body, title):
    wm = (f'<text x="{w/2}" y="{h/2}" font-size="{min(w,h)*0.16}" fill="#d33" '
          f'fill-opacity="0.13" text-anchor="middle" transform="rotate(-22 {w/2} {h/2})" '
          f'font-family="sans-serif" font-weight="bold">DADOS SINTETICOS &#183; SYNTHETIC</text>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
            f'viewBox="0 0 {w} {h}" font-family="sans-serif">'
            f'<rect width="{w}" height="{h}" fill="#fff"/>'
            f'<text x="10" y="18" font-size="13" font-weight="bold">{title}</text>'
            f'<text x="10" y="{h-8}" font-size="10" fill="#a00">SYNTHETIC DATA - Phase 5 EDA on Phase 4 output '
            f'(reports/eda.md)</text>{body}{wm}</svg>')


def _axes(x0, y0, x1, y1, xr, yr, xlab, ylab, xticks=5, yticks=5):
    s = [f'<line x1="{x0}" y1="{y1}" x2="{x1}" y2="{y1}" stroke="#333"/>',
         f'<line x1="{x0}" y1="{y0}" x2="{x0}" y2="{y1}" stroke="#333"/>']
    for i in range(xticks + 1):
        xv = xr[0] + (xr[1] - xr[0]) * i / xticks
        px = x0 + (x1 - x0) * i / xticks
        s.append(f'<line x1="{px}" y1="{y1}" x2="{px}" y2="{y1+4}" stroke="#333"/>')
        s.append(f'<text x="{px}" y="{y1+16}" font-size="9" text-anchor="middle">{xv:.4g}</text>')
    for i in range(yticks + 1):
        yv = yr[0] + (yr[1] - yr[0]) * i / yticks
        py = y1 - (y1 - y0) * i / yticks
        s.append(f'<line x1="{x0-4}" y1="{py}" x2="{x0}" y2="{py}" stroke="#333"/>')
        s.append(f'<text x="{x0-6}" y="{py+3}" font-size="9" text-anchor="end">{yv:.4g}</text>')
    s.append(f'<text x="{(x0+x1)/2}" y="{y1+30}" font-size="10" text-anchor="middle">{xlab}</text>')
    s.append(f'<text x="14" y="{(y0+y1)/2}" font-size="10" text-anchor="middle" '
             f'transform="rotate(-90 14 {(y0+y1)/2})">{ylab}</text>')
    return "".join(s)


def _scale(v, lo, hi, a, b):
    return (a + b) / 2 if hi == lo else a + (b - a) * (v - lo) / (hi - lo)


def fig_bars(path, labels, values, ylab, title, values2=None, l1="", l2=""):
    W, H = 640, 380
    x0, y0, x1, y1 = 55, 30, W - 20, H - 45
    hi = max(values + (values2 or []) + [1e-9]) * 1.1
    ax = _axes(x0, y0, x1, y1, (0, len(labels)), (0, hi), "", ylab, xticks=len(labels))
    body = [ax]
    n = len(labels)
    bw = (x1 - x0) / n
    for i, v in enumerate(values):
        bx = x0 + i * bw + bw * 0.12
        bh = _scale(v, 0, hi, 0, y1 - y0)
        w = bw * (0.76 if values2 is None else 0.36)
        body.append(f'<rect x="{bx:.1f}" y="{y1-bh:.1f}" width="{w:.1f}" height="{bh:.1f}" fill="#16c"/>')
        if values2 is not None:
            bh2 = _scale(values2[i], 0, hi, 0, y1 - y0)
            body.append(f'<rect x="{bx+w:.1f}" y="{y1-bh2:.1f}" width="{w:.1f}" height="{bh2:.1f}" fill="#c62"/>')
        body.append(f'<text x="{bx+bw*0.35:.1f}" y="{y1+16}" font-size="9" text-anchor="middle">{labels[i]}</text>')
    if values2 is not None:
        body.append(f'<rect x="{x1-130}" y="{y0}" width="10" height="10" fill="#16c"/><text x="{x1-115}" y="{y0+9}" font-size="9">{l1}</text>')
        body.append(f'<rect x="{x1-130}" y="{y0+14}" width="10" height="10" fill="#c62"/><text x="{x1-115}" y="{y0+23}" font-size="9">{l2}</text>')
    path.write_text(_svg(W, H, "".join(body), title), encoding="utf-8")


def fig_scatter(path, xs, ys, xr, yr, xlab, ylab, title, line=None):
    W, H = 640, 380
    x0, y0, x1, y1 = 55, 30, W - 20, H - 45
    body = [_axes(x0, y0, x1, y1, xr, yr, xlab, ylab)]
    step = max(1, len(xs) // 4000)
    for x, y in zip(xs[::step], ys[::step]):
        body.append(f'<circle cx="{_scale(x,xr[0],xr[1],x0,x1):.1f}" cy="{_scale(y,yr[0],yr[1],y1,y0):.1f}" r="1" fill="#16c" fill-opacity="0.25"/>')
    if line is not None:
        lx, ly = line
        pts = " ".join(f"{_scale(x,xr[0],xr[1],x0,x1):.1f},{_scale(y,yr[0],yr[1],y1,y0):.1f}" for x, y in zip(lx, ly))
        body.append(f'<polyline points="{pts}" fill="none" stroke="#d33" stroke-width="2"/>')
    path.write_text(_svg(W, H, "".join(body), title), encoding="utf-8")


def fnum(x):
    return None if x in (None, "") else float(x)


def main():
    FIGS.mkdir(parents=True, exist_ok=True)
    rows = list(csv.DictReader(FEAT.open(encoding="utf-8")))
    usable = [r for r in rows if r["split"] in ("train", "validation", "test")]
    print(f"rows total={len(rows)} usable(train+val+test)={len(usable)} embargo-excluded={len(rows)-len(usable)}")

    height = np.array([float(r["height_cm"]) for r in usable])
    growth = np.array([fnum(r["weekly_growth_cm"]) for r in usable if fnum(r["weekly_growth_cm"]) is not None])
    nivel = Counter(int(r["nivel_observed"]) for r in usable)
    tau = np.array([int(r["days_since_rocada"]) for r in usable])
    ncycle = sum(1 for r in usable if r["rocada_in_new_cycle"] == "1")

    # seasonality
    by_month_h, by_month_g = defaultdict(list), defaultdict(list)
    for r in usable:
        m = int(r["as_of_date"][5:7])
        by_month_h[m].append(float(r["height_cm"]))
        g = fnum(r["weekly_growth_cm"])
        if g is not None:
            by_month_g[m].append(g)
    month_h = [float(np.mean(by_month_h[m])) if by_month_h[m] else 0 for m in range(1, 13)]
    month_g = [float(np.mean(by_month_g[m])) if by_month_g[m] else 0 for m in range(1, 13)]

    # growth vs gdd / deficit (binned deciles), exclude new-cycle rows (growth is None there anyway)
    def binned(key):
        xs, ys = [], []
        for r in usable:
            g = fnum(r["weekly_growth_cm"])
            v = fnum(r[key])
            if g is not None and v is not None:
                xs.append(v)
                ys.append(g)
        xs, ys = np.array(xs), np.array(ys)
        if len(xs) < 20:
            return xs, ys, [], []
        qs = np.quantile(xs, np.linspace(0, 1, 11))
        bx, by = [], []
        for i in range(10):
            m = (xs >= qs[i]) & (xs <= qs[i + 1])
            if m.sum() > 5:
                bx.append(float(xs[m].mean())); by.append(float(ys[m].mean()))
        return xs, ys, bx, by

    gdd_x, gdd_y, gdd_bx, gdd_by = binned("gdd_week_tb15_c")
    def_x, def_y, def_bx, def_by = binned("water_deficit_30d")

    # days_since_rocada vs height (regrowth shape) and vs growth
    by_tau_h = defaultdict(list)
    for r in usable:
        by_tau_h[min(160, int(r["days_since_rocada"]))].append(float(r["height_cm"]))
    tau_x = sorted(t for t in by_tau_h if len(by_tau_h[t]) >= 8)
    tau_y = [float(np.mean(by_tau_h[t])) for t in tau_x]

    # between-trecho heterogeneity
    per_trecho_h = defaultdict(list)
    per_trecho_g = defaultdict(list)
    for r in usable:
        per_trecho_h[r["trecho_id"]].append(float(r["height_cm"]))
        g = fnum(r["weekly_growth_cm"])
        if g is not None:
            per_trecho_g[r["trecho_id"]].append(g)
    mh = np.array([np.mean(v) for v in per_trecho_h.values()])
    mg = np.array([np.mean(v) for v in per_trecho_g.values() if v])

    # missingness / target completeness
    n = len(usable)
    null7 = sum(1 for r in usable if r["target_height_plus_7d_cm"] == "")
    null14 = sum(1 for r in usable if r["target_height_plus_14d_cm"] == "")
    null30 = sum(1 for r in usable if r["target_height_plus_30d_cm"] == "")
    censored = sum(1 for r in usable if r["target_days_until_30cm_censored"] == "1")
    new_cycle_pct = round(100 * ncycle / n, 2)

    # correlations of KEEP/CANDIDATE numeric features vs targets
    numeric_candidates = ["height_cm", "height_prev_cm", "height_lag2_cm", "weekly_growth_cm",
                          "days_since_rocada", "gdd_week_tb15_c", "gdd_week_tb10_c", "gdd_week_tb17_c",
                          "tmin_week_c", "tmean_week_c", "tmax_week_c",
                          "rain_7d_mm", "rain_14d_mm", "rain_30d_mm", "shortwave_7d_mj_m2", "et0_7d_mm",
                          "water_deficit_30d", "water_deficit_90d", "relative_humidity_7d_pct",
                          "week_of_year", "season_sin", "season_cos", "dry_season_flag",
                          "km_mid", "cell_coverage", "weeks_observed_last_8"]
    corr = {}
    for tgt in ("target_height_plus_7d_cm", "target_days_until_30cm"):
        corr[tgt] = {}
        tv_all = np.array([fnum(r[tgt]) for r in usable])
        for feat in numeric_candidates:
            fv_all = np.array([fnum(r[feat]) for r in usable])
            mask = (~np.isnan(tv_all.astype(float))) if tv_all.dtype != object else None
            xs, ys = [], []
            for r in usable:
                a, b = fnum(r[feat]), fnum(r[tgt])
                if a is not None and b is not None:
                    xs.append(a); ys.append(b)
            if len(xs) > 30 and np.std(xs) > 0 and np.std(ys) > 0:
                corr[tgt][feat] = round(float(np.corrcoef(xs, ys)[0, 1]), 3)
            else:
                corr[tgt][feat] = None

    # outliers: |z| > 5 on height and growth
    hz = (height - height.mean()) / height.std()
    gz = (growth - growth.mean()) / growth.std()
    outliers_h = int((np.abs(hz) > 5).sum())
    outliers_g = int((np.abs(gz) > 5).sum())

    # main vs seed2
    main_rows = [r for r in usable if r["dataset"] == "main"]
    seed2_rows = [r for r in usable if r["dataset"] == "seed2"]
    def summarize(rr):
        h = np.array([float(r["height_cm"]) for r in rr])
        g = np.array([fnum(r["weekly_growth_cm"]) for r in rr if fnum(r["weekly_growth_cm"]) is not None])
        nv = Counter(int(r["nivel_observed"]) for r in rr)
        tot = sum(nv.values())
        return dict(n=len(rr), mean_height=round(float(h.mean()), 2), median_height=round(float(np.median(h)), 2),
                   mean_growth=round(float(g.mean()), 2),
                   nivel_pct={str(k): round(100 * nv.get(k, 0) / tot, 1) for k in (0, 1, 2, 3)})
    ms, s2s = summarize(main_rows), summarize(seed2_rows)

    stats = {
        "n_usable_rows": n, "n_total_rows_incl_embargo_excluded": len(rows),
        "height_cm": {"min": round(float(height.min()), 2), "median": round(float(np.median(height)), 2),
                     "mean": round(float(height.mean()), 2), "p95": round(float(np.quantile(height, .95)), 2),
                     "max": round(float(height.max()), 2)},
        "weekly_growth_cm": {"n": len(growth), "median": round(float(np.median(growth)), 2),
                             "mean": round(float(growth.mean()), 2), "p95": round(float(np.quantile(growth, .95)), 2),
                             "negative_pct": round(100 * float((growth < 0).mean()), 2)},
        "nivel_observed_pct": {str(k): round(100 * nivel.get(k, 0) / n, 2) for k in (0, 1, 2, 3)},
        "days_since_rocada": {"median": int(np.median(tau)), "p95": int(np.quantile(tau, .95))},
        "new_cycle_rows_pct": new_cycle_pct,
        "seasonality_month_mean_height_cm": [round(v, 1) for v in month_h],
        "seasonality_month_mean_growth_cm": [round(v, 2) for v in month_g],
        "growth_vs_gdd_binned": {"x": [round(v, 1) for v in gdd_bx], "y": [round(v, 2) for v in gdd_by]},
        "growth_vs_deficit30_binned": {"x": [round(v, 1) for v in def_bx], "y": [round(v, 2) for v in def_by]},
        "between_trecho_mean_height_cv": round(float(mh.std() / mh.mean()), 3),
        "between_trecho_mean_growth_cv": round(float(mg.std() / mg.mean()), 3),
        "target_missingness_pct": {"plus_7d": round(100 * null7 / n, 2), "plus_14d": round(100 * null14 / n, 2),
                                   "plus_30d": round(100 * null30 / n, 2),
                                   "days_until_30cm_censored": round(100 * censored / n, 2)},
        "correlations": corr,
        "outliers": {"height_abs_z_gt_5": outliers_h, "growth_abs_z_gt_5": outliers_g},
        "main_vs_seed2": {"main": ms, "seed2": s2s},
    }
    OUT.write_text(json.dumps(stats, ensure_ascii=False, indent=1), encoding="utf-8")

    # figures
    fig_bars(FIGS / "01_height_hist.svg",
             [f"{e:.0f}" if i % 3 == 0 else "" for i, e in enumerate(np.histogram(height, bins=24)[1][:-1])],
             list(map(float, np.histogram(height, bins=24)[0])), "count", "feature_row: height_cm distribution (dev)")
    fig_bars(FIGS / "02_growth_hist.svg",
             [f"{e:.0f}" if i % 4 == 0 else "" for i, e in enumerate(np.histogram(growth, bins=24, range=(np.quantile(growth,.01), np.quantile(growth,.99)))[1][:-1])],
             list(map(float, np.histogram(growth, bins=24, range=(np.quantile(growth,.01), np.quantile(growth,.99)))[0])),
             "count", "feature_row: weekly_growth_cm distribution (dev, same-cycle only)")
    fig_bars(FIGS / "03_nivel_dist.svg", ["0", "1", "2", "3"],
             [stats["nivel_observed_pct"][k] for k in ("0", "1", "2", "3")], "% of rows",
             "feature_row: Nivel (observed) distribution (dev)")
    fig_bars(FIGS / "04_seasonality.svg", "J F M A M J J A S O N D".split(), month_g,
             "mean weekly_growth_cm", "feature_row: mean weekly growth by month (dev)")
    fig_scatter(FIGS / "05_growth_vs_gdd.svg", list(gdd_x), list(gdd_y),
                (0, float(np.quantile(gdd_x, .99))), (float(np.quantile(gdd_y, .01)), float(np.quantile(gdd_y, .99))),
                "gdd_week_tb15_c", "weekly_growth_cm", "feature_row: growth vs GDD (dev)", line=(gdd_bx, gdd_by))
    fig_scatter(FIGS / "06_growth_vs_deficit.svg", list(def_x), list(def_y),
                (float(np.quantile(def_x, .01)), float(np.quantile(def_x, .99))),
                (float(np.quantile(def_y, .01)), float(np.quantile(def_y, .99))),
                "water_deficit_30d", "weekly_growth_cm", "feature_row: growth vs 30d water balance (dev)",
                line=(def_bx, def_by))
    fig_bars(FIGS / "07_regrowth_by_tau.svg",
             [str(t) if i % 8 == 0 else "" for i, t in enumerate(tau_x)], tau_y,
             "mean height_cm", "feature_row: mean height vs days_since_rocada (dev)")
    hh, ee = np.histogram(mh, bins=20)
    fig_bars(FIGS / "08_between_trecho.svg",
             [f"{ee[i]:.0f}" if i % 4 == 0 else "" for i in range(len(hh))], list(map(float, hh)),
             "count of trechos", "feature_row: per-trecho mean height (dev)")
    fig_bars(FIGS / "09_main_vs_seed2_nivel.svg", ["0", "1", "2", "3"],
             [ms["nivel_pct"][k] for k in ("0", "1", "2", "3")], "% of rows",
             "feature_row: Nivel distribution, main vs seed2",
             values2=[s2s["nivel_pct"][k] for k in ("0", "1", "2", "3")], l1="main", l2="seed2")

    print(json.dumps({k: v for k, v in stats.items() if k not in ("correlations",)}, indent=1)[:3000])


if __name__ == "__main__":
    main()
