"""Resolved parameter set for the synthetic generator.

Load-bearing values come from data/reference/params.yaml (with their confidence
tag). The dynamics-glue constants (rates, normalisers, noise) are declared here
as `hypothesis` -- reports/synthetic-model.md §10 lists every one.

`resolve(scenario)` returns a flat dict; `params_hash(d)` is the sha256 of it as
sorted JSON, so any change of any value changes generator_params_hash.
"""
from __future__ import annotations
import hashlib, json, re
from pathlib import Path

MODULE_ROOT = Path(__file__).resolve().parents[3]
PARAMS_YAML = MODULE_ROOT / "data" / "reference" / "params.yaml"


def _yaml_block(key: str) -> dict:
    """Minimal extractor: pull `value` / `range` / `confidence` for one top-level
    key of params.yaml (no yaml lib in this environment)."""
    text = PARAMS_YAML.read_text(encoding="utf-8")
    lines = text.splitlines()
    out: dict = {}
    inblock = False
    for ln in lines:
        if re.match(rf"^{re.escape(key)}:\s*$", ln):
            inblock = True
            continue
        if inblock:
            if ln and not ln.startswith((" ", "\t")):
                break
            m = re.match(r"\s+(value|range|confidence|unit):\s*(.+?)\s*$", ln)
            if m:
                k, v = m.group(1), m.group(2)
                if k == "range":
                    nums = re.findall(r"-?\d+\.?\d*", v)
                    out["range"] = [float(x) for x in nums] if nums else None
                elif k == "value":
                    try:
                        out["value"] = float(v)
                    except ValueError:
                        out["value"] = v.strip().strip('"')
                else:
                    out[k] = v.strip().strip('"')
    return out


def _lit() -> dict:
    """The params.yaml-sourced values the generator uses, each with provenance."""
    keys = [
        "gdd_base_temperature", "water_deficit_growth_multiplier", "mowing_trigger_height_cm",
        "regrowth_lag_days", "regrowth_time_to_near_max_rate_days", "rocada_residual_height_cm",
        "plateau_height_cm", "measurement_noise_sigma_cm", "missing_week_probability",
        "not_ready_fraction", "trecho_random_effect_cv",
    ]
    return {k: _yaml_block(k) for k in keys}


# ---------------------------------------------------------------------------
# hypothesis constants (dynamics glue) -- synthetic-model.md §2, §3, §6-§8
# ---------------------------------------------------------------------------
# NOTE: R0 and DELAY_MEAN were lowered from 0.11 / 14 d after the first QC pass
# (reports/synthetic-model.md was written with those). The first run put the
# corridor at ~48% Nivel 3 with a median cut height of ~78 cm -- i.e. the modelled
# maintenance was chronically ~1 month behind DNIT's 50 cm trigger, which is
# unrealistic for a monitored highway. These are `hypothesis` constants; the
# adjustment is for operational/biological realism, NOT for downstream metrics.
_HYPO = dict(
    R0=0.085, GDD_REF=5.0, LAG_FLOOR=0.10,
    DEF_SHIFT=15.0, DEF_SCALE=40.0,
    A_S=0.03, PHI_S=20.0,
    K_RAD=0.10, SW_CUM_REF=250.0,
    SIGMA_PROC=0.15, H_FLOOR=2.0, DH_MAX=5.0, H_HARDCAP=150.0,
    HMAX_SD=12.0, VEG_SHIFT={"braquiaria": 8.0, "capim_gordura": 15.0, "ruderal_mix": -10.0},
    VEG_PROBS={"braquiaria": 0.55, "capim_gordura": 0.20, "ruderal_mix": 0.25},
    S_CV=0.30, S_CLIP=[0.5, 1.8],
    TRIG_SD=8.0, TRIG_CLIP=[25.0, 70.0],
    DELAY_MEAN=8.0, DELAY_GAMMA_SHAPE=2.5, DELAY_CLIP=[2, 45],
    INSPECT_HIT=0.90, PROACTIVE_RATE=0.05,
    SEASON_FACTOR_WET=1.0, SEASON_FACTOR_DRY=0.55,
    WET_MONTHS=[10, 11, 12, 1, 2, 3],
    SIGMA_MEAS_OUTLIER_P=0.02, SIGMA_MEAS_OUTLIER_MULT=3.0,
    MISS_GAP_BONUS=0.35, MISS_P_CLIP=0.85, OBS_CLIP=[0.0, 160.0],
    # tuned so overall not-ready share ~= not_ready_fraction (0.20), synthetic-model.md §8
    COVERAGE_MEAN=0.80, COVERAGE_SD=0.16, FRAME_LAMBDA=85,
    DEGRADED_P=0.16, COVERAGE_NOT_READY=0.50, FRAME_NOT_READY=25,
    H0_MEAN=20.0, H0_SD=10.0, TAU0_LO=5, TAU0_HI=60,
)

