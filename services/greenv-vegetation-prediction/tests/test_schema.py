"""Phase 11 — schema.sql, executed for real against a fresh, temporary SQLite database.

This closes a Phase 2 gap explicitly named for this pass: `schema.sql` was written but never
actually run before now. Every test here uses a NEW `tmp_path` database, never the project's own
persistent files (there are none — this pipeline is CSV/JSON, `schema.sql` was never instantiated
until this test suite).
"""
from __future__ import annotations
import sqlite3

import pytest


@pytest.fixture
def db(tmp_path, schema_sql_text):
    """A fresh SQLite database, schema.sql applied, foreign keys ON, closed after the test."""
    conn = sqlite3.connect(tmp_path / "test.db")
    conn.execute("PRAGMA foreign_keys = ON;")
    conn.executescript(schema_sql_text)
    conn.commit()
    yield conn
    conn.close()


EXPECTED_TABLES = {
    "provenance", "trecho", "height_observation", "weather_observation",
    "rocada_event", "feature_row", "prediction", "dataset_manifest",
}
EXPECTED_VIEWS = {"v_trecho_weekly", "v_trecho_latest"}


def _names(db, kind):
    rows = db.execute("SELECT name FROM sqlite_master WHERE type=?", (kind,)).fetchall()
    return {r[0] for r in rows}


class TestSchemaApplies:
    def test_schema_runs_without_error(self, db):
        # Getting here at all means executescript() didn't raise.
        assert db is not None

    def test_expected_tables_exist(self, db):
        assert EXPECTED_TABLES <= _names(db, "table")

    def test_expected_views_exist(self, db):
        assert EXPECTED_VIEWS <= _names(db, "view")

    def test_provenance_seed_rows_present(self, db):
        rows = db.execute("SELECT provenance_code FROM provenance ORDER BY provenance_code").fetchall()
        codes = {r[0] for r in rows}
        assert codes == {"hypothesis", "literature_derived", "measured", "synthetic", "weather_real"}


class TestConstraints:
    def test_trecho_sentido_check_rejects_bad_value(self, db):
        with pytest.raises(sqlite3.IntegrityError):
            db.execute(
                "INSERT INTO trecho (trecho_id, rodovia, sentido, km_start, km_end, length_m, created_at) "
                "VALUES ('bad:x:000000', 'SP-021', 'nordeste', 0.0, 0.5, 500, '2026-01-01T00:00:00Z')"
            )

    def test_trecho_km_end_must_exceed_km_start(self, db):
        with pytest.raises(sqlite3.IntegrityError):
            db.execute(
                "INSERT INTO trecho (trecho_id, rodovia, sentido, km_start, km_end, length_m, created_at) "
                "VALUES ('bad:norte:000000', 'SP-021', 'norte', 1.0, 0.5, 500, '2026-01-01T00:00:00Z')"
            )

    def test_height_observation_requires_existing_trecho(self, db):
        with pytest.raises(sqlite3.IntegrityError):
            db.execute(
                "INSERT INTO height_observation (observation_id, trecho_id, observed_at, observed_on, "
                "iso_year, iso_week, height_cm, data_source, provenance, ingested_at) "
                "VALUES ('obs-1', 'does-not-exist:norte:000000', '2026-01-01T00:00:00Z', '2026-01-01', "
                "2026, 1, 5.0, 'synthetic', 'synthetic', '2026-01-01T00:00:00Z')"
            )

    def test_height_observation_data_source_provenance_must_agree(self, db, trecho_row):
        with pytest.raises(sqlite3.IntegrityError):
            db.execute(
                "INSERT INTO height_observation (observation_id, trecho_id, observed_at, observed_on, "
                "iso_year, iso_week, height_cm, data_source, provenance, ingested_at) "
                "VALUES ('obs-bad', ?, '2026-01-01T00:00:00Z', '2026-01-01', "
                "2026, 1, 5.0, 'measured', 'synthetic', '2026-01-01T00:00:00Z')",
                (trecho_row,),
            )

    def test_synthetic_height_observation_requires_generator_fields(self, db, trecho_row):
        with pytest.raises(sqlite3.IntegrityError):
            db.execute(
                "INSERT INTO height_observation (observation_id, trecho_id, observed_at, observed_on, "
                "iso_year, iso_week, height_cm, data_source, provenance, ingested_at) "
                "VALUES ('obs-nogen', ?, '2026-01-01T00:00:00Z', '2026-01-01', "
                "2026, 1, 5.0, 'synthetic', 'synthetic', '2026-01-01T00:00:00Z')",
                (trecho_row,),
            )

    def test_prediction_not_ready_caps_confidence_at_low(self, db, trecho_row):
        with pytest.raises(sqlite3.IntegrityError):
            db.execute(
                "INSERT INTO prediction (prediction_id, trecho_id, as_of_date, model_kind, model_version, "
                "days_to_30cm_censored, confidence, operational_status, input_data_source, "
                "dataset_is_synthetic, created_at) "
                "VALUES ('pred-bad', ?, '2026-01-01', 'random_forest', 'keep_plus_candidate', "
                "0, 'high', 'not-ready', 'synthetic', 1, '2026-01-01T00:00:00Z')",
                (trecho_row,),
            )

    def test_prediction_not_ready_with_low_confidence_is_accepted(self, db, trecho_row):
        db.execute(
            "INSERT INTO prediction (prediction_id, trecho_id, as_of_date, model_kind, model_version, "
            "days_to_30cm_censored, confidence, operational_status, input_data_source, "
            "dataset_is_synthetic, created_at) "
            "VALUES ('pred-ok', ?, '2026-01-01', 'random_forest', 'keep_plus_candidate', "
            "0, 'low', 'not-ready', 'synthetic', 1, '2026-01-01T00:00:00Z')",
            (trecho_row,),
        )
        db.commit()
        row = db.execute("SELECT confidence FROM prediction WHERE prediction_id='pred-ok'").fetchone()
        assert row[0] == "low"


