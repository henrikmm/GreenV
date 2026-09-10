#!/usr/bin/env python3
"""
Phase 4 Part B — QC and sanity figures for the synthetic datasets.

Reads data/synthetic/<name>/, writes:
  data/synthetic/qc/qc_synthetic_<name>.json
  reports/figures/synthetic/<name>__*.svg   (every figure carries a SYNTHETIC watermark)

No plotting library available in this environment -> hand-rolled SVG. stdlib + numpy.
"""
from __future__ import annotations
import csv, json, math, sys
from collections import Counter, defaultdict
from datetime import date
from pathlib import Path

import numpy as np

MODULE_ROOT = Path(__file__).resolve().parents[1]
SYN = MODULE_ROOT / "data" / "synthetic"
QC = SYN / "qc"
FIGS = MODULE_ROOT / "reports" / "figures" / "synthetic"
NAMES = ["main", "seed2", "ood"]
N_OBS_WEEKS = 193  # from Phase 3 weekly features


# ----------------------------------------------------------------- tiny SVG
def _svg(w, h, body, title):
    wm = (f'<text x="{w/2}" y="{h/2}" font-size="{min(w,h)*0.16}" fill="#d33" '
          f'fill-opacity="0.13" text-anchor="middle" transform="rotate(-22 {w/2} {h/2})" '
          f'font-family="sans-serif" font-weight="bold">DADOS SINTETICOS &#183; SYNTHETIC</text>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
            f'viewBox="0 0 {w} {h}" font-family="sans-serif">'
            f'<rect width="{w}" height="{h}" fill="#fff"/>'
            f'<text x="10" y="18" font-size="13" font-weight="bold">{title}</text>'
            f'<text x="10" y="{h-8}" font-size="10" fill="#a00">SYNTHETIC DATA - not a measurement '
            f'(reports/synthetic-model.md)</text>'
            f'{body}{wm}</svg>')


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
    return "".join(s), (x0, y0, x1, y1)


def _scale(v, lo, hi, a, b):
    if hi == lo:
        return (a + b) / 2
    return a + (b - a) * (v - lo) / (hi - lo)


def fig_lines(path, series, xr, yr, xlab, ylab, title, colors=None):
    W, H = 640, 380
    x0, y0, x1, y1 = 55, 30, W - 20, H - 45
    ax, _ = _axes(x0, y0, x1, y1, xr, yr, xlab, ylab)
    colors = colors or ["#1b6", "#16c", "#c62", "#93c", "#888", "#0a8", "#c26"]
    body = [ax]
    for k, (xs, ys) in enumerate(series):
        pts = " ".join(f"{_scale(x,xr[0],xr[1],x0,x1):.1f},{_scale(y,yr[0],yr[1],y1,y0):.1f}"
                       for x, y in zip(xs, ys))
        body.append(f'<polyline points="{pts}" fill="none" stroke="{colors[k%len(colors)]}" stroke-width="1.3"/>')
    path.write_text(_svg(W, H, "".join(body), title), encoding="utf-8")


def fig_bars(path, labels, values, ylab, title, values2=None, l1="", l2=""):
    W, H = 640, 380
    x0, y0, x1, y1 = 55, 30, W - 20, H - 45
    hi = max(values + (values2 or []) + [1e-9]) * 1.1
    ax, _ = _axes(x0, y0, x1, y1, (0, len(labels)), (0, hi), "", ylab, xticks=len(labels))
    body = [ax]
    n = len(labels)
    bw = (x1 - x0) / n
    for i, v in enumerate(values):
        bx = x0 + i * bw + bw * 0.12
        bh = _scale(v, 0, hi, 0, y1 - y0)
        w = bw * (0.76 if values2 is None else 0.36)
        body.append(f'<rect x="{bx:.1f}" y="{y1-bh:.1f}" width="{w:.1f}" height="{bh:.1f}" fill="#16c"/>')
        if values2 is not None:
            v2 = values2[i]
            bh2 = _scale(v2, 0, hi, 0, y1 - y0)
            body.append(f'<rect x="{bx+w:.1f}" y="{y1-bh2:.1f}" width="{w:.1f}" height="{bh2:.1f}" fill="#c62"/>')
        body.append(f'<text x="{bx+bw*0.35:.1f}" y="{y1+16}" font-size="9" text-anchor="middle">{labels[i]}</text>')
    if values2 is not None:
        body.append(f'<rect x="{x1-120}" y="{y0}" width="10" height="10" fill="#16c"/><text x="{x1-105}" y="{y0+9}" font-size="9">{l1}</text>')
        body.append(f'<rect x="{x1-120}" y="{y0+14}" width="10" height="10" fill="#c62"/><text x="{x1-105}" y="{y0+23}" font-size="9">{l2}</text>')
    path.write_text(_svg(W, H, "".join(body), title), encoding="utf-8")


