"""Phase 11 — a small, fast end-to-end smoke test: the REAL Phase 9 snapshot flows through the
loader, the service layer, and out through the HTTP API, proving the pieces actually connect.
No training, no TEST/OOD data, no synthetic-generator run.
"""
from __future__ import annotations
import json

from fastapi.testclient import TestClient

from greenv_vegpred.api import loader, service
from greenv_vegpred.api.app import app


def test_snapshot_file_exists_and_covers_the_whole_corridor(module_root):
    snapshot_path = module_root / "data" / "forecast" / "ranking_current.json"
    assert snapshot_path.exists()
    raw = json.loads(snapshot_path.read_text(encoding="utf-8"))
    assert len(raw) == 118  # every SP-021 trecho, both sentidos


def test_loader_reads_and_reranks_the_snapshot():
    loader.get_store.cache_clear()
    snap = loader.get_store().snapshot()
    assert snap is not None and len(snap) == 118
    ranks = [row["rank"] for row in snap]
    assert ranks == sorted(ranks) == list(range(1, 119))


def test_service_enriches_every_row_with_trecho_metadata():
    loader.get_store.cache_clear()
    top = service.get_ranking(limit=5)
    assert len(top) == 5
    for row in top:
        assert row["rodovia"] == "SP-021"
        assert row["sentido"] in ("norte", "sul")
        assert row["vegetation_level"] in (0, 1, 2, 3)


def test_end_to_end_through_the_http_layer():
    """snapshot -> loader -> service -> route -> HTTP response, in one call, then cross-checked
    against the single-trecho endpoint for the same trecho."""
    loader.get_store.cache_clear()
    client = TestClient(app)
    r = client.get("/api/v1/forecasts/ranking?limit=1")
    assert r.status_code == 200
    row = r.json()[0]
    assert row["rank"] == 1
    assert row["data_provenance"] == "synthetic"

    single = client.get(f"/api/v1/forecasts/{row['trecho_id']}")
    assert single.status_code == 200
    assert single.json()["days_until_critical"] == row["days_until_critical"]