@pytest.fixture
def trecho_row(db):
    """Inserts one valid trecho and returns its id, for tests that need a valid FK target."""
    db.execute(
        "INSERT INTO trecho (trecho_id, rodovia, sentido, km_start, km_end, length_m, created_at) "
        "VALUES ('SP-021:norte:000000', 'SP-021', 'norte', 0.0, 0.5, 500, '2026-01-01T00:00:00Z')"
    )
    db.commit()
    return "SP-021:norte:000000"


class TestNivelGeneratedColumn:
    """The exact boundary matrix also exercised in test_classification.py, but here checked
    against schema.sql's OWN generated column, not the Python port of it -- proving the two never
    drift apart."""

    @pytest.mark.parametrize("height_cm, operational_status, expected_nivel", [
        (None, "ready", 0),
        (5.0, "not-ready", 0),      # not-ready wins even with a real height present
        (0.0, "ready", 1),
        (9.99, "ready", 1),
        (10.0, "ready", 2),         # boundary: <10 is level 1, so exactly 10 falls to level 2
        (29.99, "ready", 2),
        (30.0, "ready", 2),         # boundary: <=30 is level 2, so exactly 30 is NOT level 3
        (30.01, "ready", 3),
        (100.0, "ready", 3),
    ])
    def test_nivel_matches_frozen_formula(self, db, trecho_row, height_cm, operational_status, expected_nivel):
        # synthetic rows must carry generator_version/seed/params_hash (a schema CHECK) --
        # included from the start here, unlike the deliberately-incomplete row in
        # test_synthetic_height_observation_requires_generator_fields above.
        db.execute(
            "INSERT INTO height_observation (observation_id, trecho_id, observed_at, observed_on, "
            "iso_year, iso_week, height_cm, operational_status, data_source, provenance, "
            "generator_version, generator_seed, generator_params_hash, ingested_at) "
            "VALUES (?, ?, '2026-01-01T00:00:00Z', '2026-01-01', 2026, 1, ?, ?, 'synthetic', 'synthetic', "
            "'v1', 42, 'hash123', '2026-01-01T00:00:00Z')",
            (f"obs-{height_cm}-{operational_status}", trecho_row, height_cm, operational_status),
        )
        db.commit()
        row = db.execute(
            "SELECT nivel FROM height_observation WHERE observation_id=?",
            (f"obs-{height_cm}-{operational_status}",),
        ).fetchone()
        assert row[0] == expected_nivel


