"""The synthetic vegetation-growth simulation. Implements reports/synthetic-model.md.

Daily latent simulation, weekly observation. Growth is non-negative every day;
height falls only through explicit roçada events.
"""
from __future__ import annotations
import csv, json, math
from datetime import date
from pathlib import Path

import numpy as np

from . import GENERATOR_VERSION
from .params import resolve, params_hash

MODULE_ROOT = Path(__file__).resolve().parents[3]
WX_DAILY = MODULE_ROOT / "data" / "real" / "weather" / "processed" / "weather_observation.csv"
WX_WEEKLY = MODULE_ROOT / "data" / "real" / "weather" / "processed" / "weather_weekly_features.csv"
TRECHO_MAP = MODULE_ROOT / "data" / "real" / "weather" / "processed" / "trecho_weather_cell.csv"

CORRIDOR_CELL = "open-meteo:era5_seamless:-23.5:-46.8"   # weather-v1 DEC-001 canonical source

_smoothstep = lambda x: x * x * (3.0 - 2.0 * x)
_sigmoid = lambda x: 1.0 / (1.0 + math.exp(-max(-40.0, min(40.0, x))))


# ---------------------------------------------------------------- load real weather
def load_weather():
    cells: dict[str, dict] = {}
    with WX_DAILY.open(encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            c = cells.setdefault(r["grid_cell_id"], {"date": [], "tmin": [], "tmax": [],
                                                     "precip": [], "et0": [], "sw": []})
            c["date"].append(r["obs_date"])
            c["tmin"].append(float(r["temp_min_c"]))
            c["tmax"].append(float(r["temp_max_c"]))
            c["precip"].append(float(r["precip_mm"]))
            c["et0"].append(float(r["et0_mm"]))
            c["sw"].append(float(r["shortwave_radiation_mj_m2"]))
    for c in cells.values():
        for k in ("tmin", "tmax", "precip", "et0", "sw"):
            c[k] = np.asarray(c[k], dtype=float)
        # daily climatic water balance and its trailing-30d sum
        bal = c["precip"] - c["et0"]
        c["def30"] = np.array([bal[max(0, i - 29):i + 1].sum() for i in range(len(bal))])
        c["idx"] = {d: i for i, d in enumerate(c["date"])}

    weekly: dict[tuple[str, str], dict] = {}
    with WX_WEEKLY.open(encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            weekly[(r["grid_cell_id"], r["as_of_date"])] = r
    obs_dates = sorted({d for (cid, d) in weekly if cid == CORRIDOR_CELL})
    return cells, weekly, obs_dates


def load_trechos():
    out = []
    with TRECHO_MAP.open(encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            out.append({
                "trecho_id": r["trecho_id"], "rodovia": r["rodovia"], "sentido": r["sentido"],
                "km_start": float(r["km_start"]), "km_end": float(r["km_end"]),
                "km_mid": float(r["km_mid"]),
                "centroid_lat": float(r["anchor_lat"]), "centroid_lon": float(r["anchor_lon"]),
                "grid_cell_id": r["grid_cell_id"],
            })
    return out


# ---------------------------------------------------------------- one trecho
def _draw_trecho(rng, P):
    veg = rng.choice(list(P["VEG_PROBS"]), p=[P["VEG_PROBS"][k] for k in P["VEG_PROBS"]])
    # m_i : persistent growth multiplier (hidden)
    if P["trecho_effect_dist"] == "lognormal_bimodal":
        if rng.random() < 0.5:
            m = float(np.exp(rng.normal(math.log(0.80), 0.12)))
        else:
            m = float(np.exp(rng.normal(math.log(1.25), 0.12)))
    else:
        sigma = math.sqrt(math.log(1.0 + P["CV_M"] ** 2))
        m = float(np.exp(rng.normal(-0.5 * sigma ** 2, sigma)))          # mean 1
    s = float(np.clip(np.exp(rng.normal(0.0, math.sqrt(math.log(1 + P["S_CV"] ** 2)))),
                      P["S_CLIP"][0], P["S_CLIP"][1]))                    # hidden water sensitivity
    hmax = float(np.clip(rng.normal(P["H0_PLATEAU"] + P["VEG_SHIFT"][veg], P["HMAX_SD"]), 55, 140))
    trig = float(np.clip(rng.normal(P["TRIG0"], P["TRIG_SD"]), P["TRIG_CLIP"][0], P["TRIG_CLIP"][1]))
    h0 = float(np.clip(rng.normal(P["H0_MEAN"], P["H0_SD"]), 3.0, hmax))
    tau0 = int(rng.integers(P["TAU0_LO"], P["TAU0_HI"]))
    return dict(veg=veg, m=m, s=s, hmax=hmax, trig=trig, h0=h0, tau0=tau0)


def _g_water(def30, s_i, P):
    z = (def30 + P["DEF_SHIFT"]) / P["DEF_SCALE"]
    if P["hydric_response"] == "piecewise_linear":
        # OOD: linear ramp, clipped at [W_MIN, 1.05]
        return float(np.clip(P["W_MIN"] + (1 - P["W_MIN"]) * (0.5 + 0.5 * np.clip(s_i * z, -1, 1)),
                             P["W_MIN"], 1.05))
    return P["W_MIN"] + (1 - P["W_MIN"]) * _sigmoid(s_i * z)             # baseline: logistic


def _approach(H, Hmax_eff, curve):
    if curve == "monomolecular":
        return max(0.0, 1.0 - H / Hmax_eff)                              # Mitscherlich: rate ~ (Hmax - H)
    return max(0.0, 1.0 - H / Hmax_eff)                                  # logistic multiplies by H separately


def simulate(seed: int, scenario: str = "baseline"):
    P = resolve(scenario)
    phash = params_hash(P)
    cells, weekly, obs_dates = load_weather()
    trechos = load_trechos()

    ss = np.random.SeedSequence(seed)
    child = ss.spawn(2 * len(trechos))

    all_dates = cells[CORRIDOR_CELL]["date"]
    dt_all = [date.fromisoformat(d) for d in all_dates]
    obs_set = set(obs_dates)

    OBS_ROWS, ROC_ROWS = [], []

    for ti, tr in enumerate(trechos):
        rng_draw = np.random.default_rng(child[2 * ti])
        rng_seq = np.random.default_rng(child[2 * ti + 1])
        d = _draw_trecho(rng_draw, P)

        c = cells[tr["grid_cell_id"]]
        H = d["h0"]
        tau = d["tau0"]
        sw_cum = 0.0
        pending_cut = None           # (cut_date_str, reason, delay_days)
        prev_missing = False
        last_obs_date = None
        last_obs_height = None

        for di, dstr in enumerate(all_dates):
            dt = dt_all[di]
            doy = dt.timetuple().tm_yday

            # --- execute a scheduled roçada on its day ---
            if pending_cut is not None and dstr == pending_cut[0]:
                h_before = H
                res_lo, res_hi = P["RES_RANGE"]
                residual = float(rng_seq.uniform(res_lo, res_hi))
                H = residual
                tau = 0
                sw_cum = 0.0
                ROC_ROWS.append(dict(
                    rocada_id=f"{tr['trecho_id']}:{dstr}",
                    trecho_id=tr["trecho_id"], rodovia=tr["rodovia"], sentido=tr["sentido"],
                    km_mid=tr["km_mid"], grid_cell_id=tr["grid_cell_id"],
                    occurred_on=dstr, height_before_cm=round(h_before, 2),
                    height_after_cm=round(residual, 2), trigger_height_cm=round(d["trig"], 2),
                    delay_days=pending_cut[2], reason=pending_cut[1],
                    source="synthetic", data_source="synthetic", provenance="synthetic",
                    generator_version=GENERATOR_VERSION, generator_seed=seed,
                    generator_params_hash=phash, scenario=scenario,
                ))
                pending_cut = None

            # --- daily growth ---
            i = c["idx"][dstr]
            tmn, tmx = c["tmin"][i], c["tmax"][i]
            gdd_d = max(0.0, (tmx + tmn) / 2.0 - P["Tb"])
            def30 = float(c["def30"][i])
            sw_d = float(c["sw"][i])
            sw_cum += sw_d

            phi_lag = P["LAG_FLOOR"] + (1 - P["LAG_FLOOR"]) * _smoothstep(min(1.0, tau / P["LAG"]))
            g_temp = min(1.8, gdd_d / P["GDD_REF"])
            g_water = _g_water(def30, d["s"], P)
            g_seas = 1.0 + P["A_S"] * math.sin(2 * math.pi * (doy - P["PHI_S"]) / 365.25)
            hmax_eff = d["hmax"] * (1 + P["K_RAD"] * float(np.clip(sw_cum / P["SW_CUM_REF"] - 1, -0.15, 0.15)))
            hmax_eff = max(hmax_eff, 10.0)

            r_daily = P["R0"] * d["m"] * phi_lag * g_temp * g_water * g_seas
            eps = math.exp(rng_seq.normal(0.0, P["SIGMA_PROC"]))
            if P["regrowth_curve"] == "monomolecular":
                dH = r_daily * eps * hmax_eff * _approach(H, hmax_eff, "monomolecular")
            else:
                dH = r_daily * eps * max(H, P["H_FLOOR"]) * _approach(H, hmax_eff, "logistic")
            dH = min(max(0.0, dH), P["DH_MAX"])
            H = min(max(0.0, H + dH), P["H_HARDCAP"])
            tau += 1

            if dstr not in obs_set:
                continue

            # --- weekly roçada inspection (uses current true height; no future info) ---
            month = dt.month
            frozen = month in P.get("winter_maintenance_freeze", [])
            if pending_cut is None and not frozen:
                season_factor = P["SEASON_FACTOR_WET"] if month in P["WET_MONTHS"] else P["SEASON_FACTOR_DRY"]
                u = rng_seq.random()
                sched = None
                if H >= d["trig"] and u < P["INSPECT_HIT"]:
                    sched = "trigger"
                elif 30.0 <= H < d["trig"] and u < P["PROACTIVE_RATE"] * season_factor:
                    sched = "proactive"
                if sched:
                    delay = int(np.clip(round(rng_seq.gamma(P["DELAY_GAMMA_SHAPE"],
                                                            P["DELAY_MEAN"] / P["DELAY_GAMMA_SHAPE"])),
                                        P["DELAY_CLIP"][0], P["DELAY_CLIP"][1]))
                    cut_i = min(di + delay, len(all_dates) - 1)
                    pending_cut = (all_dates[cut_i], sched, delay)

            # --- missingness (gaps cluster; base chosen so the stationary missing
            #     rate ~= MISS_P despite the post-gap bonus) ---
            base_miss = P["MISS_P"] * (1.0 - P["MISS_GAP_BONUS"])
            miss_p = min(P["MISS_P_CLIP"], base_miss + (P["MISS_GAP_BONUS"] if prev_missing else 0.0))
            if rng_seq.random() < miss_p:
                prev_missing = True
                continue
            prev_missing = False

            # --- observation: true vs observed ---
            true_h = H
            xi = rng_seq.normal(0.0, P["SIGMA_MEAS"])
            if rng_seq.random() < P["SIGMA_MEAS_OUTLIER_P"]:
                xi *= P["SIGMA_MEAS_OUTLIER_MULT"]
            obs_h = float(np.clip(true_h + xi, P["OBS_CLIP"][0], P["OBS_CLIP"][1]))

            weeks_gap = None
            growth = None
            if last_obs_date is not None:
                weeks_gap = round((dt - date.fromisoformat(last_obs_date)).days / 7.0, 2)
                growth = round(obs_h - last_obs_height, 2)
            rocada_in_prev = 0
            if last_obs_date is not None:
                rocada_in_prev = int(any(
                    last_obs_date < rr["occurred_on"] <= dstr
                    for rr in ROC_ROWS if rr["trecho_id"] == tr["trecho_id"]))

            # --- operational status / blockers (own quality draws; no future info) ---
            coverage = float(np.clip(rng_seq.normal(P["COVERAGE_MEAN"], P["COVERAGE_SD"]), 0.05, 1.0))
            frames = int(rng_seq.poisson(P["FRAME_LAMBDA"]))
            degraded = rng_seq.random() < P["DEGRADED_P"]
            blockers = []
            if coverage < P["COVERAGE_NOT_READY"]:
                blockers.append("low-coverage")
            if frames < P["FRAME_NOT_READY"]:
                blockers.append("insufficient-frames")
            if degraded:
                blockers.append("depth-degraded")
            op_status = "not-ready" if blockers else "ready"

            def _nivel(h):
                return 1 if h < 10 else (2 if h <= 30 else 3)
            nivel_true = _nivel(true_h)
            nivel_obs = 0 if op_status == "not-ready" else _nivel(obs_h)

            wf = weekly.get((tr["grid_cell_id"], dstr), {})
            gv = lambda k: (float(wf[k]) if wf.get(k) not in (None, "") else None)

            OBS_ROWS.append(dict(
                trecho_id=tr["trecho_id"], rodovia=tr["rodovia"], sentido=tr["sentido"],
                km_start=tr["km_start"], km_end=tr["km_end"], km_mid=tr["km_mid"],
                centroid_lat=tr["centroid_lat"], centroid_lon=tr["centroid_lon"],
                grid_cell_id=tr["grid_cell_id"],
                observation_date=dstr,
                iso_year=int(wf.get("iso_year") or dt.isocalendar().year),
                iso_week=int(wf.get("iso_week") or dt.isocalendar().week),
                true_height_cm=round(true_h, 2),
                observed_height_cm=round(obs_h, 2),
                previous_observed_height_cm=(round(last_obs_height, 2) if last_obs_height is not None else None),
                weekly_growth_cm=growth,
                weeks_since_prev_obs=weeks_gap,
                days_since_rocada=tau,
                rocada_in_prev_interval=rocada_in_prev,
                vegetation_type=d["veg"],
                vegetation_type_provenance="hypothesis",
                gdd_week_tb10_c=gv("gdd_week_tb10_c"),
                gdd_week_tb15_c=gv("gdd_week_tb15_c"),
                gdd_week_tb17_c=gv("gdd_week_tb17_c"),
                tmin_week_c=gv("tmin_week_c"), tmean_week_c=gv("tmean_week_c"), tmax_week_c=gv("tmax_week_c"),
                rain_7d_mm=gv("rain_7d_mm"), rain_14d_mm=gv("rain_14d_mm"), rain_30d_mm=gv("rain_30d_mm"),
                shortwave_7d_mj_m2=gv("shortwave_7d_mj_m2"),
                et0_7d_mm=gv("et0_7d_mm"),
                water_deficit_30d=gv("water_deficit_30d"),
                week_of_year=int(wf.get("week_of_year") or dt.isocalendar().week),
                season_sin=gv("season_sin"), season_cos=gv("season_cos"),
                dry_season_flag=int(wf.get("dry_season_flag") or (1 if dt.month in (4, 5, 6, 7, 8, 9) else 0)),
                week_days_present=int(wf.get("week_days_present") or 7),
                operational_status=op_status,
                blockers=json.dumps(blockers),
                cell_coverage=round(coverage, 3),
                frame_count=frames,
                nivel_true=nivel_true,
                nivel_observed=nivel_obs,
                data_source="synthetic",
                provenance="synthetic",
                generator_version=GENERATOR_VERSION,
                generator_seed=seed,
                generator_params_hash=phash,
                scenario=scenario,
            ))
            last_obs_date = dstr
            last_obs_height = obs_h

    meta = dict(
        generator_version=GENERATOR_VERSION, seed=seed, scenario=scenario,
        generator_params_hash=phash,
        n_trechos=len(trechos), n_obs_dates=len(obs_dates),
        period=[all_dates[0], all_dates[-1]],
        resolved_params={k: v for k, v in P.items() if k != "_literature"},
        literature_params=P["_literature"],
    )
    return OBS_ROWS, ROC_ROWS, meta