# scenario overrides -- synthetic-model.md "OOD holdout"
_SCENARIOS = {
    "baseline": dict(
        scenario="baseline",
        hydric_response="logistic",
        trecho_effect_dist="lognormal_unimodal",
        regrowth_curve="logistic",
        winter_maintenance_freeze=[],
    ),
    "ood": dict(
        scenario="ood",
        hydric_response="piecewise_linear",
        W_MIN_OVERRIDE=0.45,
        DEF_SCALE=55.0,
        trecho_effect_dist="lognormal_bimodal",
        regrowth_curve="monomolecular",
        # monomolecular dH ~ (Hmax - H) is large early, so its rate constant must be
        # far smaller than the logistic R0 (which multiplies by H). Calibrated so
        # 5->30 cm takes ~2 weeks in summer, matching the baseline regrowth speed.
        R0_OVERRIDE=0.020,
        LAG_OVERRIDE=13.0,
        DELAY_MEAN=16.0,
        TRIG0_OVERRIDE=58.0, TRIG_SD=10.0,
        winter_maintenance_freeze=[7],   # July stand-down only (softened from Jun-Aug)
    ),
}


def resolve(scenario: str = "baseline") -> dict:
    if scenario not in _SCENARIOS:
        raise ValueError(f"unknown scenario {scenario!r}; choose from {list(_SCENARIOS)}")
    lit = _lit()
    d = dict(_HYPO)
    d["_literature"] = lit
    d["Tb"] = float(lit["gdd_base_temperature"]["value"])          # scenario Tb (reference 15)
    d["W_MIN"] = float(lit["water_deficit_growth_multiplier"]["value"])
    d["TRIG0"] = float(lit["mowing_trigger_height_cm"]["value"])
    d["LAG"] = float(lit["regrowth_lag_days"]["value"])
    d["RES_RANGE"] = lit["rocada_residual_height_cm"]["range"] or [3.0, 8.0]
    d["H0_PLATEAU"] = float(lit["plateau_height_cm"]["value"])
    d["SIGMA_MEAS"] = float(lit["measurement_noise_sigma_cm"]["value"])
    d["MISS_P"] = float(lit["missing_week_probability"]["value"])
    d["NOT_READY_TARGET"] = float(lit["not_ready_fraction"]["value"])
    d["CV_M"] = float(lit["trecho_random_effect_cv"]["value"])

    sc = _SCENARIOS[scenario]
    d.update({k: v for k, v in sc.items() if not k.endswith("_OVERRIDE")})
    if "W_MIN_OVERRIDE" in sc:
        d["W_MIN"] = sc["W_MIN_OVERRIDE"]
    if "LAG_OVERRIDE" in sc:
        d["LAG"] = sc["LAG_OVERRIDE"]
    if "TRIG0_OVERRIDE" in sc:
        d["TRIG0"] = sc["TRIG0_OVERRIDE"]
    if "R0_OVERRIDE" in sc:
        d["R0"] = sc["R0_OVERRIDE"]
    return d


def params_hash(d: dict) -> str:
    def _clean(x):
        if isinstance(x, dict):
            return {k: _clean(v) for k, v in sorted(x.items())}
        if isinstance(x, (list, tuple)):
            return [_clean(v) for v in x]
        return x
    payload = json.dumps(_clean(d), sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:16]