class TestRoundTrip:
    """One minimal, valid insert-then-select round trip per main table."""

    def test_weather_observation_round_trip(self, db, trecho_row):
        db.execute(
            "INSERT INTO weather_observation (weather_id, trecho_id, obs_date, iso_year, iso_week, "
            "gdd_base_temp_c, provider, data_source, provenance, retrieved_at) "
            "VALUES ('wx-1', ?, '2026-01-01', 2026, 1, 15.0, 'open-meteo', 'weather_real', 'weather_real', "
            "'2026-01-01T00:00:00Z')",
            (trecho_row,),
        )
        db.commit()
        row = db.execute("SELECT weather_id, provider FROM weather_observation WHERE weather_id='wx-1'").fetchone()
        assert row == ("wx-1", "open-meteo")

    def test_rocada_event_round_trip(self, db, trecho_row):
        db.execute(
            "INSERT INTO rocada_event (rocada_id, trecho_id, occurred_on, source, data_source, "
            "provenance, recorded_at) "
            "VALUES ('roc-1', ?, '2026-02-01', 'synthetic', 'synthetic', 'synthetic', "
            "'2026-02-01T00:00:00Z')",
            (trecho_row,),
        )
        db.commit()
        row = db.execute("SELECT rocada_id FROM rocada_event WHERE rocada_id='roc-1'").fetchone()
        assert row[0] == "roc-1"

    def test_feature_row_round_trip(self, db, trecho_row):
        db.execute(
            "INSERT INTO feature_row (feature_row_id, trecho_id, iso_year, iso_week, as_of_date, "
            "data_source, split, split_scheme, feature_spec_version, built_at) "
            "VALUES ('fr-1', ?, 2026, 1, '2026-01-01', 'synthetic', 'train', 'temporal-cut-2025', "
            "'weather-v1+features-v1', '2026-01-01T00:00:00Z')",
            (trecho_row,),
        )
        db.commit()
        row = db.execute("SELECT split FROM feature_row WHERE feature_row_id='fr-1'").fetchone()
        assert row[0] == "train"

    def test_prediction_round_trip(self, db, trecho_row):
        db.execute(
            "INSERT INTO prediction (prediction_id, trecho_id, as_of_date, model_kind, model_version, "
            "days_to_30cm_censored, input_data_source, dataset_is_synthetic, created_at) "
            "VALUES ('pred-rt', ?, '2026-01-01', 'random_forest', 'keep_plus_candidate', 0, "
            "'synthetic', 1, '2026-01-01T00:00:00Z')",
            (trecho_row,),
        )
        db.commit()
        row = db.execute("SELECT model_kind FROM prediction WHERE prediction_id='pred-rt'").fetchone()
        assert row[0] == "random_forest"

    def test_dataset_manifest_round_trip(self, db):
        db.execute(
            "INSERT INTO dataset_manifest (manifest_id, kind, created_at, is_synthetic) "
            "VALUES ('manifest-1', 'model_training', '2026-01-01T00:00:00Z', 1)"
        )
        db.commit()
        row = db.execute("SELECT kind FROM dataset_manifest WHERE manifest_id='manifest-1'").fetchone()
        assert row[0] == "model_training"


class TestViews:
    def test_v_trecho_weekly_computes_lag_and_growth(self, db, trecho_row):
        for week, height in ((1, 10.0), (2, 13.0)):
            db.execute(
                "INSERT INTO height_observation (observation_id, trecho_id, observed_at, observed_on, "
                "iso_year, iso_week, height_cm, data_source, provenance, generator_version, "
                "generator_seed, generator_params_hash, ingested_at) "
                "VALUES (?, ?, '2026-01-0'||?||'T00:00:00Z', '2026-01-0'||?, 2026, ?, ?, 'synthetic', "
                "'synthetic', 'v1', 42, 'hash', '2026-01-01T00:00:00Z')",
                (f"obs-w{week}", trecho_row, week, week, week, height),
            )
        db.commit()
        row = db.execute(
            "SELECT height_cm, height_prev_cm, weekly_growth_cm FROM v_trecho_weekly "
            "WHERE trecho_id=? AND iso_week=2",
            (trecho_row,),
        ).fetchone()
        assert row == (13.0, 10.0, 3.0)

    def test_v_trecho_latest_returns_most_recent_row(self, db, trecho_row):
        for week, ts in ((1, "2026-01-01T00:00:00Z"), (2, "2026-01-08T00:00:00Z")):
            db.execute(
                "INSERT INTO height_observation (observation_id, trecho_id, observed_at, observed_on, "
                "iso_year, iso_week, height_cm, data_source, provenance, generator_version, "
                "generator_seed, generator_params_hash, ingested_at) "
                "VALUES (?, ?, ?, '2026-01-01', 2026, ?, 1.0, 'synthetic', 'synthetic', 'v1', 42, "
                "'hash', '2026-01-01T00:00:00Z')",
                (f"obs-latest-{week}", trecho_row, ts, week),
            )
        db.commit()
        row = db.execute(
            "SELECT observation_id FROM v_trecho_latest WHERE trecho_id=?", (trecho_row,)
        ).fetchone()
        assert row[0] == "obs-latest-2"