def fig_scatter(path, xs, ys, xr, yr, xlab, ylab, title, line=None):
    W, H = 640, 380
    x0, y0, x1, y1 = 55, 30, W - 20, H - 45
    ax, _ = _axes(x0, y0, x1, y1, xr, yr, xlab, ylab)
    body = [ax]
    step = max(1, len(xs) // 4000)
    for x, y in zip(xs[::step], ys[::step]):
        body.append(f'<circle cx="{_scale(x,xr[0],xr[1],x0,x1):.1f}" cy="{_scale(y,yr[0],yr[1],y1,y0):.1f}" r="1" fill="#16c" fill-opacity="0.28"/>')
    if line is not None:
        lx, ly = line
        pts = " ".join(f"{_scale(x,xr[0],xr[1],x0,x1):.1f},{_scale(y,yr[0],yr[1],y1,y0):.1f}" for x, y in zip(lx, ly))
        body.append(f'<polyline points="{pts}" fill="none" stroke="#d33" stroke-width="2"/>')
    path.write_text(_svg(W, H, "".join(body), title), encoding="utf-8")


# ----------------------------------------------------------------- QC
def load(name):
    d = SYN / name
    obs = list(csv.DictReader((d / "height_observations.csv").open(encoding="utf-8")))
    roc = list(csv.DictReader((d / "rocada_events.csv").open(encoding="utf-8")))
    meta = json.loads((d / "manifest.json").read_text(encoding="utf-8"))
    return obs, roc, meta


def fnum(x):
    return float(x) if x not in ("", None) else None


def qc_one(name):
    obs, roc, meta = load(name)
    FIGS.mkdir(parents=True, exist_ok=True)
    QC.mkdir(parents=True, exist_ok=True)

    trechos = sorted({o["trecho_id"] for o in obs})
    n_trechos = len(trechos)
    true_h = np.array([float(o["true_height_cm"]) for o in obs])
    obs_h = np.array([float(o["observed_height_cm"]) for o in obs])
    growth = np.array([fnum(o["weekly_growth_cm"]) for o in obs if fnum(o["weekly_growth_cm"]) is not None], dtype=float)
    # per-week growth normalised to cm/week using the actual gap
    grw = []
    for o in obs:
        g, wk = fnum(o["weekly_growth_cm"]), fnum(o["weeks_since_prev_obs"])
        if g is not None and wk and wk > 0 and int(o["rocada_in_prev_interval"]) == 0:
            grw.append(g / wk)
    grw = np.array(grw, dtype=float)

    # missingness
    emitted = len(obs)
    possible = n_trechos * N_OBS_WEEKS
    missing_pct = round(100 * (1 - emitted / possible), 2)

    # nivel distributions
    nt = Counter(int(o["nivel_true"]) for o in obs)
    no = Counter(int(o["nivel_observed"]) for o in obs)
    def pct(c):
        s = sum(c.values())
        return {str(k): round(100 * c.get(k, 0) / s, 2) for k in (0, 1, 2, 3)}

    # roçada frequency
    per_trecho_roc = Counter(r["trecho_id"] for r in roc)
    years = (date.fromisoformat(meta["period"][1]) - date.fromisoformat(meta["period"][0])).days / 365.25
    roc_per_trecho_year = round(len(roc) / n_trechos / years, 2)
    # interval between consecutive roçadas
    rdates = defaultdict(list)
    for r in roc:
        rdates[r["trecho_id"]].append(date.fromisoformat(r["occurred_on"]))
    intervals = []
    for v in rdates.values():
        v.sort()
        intervals += [(v[i + 1] - v[i]).days for i in range(len(v) - 1)]
    intervals = np.array(intervals) if intervals else np.array([np.nan])
    height_at_cut = np.array([float(r["height_before_cm"]) for r in roc])

    # operational status
    not_ready_pct = round(100 * sum(1 for o in obs if o["operational_status"] == "not-ready") / emitted, 2)

    # growth vs GDD / deficit (use per-week normalised growth, no-rocada intervals)
    def rel(key):
        xs, ys = [], []
        for o in obs:
            g, wk = fnum(o["weekly_growth_cm"]), fnum(o["weeks_since_prev_obs"])
            v = fnum(o[key])
            if g is not None and wk and wk > 0 and v is not None and int(o["rocada_in_prev_interval"]) == 0:
                xs.append(v)
                ys.append(g / wk)
        xs, ys = np.array(xs), np.array(ys)
        if len(xs) < 20:
            return xs, ys, [], []
        qs = np.quantile(xs, np.linspace(0, 1, 11))
        bx, by = [], []
        for i in range(10):
            m = (xs >= qs[i]) & (xs <= qs[i + 1])
            if m.sum() > 5:
                bx.append(float(xs[m].mean()))
                by.append(float(ys[m].mean()))
        return xs, ys, bx, by

    gdd_x, gdd_y, gdd_bx, gdd_by = rel("gdd_week_tb15_c")
    def_x, def_y, def_bx, def_by = rel("water_deficit_30d")

    # seasonality: mean true height and mean per-week growth by month
    by_month_h = defaultdict(list)
    by_month_g = defaultdict(list)
    for o in obs:
        m = int(o["observation_date"][5:7])
        by_month_h[m].append(float(o["true_height_cm"]))
        g, wk = fnum(o["weekly_growth_cm"]), fnum(o["weeks_since_prev_obs"])
        if g is not None and wk and wk > 0 and int(o["rocada_in_prev_interval"]) == 0:
            by_month_g[m].append(g / wk)
    month_h = [float(np.mean(by_month_h[m])) for m in range(1, 13)]
    month_g = [float(np.mean(by_month_g[m])) if by_month_g[m] else 0.0 for m in range(1, 13)]

    # post-roçada regrowth: mean true height vs days_since_rocada
    by_tau = defaultdict(list)
    for o in obs:
        by_tau[min(160, int(o["days_since_rocada"]))].append(float(o["true_height_cm"]))
    tau_x = sorted(t for t in by_tau if len(by_tau[t]) >= 10)
    tau_y = [float(np.mean(by_tau[t])) for t in tau_x]

    # between-trecho differences
    per_trecho_meanh = np.array([np.mean([float(o["true_height_cm"]) for o in obs if o["trecho_id"] == t])
                                 for t in trechos])
    per_trecho_meang = []
    for t in trechos:
        gg = [fnum(o["weekly_growth_cm"]) / fnum(o["weeks_since_prev_obs"])
              for o in obs if o["trecho_id"] == t and fnum(o["weekly_growth_cm"]) is not None
              and fnum(o["weeks_since_prev_obs"]) and int(o["rocada_in_prev_interval"]) == 0]
        per_trecho_meang.append(float(np.mean(gg)) if gg else np.nan)
    per_trecho_meang = np.array(per_trecho_meang)

    # week-to-week implausible jumps (obs, excluding rocada intervals): |growth/week| > DH_MAX*7 + 4*sigma
    dhmax_week = 5.0 * 7
    jump_flags = 0
    for o in obs:
        g, wk = fnum(o["weekly_growth_cm"]), fnum(o["weeks_since_prev_obs"])
        if g is not None and wk and wk > 0 and int(o["rocada_in_prev_interval"]) == 0:
            if abs(g / wk) > dhmax_week + 4 * 8:  # generous: physical cap + 4x max meas noise
                jump_flags += 1

    report = {
        "dataset": name, "scenario": meta["scenario"], "seed": meta["seed"],
        "generator_version": meta["generator_version"], "generator_params_hash": meta["generator_params_hash"],
        "is_synthetic": 1,
        "n_trechos": n_trechos, "period": meta["period"],
        "n_height_observations": emitted, "n_rocada_events": len(roc),
        "missing_weeks_pct": missing_pct,
        "height_true": {"min": round(float(true_h.min()), 2), "p05": round(float(np.quantile(true_h, .05)), 2),
                        "median": round(float(np.median(true_h)), 2), "mean": round(float(true_h.mean()), 2),
                        "p95": round(float(np.quantile(true_h, .95)), 2), "max": round(float(true_h.max()), 2),
                        "negative_count": int((true_h < 0).sum()), "over_150_count": int((true_h > 150).sum())},
        "height_observed": {"min": round(float(obs_h.min()), 2), "median": round(float(np.median(obs_h)), 2),
                            "mean": round(float(obs_h.mean()), 2), "max": round(float(obs_h.max()), 2),
                            "negative_count": int((obs_h < 0).sum())},
        "weekly_growth_cm_per_week": {"p05": round(float(np.quantile(grw, .05)), 2),
                                      "median": round(float(np.median(grw)), 2),
                                      "mean": round(float(grw.mean()), 2),
                                      "p95": round(float(np.quantile(grw, .95)), 2),
                                      "max": round(float(grw.max()), 2),
                                      "negative_share_pct": round(100 * float((grw < 0).mean()), 2)},
        "rocada": {"total": len(roc), "per_trecho_per_year": roc_per_trecho_year,
                   "interval_days": {"p10": round(float(np.nanquantile(intervals, .10)), 1),
                                     "median": round(float(np.nanmedian(intervals)), 1),
                                     "p90": round(float(np.nanquantile(intervals, .90)), 1)},
                   "height_at_cut_cm": {"min": round(float(height_at_cut.min()), 1),
                                        "median": round(float(np.median(height_at_cut)), 1),
                                        "mean": round(float(height_at_cut.mean()), 1),
                                        "max": round(float(height_at_cut.max()), 1)},
                   "reason_share": {k: round(100 * v / len(roc), 1) for k, v in Counter(r["reason"] for r in roc).items()}},
        "nivel_true_pct": pct(nt), "nivel_observed_pct": pct(no),
        "operational_not_ready_pct": not_ready_pct,
        "growth_vs_gdd_binned": {"x": [round(v, 1) for v in gdd_bx], "y": [round(v, 3) for v in gdd_by]},
        "growth_vs_water_deficit30_binned": {"x": [round(v, 1) for v in def_bx], "y": [round(v, 3) for v in def_by]},
        "seasonality_month_mean_true_height_cm": [round(v, 1) for v in month_h],
        "seasonality_month_mean_growth_cm_per_week": [round(v, 2) for v in month_g],
        "between_trecho": {"mean_height_cm": {"min": round(float(per_trecho_meanh.min()), 1),
                                              "mean": round(float(per_trecho_meanh.mean()), 1),
                                              "max": round(float(per_trecho_meanh.max()), 1),
                                              "cv": round(float(per_trecho_meanh.std() / per_trecho_meanh.mean()), 3)},
                           "mean_growth_cm_per_week_cv": round(float(np.nanstd(per_trecho_meang) / np.nanmean(per_trecho_meang)), 3)},
        "implausible_weektoweek_jumps": jump_flags,
    }
    (QC / f"qc_synthetic_{name}.json").write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")

    # ---------------- figures
    # 1. height traces for 5 trechos
    samp = trechos[:: max(1, n_trechos // 5)][:5]
    series = []
    for t in samp:
        rows = sorted((o for o in obs if o["trecho_id"] == t), key=lambda r: r["observation_date"])
        xs = [(date.fromisoformat(r["observation_date"]) - date.fromisoformat(meta["period"][0])).days / 7
              for r in rows]
        ys = [float(r["true_height_cm"]) for r in rows]
        series.append((xs, ys))
    fig_lines(FIGS / f"{name}__01_height_traces.svg", series, (0, N_OBS_WEEKS), (0, 120),
              "week index", "true height (cm)", f"{name}: true-height traces, 5 trechos")
    # 2. weekly growth histogram
    hist, edges = np.histogram(grw, bins=30, range=(float(np.quantile(grw, .01)), float(np.quantile(grw, .99))))
    fig_bars(FIGS / f"{name}__02_growth_hist.svg",
             [f"{edges[i]:.1f}" if i % 5 == 0 else "" for i in range(len(hist))],
             list(map(float, hist)), "count", f"{name}: weekly growth (cm/week), no-roçada intervals")
    # 3. seasonality
    fig_bars(FIGS / f"{name}__03_seasonality.svg",
             "J F M A M J J A S O N D".split(), month_g, "mean growth (cm/week)",
             f"{name}: mean weekly growth by month  (seasonality is emergent from real weather)")
    # 4. post-roçada regrowth
    fig_lines(FIGS / f"{name}__04_regrowth.svg", [(tau_x, tau_y)], (0, 160), (0, 90),
              "days since roçada", "mean true height (cm)", f"{name}: post-roçada regrowth curve")
    # 5. growth vs GDD
    fig_scatter(FIGS / f"{name}__05_growth_vs_gdd.svg", list(gdd_x), list(gdd_y),
                (0, float(np.quantile(gdd_x, .99))), (float(np.quantile(gdd_y, .01)), float(np.quantile(gdd_y, .99))),
                "gdd_week (Tb15, C.d)", "growth (cm/week)", f"{name}: growth vs GDD", line=(gdd_bx, gdd_by))
    # 6. growth vs water deficit
    fig_scatter(FIGS / f"{name}__06_growth_vs_deficit.svg", list(def_x), list(def_y),
                (float(np.quantile(def_x, .01)), float(np.quantile(def_x, .99))),
                (float(np.quantile(def_y, .01)), float(np.quantile(def_y, .99))),
                "water_deficit_30d (mm, P-ET0)", "growth (cm/week)",
                f"{name}: growth vs 30-day water balance", line=(def_bx, def_by))
    # 7. nivel distribution
    fig_bars(FIGS / f"{name}__07_nivel_dist.svg", ["0", "1", "2", "3"],
             [pct(nt)[k] for k in ("0", "1", "2", "3")], "% of observations",
             f"{name}: Nivel distribution", values2=[pct(no)[k] for k in ("0", "1", "2", "3")],
             l1="true", l2="observed (not-ready->0)")
    # 8. between-trecho heterogeneity
    hh, ee = np.histogram(per_trecho_meanh, bins=20)
    fig_bars(FIGS / f"{name}__08_between_trecho.svg",
             [f"{ee[i]:.0f}" if i % 4 == 0 else "" for i in range(len(hh))], list(map(float, hh)),
             "count of trechos", f"{name}: per-trecho mean true height (persistent heterogeneity)")

    return report


def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    reports = {}
    for n in NAMES:
        if only and n != only:
            continue
        reports[n] = qc_one(n)
        r = reports[n]
        print(f"\n=== {n} ({r['scenario']}) ===")
        print(f"  obs {r['n_height_observations']}  missing {r['missing_weeks_pct']}%  "
              f"roçada {r['n_rocada_events']} ({r['rocada']['per_trecho_per_year']}/trecho/yr)")
        print(f"  true height  median {r['height_true']['median']}  p95 {r['height_true']['p95']}  "
              f"max {r['height_true']['max']}  neg {r['height_true']['negative_count']}")
        print(f"  growth cm/wk median {r['weekly_growth_cm_per_week']['median']}  "
              f"p95 {r['weekly_growth_cm_per_week']['p95']}  neg% {r['weekly_growth_cm_per_week']['negative_share_pct']}")
        print(f"  Nivel true    {r['nivel_true_pct']}")
        print(f"  Nivel obs     {r['nivel_observed_pct']}")
        print(f"  not-ready {r['operational_not_ready_pct']}%   height@cut median {r['rocada']['height_at_cut_cm']['median']}")
        print(f"  between-trecho mean-height CV {r['between_trecho']['mean_height_cm']['cv']}   "
              f"implausible jumps {r['implausible_weektoweek_jumps']}")


if __name__ == "__main__":
    main()
