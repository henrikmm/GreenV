"""Phase 5 — feature_row materialiser and the frozen feature classification.

Inputs: data/synthetic/{main,seed2,ood}/ (Phase 4, all provenance=synthetic) and
data/real/weather/processed/ (Phase 3, provenance=weather_real, corridor cell).
Respects reports/methodology-decisions.md (weather-v1) and the schema.sql
`feature_row` shape. true_height_cm is never read by this package except for
QC/EDA diagnostics explicitly labelled as such.
"""
FEATURE_SPEC_VERSION = "weather-v1+features-v1"
