"""DA3 inference service — Cloud Run Service, 1x NVIDIA L4, scale-to-zero.

Design notes that matter:

* Inference runs in a thread executor and is guarded by a lock, so exactly one run
  happens at a time while `/gpu` stays responsive. That is what makes the app's live
  VRAM readout possible — Cloud Run must therefore be deployed with concurrency > 1
  even though only one inference executes at once.
* Frames arrive already extracted. The Mac does ffmpeg (local-first); the server
  never resamples and never downloads video.
* Artifacts go to GCS when `VERGE_OUTPUT_BUCKET` is set, otherwise they stay on
  local disk and are served from `/artifact`. The mock server uses the same shape.
  Publishing is what lets results outlive the instance, and it is why this service no
  longer needs `--min-instances=1`: an /artifact URL was only ever valid on the machine
  that produced it, so Cloud Run replacing an instance destroyed a paid run (it did, on
  2026-08-05, 86 ms after the /infer response). A bucket object has no such affinity.
  Publishing NEVER fails a run — see _publish — because the GPU is already paid for by
  the time it happens.
* Transience is enforced here, not left to chance. Uploaded frames are discarded the
  moment inference returns, and run directories are swept once they pass
  `VERGE_RUN_TTL_SECONDS`. Before 2026-08-06 nothing deleted a successful run at all --
  the instance dying was the only cleanup, which made the "nothing is kept unless the
  user saves it" policy an accident of scheduling rather than a property of the service.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
import shutil
import tempfile
import threading
import time
import uuid
import zipfile
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse

from contract import (
    Artifact,
    Diagnostics,
    FrameInfo,
    GpuSnapshot,
    HealthResponse,
    InferManifest,
    InferParams,
    PreviewStatus,
    Timing,
    VramStats,
    WarmupResponse,
)
import preview
import vram

MODEL_DIR = os.environ.get("VERGE_MODEL_DIR", "/opt/mvl-models/da3_nested_giant_large_1_1")
OUTPUT_BUCKET = os.environ.get("VERGE_OUTPUT_BUCKET", "")
OUTPUT_PREFIX = os.environ.get("VERGE_OUTPUT_PREFIX", "runs/transient").strip("/")
RUN_ROOT = Path(os.environ.get("VERGE_RUN_ROOT", tempfile.gettempdir())) / "verge-runs"

# How long a finished run's exports stay fetchable on this instance.
#
# Generous on purpose. A batched session runs several experiments against one warm machine
# and saves them at the end, so a short window would delete artifacts the user has already
# paid for but not yet fetched. Six hours outlives any session we have run while still
# bounding growth on an instance that --min-instances=1 keeps alive indefinitely.
#
# This bounds LOCAL disk only. Durable storage expiry is the bucket's lifecycle rule; see
# expires_after_days in contract.py.
RUN_TTL_SECONDS = int(os.environ.get("VERGE_RUN_TTL_SECONDS", 6 * 60 * 60))

app = FastAPI(title="Verge Studio DA3 service")

# The app is served from localhost during development; the service is identity-token
# protected, so permissive CORS here does not widen access.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

_model: Any = None
_model_load_seconds: float | None = None
_infer_lock = asyncio.Lock()
_busy = False
_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="da3")

#: Uploads that overlap the npz write: the preview and the full GLB exist the moment the forward
#: pass returns, and waiting for the npz before sending them was ~1.4 s (preview) plus the GLB's
#: upload added to every request (2026-10-23 timing log). Separate from `_executor`, which must
#: stay one-at-a-time for the GPU.
_publish_pool = ThreadPoolExecutor(max_workers=3, thread_name_prefix="publish")

#: Preview state by client token, newest last. Written from the executor thread, read by
#: GET /preview; each write replaces a whole dict, so a reader never sees half of one.
_previews: dict[str, dict[str, Any]] = {}
_PREVIEWS_KEPT = 32

#: The thinned cloud's file name; distinct from DA3's GLB so nothing confuses the two.
PREVIEW_NAME = "verge-preview.glb"


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _load_model() -> Any:
    """Blocking. ~6.7 GB checkpoint; this is the bulk of the ~3 min cold start."""
    global _model, _model_load_seconds
    if _model is not None:
        return _model
    from depth_anything_3.api import DepthAnything3

    started = time.monotonic()
    model = DepthAnything3.from_pretrained(MODEL_DIR).to("cuda")
    _model_load_seconds = time.monotonic() - started
    _model = model
    return _model


def _snapshot() -> GpuSnapshot:
    stats = vram.current_snapshot()
    return GpuSnapshot(
        available=vram.cuda_available(),
        model_loaded=_model is not None,
        busy=_busy,
        device_name=str(stats["device_name"]),
        current_bytes=int(stats["current_bytes"]),
        peak_bytes=int(stats["peak_bytes"]),
        total_bytes=int(stats["total_bytes"]) or GpuSnapshot.model_fields["total_bytes"].default,
    )


# NOT /healthz: Google's frontend swallows that path on Cloud Run and returns its own
# HTML 404 before the request ever reaches the container. Verified 2026-07-31 — /gpu,
# /docs and /openapi.json all served fine from the same revision while /healthz 404'd.
@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(
        status="ok",
        model_loaded=_model is not None,
        gpu_available=vram.cuda_available(),
    )


@app.get("/gpu", response_model=GpuSnapshot)
def gpu() -> GpuSnapshot:
    """Polled by the UI during inference for the live VRAM bar."""
    return _snapshot()


@app.post("/warmup", response_model=WarmupResponse)
async def warmup() -> WarmupResponse:
    if not vram.cuda_available():
        raise HTTPException(status_code=503, detail="no CUDA device")
    loop = asyncio.get_running_loop()
    await loop.run_in_executor(_executor, _load_model)
    return WarmupResponse(
        model_loaded=True,
        model_load_seconds=_model_load_seconds or 0.0,
        gpu=_snapshot(),
    )


def _run_inference(frame_paths: list[str], params: InferParams, export_dir: Path) -> dict[str, Any]:
    """Blocking; runs on the executor thread."""
    model = _load_model()

    # GLB only. DA3's own `npz` exporter (asked for here until 2026-10-23) writes
    # exports/npz/results.npz — depth, confidence AND every image, compressed — on a background
    # thread: 78.7 MB and 3.3 s of CPU for the 118-frame run 20261023-025037-aff2db, measured on
    # a Mac with DA3's own code. Nothing reads it; ours below is the result.
    export_formats = ["glb"]
    if params.infer_gs:
        export_formats += ["gs_ply"]

    started = time.monotonic()
    with vram.VramSampler() as sampler:
        prediction = model.inference(
            frame_paths,
            process_res=params.process_res,
            process_res_method=params.process_res_method,
            ref_view_strategy=params.ref_view_strategy,
            infer_gs=params.infer_gs,
            export_dir=str(export_dir),
            export_format="-".join(export_formats),
            # The GLB exporter otherwise renders a colourised depth JPEG for every frame, only to
            # copy the first to scene.jpg, which nothing publishes. Off, the same run's GLB export
            # went from 2.98 s to 1.01 s with a byte-identical scene.glb (same measurement as
            # above). A fresh dict each call: DA3 mutates the one it is given.
            export_kwargs={"glb": {"export_depth_vis": False}},
        )
    gpu_seconds = time.monotonic() - started
    stages: dict[str, float] = {"inference": gpu_seconds}

    # Everything that already exists leaves now, while the npz is written: the preview (the
    # earliest point cloud, and the reason it exists) and DA3's full GLB.
    run_id = export_dir.parent.name
    early: dict[str, Any] = {}
    if params.preview_token:
        early[PREVIEW_NAME] = _publish_pool.submit(
            _make_preview, params.preview_token, params.preview_points, export_dir, stages
        )
    for glb in sorted(export_dir.glob("*.glb")):
        if glb.name != PREVIEW_NAME:
            early[glb.name] = _publish_pool.submit(_timed_publish, glb, run_id, stages)

    import numpy as np

    # Written as verge-result.npz, NOT result.npz. DA3's own `npz` exporter writes into
    # this same export_dir, and if it picks the name result.npz ours silently overwrites
    # it -- taking DA3's embedded images and any keys we do not re-emit with it. A
    # distinct filename makes the two coexist whatever DA3 chooses to call its own.
    # The keys below are the ones app/src/lib/npz.ts reads; see native_npz in the
    # manifest for what DA3 emitted alongside.
    ours = export_dir / VERGE_NPZ_NAME
    npz_started = time.monotonic()
    _write_npz(
        ours,
        {
            "depth": prediction.depth,
            "confidence": prediction.conf,
            "extrinsics": prediction.extrinsics,
            "intrinsics": prediction.intrinsics,
        },
    )
    stages["npz_write"] = time.monotonic() - npz_started

    # Record what DA3 itself produced, so the npz key names stop being a guess. This is
    # cheap (a directory listing plus one npz header read) and answers the open question
    # without another cloud session.
    native_npz: dict[str, list[str]] = {}
    for path in sorted(export_dir.glob("*.npz")):
        if path.name == ours.name:
            continue
        try:
            with np.load(path, allow_pickle=False) as handle:
                native_npz[path.name] = list(handle.files)
        except Exception as exc:  # a listing must never fail the run
            native_npz[path.name] = [f"<unreadable: {exc}>"]

    depth = np.asarray(prediction.depth)
    return {
        "gpu_seconds": gpu_seconds,
        "peak_bytes": sampler.peak_bytes,
        "torch_peak_bytes": sampler.torch_peak_bytes,
        "baseline_bytes": sampler.baseline_bytes,
        "total_bytes": sampler.total_bytes,
        "height": int(depth.shape[-2]),
        "width": int(depth.shape[-1]),
        "native_npz": native_npz,
        "export_dir_listing": sorted(p.name for p in export_dir.iterdir() if p.is_file()),
        "early": early,
        "stages": stages,
    }


#: zlib level for our npz. numpy's savez_compressed uses the default (6): 4.01 s and 93.6 MB for
#: run 20261023-025037-aff2db on a Mac; level 1 wrote the same arrays in 1.92 s at 94.3 MB.
#: Depth and confidence are float32 noise to deflate, so harder effort buys almost nothing.
NPZ_COMPRESS_LEVEL = 1

#: Each npz member is deflated in pieces this size, all at once. Level 1 on one core still took
#: 3.25 s for 118 frames on the RTX PRO 6000 instance (20 vCPU) on 2026-10-23, the largest
#: stretch left after the forward pass. Pieces are independent deflate streams joined with sync
#: flushes, as pigz does: one valid stream, no back-reference across a seam, a few bytes per seam.
NPZ_PIECE_BYTES = 4 * 1024 * 1024

_deflate_pool = ThreadPoolExecutor(max_workers=max(2, os.cpu_count() or 2), thread_name_prefix="deflate")


def _deflate_piece(piece: memoryview, last: bool) -> bytes:
    import zlib

    compressor = zlib.compressobj(NPZ_COMPRESS_LEVEL, zlib.DEFLATED, -15)
    return compressor.compress(piece) + compressor.flush(zlib.Z_FINISH if last else zlib.Z_SYNC_FLUSH)


def _deflate_member(data: memoryview) -> tuple[bytes, int]:
    """Raw deflate of `data` in parallel pieces, and its CRC-32 (zlib releases the GIL for both)."""
    import zlib

    pieces = [data[i : i + NPZ_PIECE_BYTES] for i in range(0, len(data), NPZ_PIECE_BYTES)] or [data]
    crc = _deflate_pool.submit(zlib.crc32, data)
    compressed = _deflate_pool.map(_deflate_piece, pieces, [i == len(pieces) - 1 for i in range(len(pieces))])
    return b"".join(compressed), crc.result()


def _write_npz(path: Path, arrays: dict[str, Any]) -> None:
    """`np.savez_compressed`, faster: deflate level 1, each member compressed on every core.

    Same container and the same `.npy` members, so `np.load` and the studio's `npz.ts` read it
    unchanged. The zip is written by hand because `zipfile` cannot take data compressed
    elsewhere; members over 4 GiB, which would need zip64, go through `zipfile` instead.
    """
    import io
    import struct

    import numpy as np

    members: list[tuple[bytes, bytes, int, int]] = []
    for name, value in arrays.items():
        buffer = io.BytesIO()
        np.lib.format.write_array(buffer, np.asanyarray(value), allow_pickle=False)
        raw = buffer.getbuffer()
        if raw.nbytes >= 0xFFFFFFFF:
            return _write_npz_zipfile(path, arrays)
        compressed, crc = _deflate_member(raw)
        members.append((f"{name}.npy".encode(), compressed, crc, raw.nbytes))

    # 1980-01-01 00:00, the zip epoch: the timestamp carries no meaning here.
    dos_time, dos_date = 0, (0 << 9) | (1 << 5) | 1
    central = bytearray()
    with path.open("wb") as out:
        for name, compressed, crc, size in members:
            offset = out.tell()
            out.write(struct.pack("<IHHHHHIIIHH", 0x04034B50, 20, 0, 8, dos_time, dos_date, crc, len(compressed), size, len(name), 0))
            out.write(name)
            out.write(compressed)
            central += struct.pack(
                "<IHHHHHHIIIHHHHHII", 0x02014B50, 20, 20, 0, 8, dos_time, dos_date, crc, len(compressed), size, len(name), 0, 0, 0, 0, 0, offset
            )
            central += name
        start = out.tell()
        if start >= 0xFFFFFFFF:
            out.close()
            return _write_npz_zipfile(path, arrays)
        out.write(central)
        out.write(struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(members), len(members), len(central), start, 0))


def _write_npz_zipfile(path: Path, arrays: dict[str, Any]) -> None:
    """The single-threaded path, through `zipfile` (zip64 when needed)."""
    import numpy as np

    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=NPZ_COMPRESS_LEVEL) as archive:
        for name, value in arrays.items():
            with archive.open(f"{name}.npy", "w", force_zip64=True) as member:
                np.lib.format.write_array(member, np.asanyarray(value), allow_pickle=False)


def _timed_publish(path: Path, run_id: str, stages: dict[str, float]) -> tuple[tuple[str, str | None], list[str]]:
    """`_publish` off the request thread: its link, and its own notes to merge afterwards."""
    notes: list[str] = []
    started = time.monotonic()
    published = _publish(path, run_id, notes)
    stages[f"publish_{path.name}"] = time.monotonic() - started
    return published, notes


#: Our own npz, written by _run_inference. Deliberately not "result.npz" — see there.
VERGE_NPZ_NAME = "verge-result.npz"


def _register_preview(token: str, run_id: str) -> None:
    _previews.pop(token, None)
    _previews[token] = {"state": "pending", "run_id": run_id, "accepted": time.monotonic()}
    while len(_previews) > _PREVIEWS_KEPT:
        _previews.pop(next(iter(_previews)))


def _make_preview(token: str, max_points: int, export_dir: Path, stages: dict[str, float] | None = None) -> None:
    """Thin DA3's GLB and publish it. Never raises: the run it belongs to is already paid for."""
    entry = _previews.get(token)
    if entry is None:
        return
    run_id = entry["run_id"]
    started = time.monotonic()
    try:
        source = next(p for p in sorted(export_dir.glob("*.glb")) if p.name != PREVIEW_NAME)
        stats = preview.thin_glb(source, export_dir / PREVIEW_NAME, max_points)
        built = time.monotonic()
        notes: list[str] = []
        url, gs_uri = _publish(export_dir / PREVIEW_NAME, run_id, notes)
        published = time.monotonic()
        _previews[token] = {
            **entry,
            "state": "ready",
            "url": url,
            "gs_uri": gs_uri,
            "points": stats["points"],
            "source_points": stats["source_points"],
            "size_bytes": stats["size_bytes"],
            "ready_after_seconds": published - entry["accepted"],
            "build_seconds": built - started,
            "publish_seconds": published - built,
            "error": "; ".join(notes) or None,
        }
    except Exception as exc:  # noqa: BLE001 — a preview must never cost the run
        _previews[token] = {**entry, "state": "failed", "error": f"{type(exc).__name__}: {exc}"}
    finally:
        if stages is not None:
            stages["preview"] = time.monotonic() - started


def _published_preview(run_id: str) -> tuple[str, str | None] | None:
    for entry in _previews.values():
        if entry.get("run_id") == run_id and entry.get("state") == "ready":
            return entry["url"], entry.get("gs_uri")
    return None


@app.get("/preview/{token}", response_model=PreviewStatus)
def preview_status(token: str) -> PreviewStatus:
    """Polled by the studio while /infer is open. Reads memory only; wakes nothing extra."""
    entry = _previews.get(token)
    if entry is None:
        raise HTTPException(status_code=404, detail="no such preview")
    return PreviewStatus(**{k: v for k, v in entry.items() if k in PreviewStatus.model_fields})


def _collect_artifacts(
    export_dir: Path,
    run_id: str,
    notes: list[str],
    early: dict[str, Any] | None = None,
    stages: dict[str, float] | None = None,
) -> list[Artifact]:
    """Every artifact with its link. `early` holds uploads `_run_inference` started already:
    each is awaited here rather than published twice."""
    early = early or {}
    for name, pending in early.items():
        if name == PREVIEW_NAME:
            pending.result()  # the preview records its own link in _previews
    
    kinds = {
        ".glb": "glb",
        ".npz": "npz",
        ".png": "depth_preview",
        ".ply": "gs_ply",
        ".mp4": "gs_video",
    }
    artifacts: list[Artifact] = []
    for path in sorted(export_dir.iterdir()):
        if not path.is_file():
            continue
        kind = kinds.get(path.suffix.lower())
        if kind is None:
            continue
        # Exactly one artifact may carry kind "npz": the client looks it up by kind and
        # expects OUR key names (depth/confidence/extrinsics/intrinsics). DA3's own npz
        # is kept and served, but under a distinct kind, so an alphabetically earlier
        # filename can never shadow ours in the client's `find`.
        if kind == "npz" and path.name != VERGE_NPZ_NAME:
            kind = "npz_native"
        # The preview went out early; publishing it again would collide with itself in the
        # bucket (if_generation_match=0) and read as a degraded run.
        if path.name == PREVIEW_NAME:
            kind = "cloud_preview"
            published = _published_preview(run_id)
        elif path.name in early:
            published, publish_notes = early[path.name].result()
            notes.extend(publish_notes)
        else:
            published = None
        if published is None:
            started = time.monotonic()
            published = _publish(path, run_id, notes)
            if stages is not None:
                stages[f"publish_{path.name}"] = time.monotonic() - started
        url, gs_uri = published
        artifacts.append(
            Artifact(
                kind=kind,  # type: ignore[arg-type]
                name=path.name,
                size_bytes=path.stat().st_size,
                sha256=_sha256(path),
                url=url,
                gs_uri=gs_uri,
            )
        )
    return artifacts


# How long a signed link stays usable. Comfortably longer than a working session and far
# shorter than the 7-day maximum V4 allows. It does not need to cover archival: Save reads
# `gs_uri` under the operator's own credentials, so a link expiring never strands a run.
SIGNED_URL_TTL_SECONDS = int(os.environ.get("VERGE_SIGNED_URL_TTL_SECONDS", 12 * 60 * 60))

_storage_client: Any = None
_signing_credentials: Any = None


_thread_clients = threading.local()
_sign_lock = threading.Lock()


def _client() -> Any:
    """One GCS client per thread. Uploads now run on `_publish_pool` beside the request thread,
    and a client is not documented as safe to share across threads; building one per thread
    re-reads ADC once per worker, not once per artifact."""
    if _storage_client is not None:  # injected: one shared client, as the tests use
        return _storage_client
    client = getattr(_thread_clients, "client", None)
    if client is None:
        from google.cloud import storage

        client = storage.Client()
        _thread_clients.client = client
    return client


def _sign(blob: Any) -> str:
    """A V4 signed GET link for `blob`, signed through the IAM API.

    There is no private key inside Cloud Run — the runtime identity is a metadata-server
    token, and `generate_signed_url` cannot sign with that on its own. Passing
    `service_account_email` plus `access_token` routes the signature through the IAM
    `signBlob` API instead, which is why the runtime account needs
    `roles/iam.serviceAccountTokenCreator` ON ITSELF (granted by scripts/create-bucket.sh).

    A signed URL carries the SIGNER's authority, so the same account also needs read access
    to the object it signs for — objectAdmin on the bucket, not merely objectCreator.
    """
    global _signing_credentials
    # Several uploads can finish at once; one refresh at a time, and nobody signs mid-refresh.
    with _sign_lock:
        if _signing_credentials is None:
            import google.auth

            _signing_credentials, _ = google.auth.default()
        # The access token IS the signing credential here, so a stale one produces a bad
        # signature rather than a clean 401. Refresh whenever it is not currently valid.
        if not getattr(_signing_credentials, "valid", False):
            from google.auth.transport import requests as google_requests

            _signing_credentials.refresh(google_requests.Request())
        email = _signing_credentials.service_account_email
        token = _signing_credentials.token

    return blob.generate_signed_url(
        version="v4",
        expiration=timedelta(seconds=SIGNED_URL_TTL_SECONDS),
        method="GET",
        service_account_email=email,
        access_token=token,
    )


def _publish(path: Path, run_id: str, notes: list[str]) -> tuple[str, str | None]:
    """Upload to the transient GCS prefix and return (url_for_client, durable_gs_uri).

    Returns a LOCAL /artifact path and no gs_uri in two cases: no bucket is configured, or
    publishing failed. The second one never raises, and that is deliberate — this runs after
    `model.inference` has returned, so the GPU time is already spent and money is already
    gone. Turning a storage permission slip into a 500 would throw away a paid run to report
    a problem that costs nothing to work around: the files are still on local disk and
    /artifact still serves them.

    Failing quietly is the other trap, so every failure is recorded in `notes` and surfaces
    as diagnostics.publish_mode == "degraded" on the manifest.
    """
    local = f"/artifact/{run_id}/{path.name}"
    if not OUTPUT_BUCKET:
        return local, None

    object_name = f"{OUTPUT_PREFIX}/{run_id}/{path.name}"
    try:
        blob = _client().bucket(OUTPUT_BUCKET).blob(object_name)
        # if_generation_match=0 makes this fail rather than overwrite. Run ids carry a
        # timestamp and six random hex, so a collision means something is wrong.
        blob.upload_from_filename(str(path), if_generation_match=0)
        return _sign(blob), f"gs://{OUTPUT_BUCKET}/{object_name}"
    except Exception as exc:  # noqa: BLE001 — a paid run must survive any storage failure
        notes.append(f"{path.name}: {type(exc).__name__}: {exc}")
        return local, None


def _publish_mode(notes: list[str]) -> str:
    """Where this run's artifacts ended up. Separate from _publish so it can be tested
    without a GPU: /infer is unreachable on a CPU box, so an inline expression there would
    never be exercised by anything until a real cloud run."""
    if not OUTPUT_BUCKET:
        return "local"
    return "degraded" if notes else "gcs"


def _discard_frames(run_dir: Path) -> int:
    """Drop a run's uploaded frames. Returns the bytes released.

    Frames are input. Once `model.inference` has returned, nothing reads them again --
    `_collect_artifacts` and `/artifact` both work exclusively out of `exports/`. Keeping
    them was pure residue: measured on the door clip, 4.7 MB of the 126 MB a 112-frame run
    left behind, held for the life of the instance for no reader.
    """
    frame_dir = run_dir / "frames"
    if not frame_dir.is_dir():
        return 0
    released = sum(p.stat().st_size for p in frame_dir.rglob("*") if p.is_file())
    shutil.rmtree(frame_dir, ignore_errors=True)
    return released


def _sweep_expired_runs(ttl_seconds: int = RUN_TTL_SECONDS, now: float | None = None) -> list[str]:
    """Delete run directories that have outlived the retention window.

    Called at the top of every /infer, which is the only moment new disk gets claimed and
    therefore the only moment the sweep is worth its syscalls. A symlinked entry is skipped
    rather than followed -- RUN_ROOT lives under the OS temp directory, and rmtree through
    a link would delete somebody else's tree.
    """
    if not RUN_ROOT.is_dir():
        return []
    cutoff = (now if now is not None else time.time()) - ttl_seconds
    removed: list[str] = []
    for path in sorted(RUN_ROOT.iterdir()):
        if path.is_symlink() or not path.is_dir():
            continue
        try:
            if path.stat().st_mtime >= cutoff:
                continue
        except OSError:  # vanished under us, or unreadable; either way not ours to chase
            continue
        shutil.rmtree(path, ignore_errors=True)
        if not path.exists():
            removed.append(path.name)
    return removed


@app.post("/infer", response_model=InferManifest)
async def infer(
    frames: list[UploadFile] = File(...),
    params: str = Form("{}"),
) -> InferManifest:
    global _busy

    try:
        parsed = InferParams(**json.loads(params))
    except (json.JSONDecodeError, ValueError) as exc:
        raise HTTPException(status_code=422, detail=f"bad params: {exc}") from exc

    if len(frames) < 2:
        raise HTTPException(
            status_code=422,
            detail="DA3 needs multiple views — single images skip cross-view attention entirely",
        )
    if len(frames) > parsed.max_frames:
        raise HTTPException(
            status_code=422,
            detail=f"{len(frames)} frames exceeds max_frames={parsed.max_frames}; "
            "lower the sampling fps rather than truncating the clip",
        )
    if not vram.cuda_available():
        raise HTTPException(status_code=503, detail="no CUDA device")

    # Before claiming new disk, release any that has expired.
    _sweep_expired_runs()

    run_id = f"{time.strftime('%Y%m%d-%H%M%S')}-{uuid.uuid4().hex[:6]}"
    run_dir = RUN_ROOT / run_id
    frame_dir = run_dir / "frames"
    export_dir = run_dir / "exports"
    frame_dir.mkdir(parents=True)
    export_dir.mkdir(parents=True)
    if parsed.preview_token:
        _register_preview(parsed.preview_token, run_id)

    frame_paths: list[str] = []
    for index, upload in enumerate(frames):
        target = frame_dir / f"frame-{index:04d}.jpg"
        with target.open("wb") as handle:
            shutil.copyfileobj(upload.file, handle)
        frame_paths.append(str(target))

    wall_started = time.monotonic()
    loop = asyncio.get_running_loop()
    async with _infer_lock:
        _busy = True
        try:
            result = await loop.run_in_executor(
                _executor, _run_inference, frame_paths, parsed, export_dir
            )
        except Exception as exc:  # surface the real reason, OOM included
            if parsed.preview_token and _previews.get(parsed.preview_token, {}).get("state") == "pending":
                _previews[parsed.preview_token] = {
                    **_previews[parsed.preview_token],
                    "state": "failed",
                    "error": "inference failed",
                }
            shutil.rmtree(run_dir, ignore_errors=True)
            raise HTTPException(status_code=500, detail=f"inference failed: {exc}") from exc
        finally:
            _busy = False

    # The GPU is done with them, so they stop costing us anything to keep.
    _discard_frames(run_dir)

    publish_notes: list[str] = []
    stages: dict[str, float] = result["stages"]
    collect_started = time.monotonic()
    artifacts = _collect_artifacts(export_dir, run_id, publish_notes, result["early"], stages)
    stages["collect"] = time.monotonic() - collect_started

    manifest = InferManifest(
        run_id=run_id,
        params=parsed,
        frames=FrameInfo(
            count=len(frame_paths),
            requested_count=len(frame_paths),
            width=result["width"],
            height=result["height"],
            capped=False,
            effective_fps=parsed.fps,
        ),
        timing=Timing(
            gpu_seconds=result["gpu_seconds"],
            wall_seconds=time.monotonic() - wall_started,
            model_load_seconds=_model_load_seconds,
        ),
        vram=VramStats(
            peak_bytes=result["peak_bytes"],
            current_bytes=int(vram.current_snapshot()["current_bytes"]),
            total_bytes=result["total_bytes"],
            device_name=vram.device_name(),
            torch_peak_bytes=result["torch_peak_bytes"],
            baseline_bytes=result["baseline_bytes"],
        ),
        artifacts=artifacts,
        diagnostics=Diagnostics(
            native_npz=result["native_npz"],
            export_dir_listing=result["export_dir_listing"],
            publish_mode=_publish_mode(publish_notes),
            publish_errors=publish_notes,
            stage_seconds={k: round(v, 3) for k, v in stages.items()},
        ),
    )
    (run_dir / "manifest.json").write_text(manifest.model_dump_json(indent=2))
    return manifest


@app.get("/publish-check")
def publish_check() -> dict[str, Any]:
    """Prove the whole durable-storage chain without touching the GPU.

    Writes ~1 KB, signs it, and hands back the link. That exercises every part that can fail
    at run time and none of the parts that cost money: bucket reachability, objectAdmin on
    the bucket, Token Creator on the runtime account, the signing round trip through IAM, and
    — once the browser fetches the returned URL — the bucket's CORS policy.

    It exists because the alternative is discovering a missing IAM binding *after* a 40-second
    GPU run, and this project has twice paid for code that was written but never executed.
    Cost of being wrong here: one HTTP request.

    The probe object is written UNDER the transient prefix on purpose, so the same lifecycle
    rule that reaps run artifacts reaps these too. A checker that leaks objects forever would
    be its own small version of the problem it is checking for.
    """
    if not OUTPUT_BUCKET:
        raise HTTPException(
            status_code=503,
            detail="VERGE_OUTPUT_BUCKET is not set; this revision publishes to local disk only",
        )

    object_name = f"{OUTPUT_PREFIX}/_publish-check/{time.strftime('%Y%m%d-%H%M%S')}-{uuid.uuid4().hex[:6]}.txt"
    payload = f"verge publish-check {time.time():.0f}\n".encode() + b"." * 1024

    blob = _client().bucket(OUTPUT_BUCKET).blob(object_name)
    blob.upload_from_string(payload, content_type="text/plain", if_generation_match=0)
    url = _sign(blob)

    return {
        "bucket": OUTPUT_BUCKET,
        "prefix": OUTPUT_PREFIX,
        "object": object_name,
        "gs_uri": f"gs://{OUTPUT_BUCKET}/{object_name}",
        "signed_url": url,
        "signed_ttl_seconds": SIGNED_URL_TTL_SECONDS,
        "signer": getattr(_signing_credentials, "service_account_email", None),
        "bytes": len(payload),
    }


@app.get("/artifact/{run_id}/{name}")
def artifact(run_id: str, name: str) -> FileResponse:
    # Defend against path traversal in both segments.
    if "/" in run_id or "/" in name or ".." in run_id or ".." in name:
        raise HTTPException(status_code=400, detail="bad path")
    path = RUN_ROOT / run_id / "exports" / name
    if not path.is_file():
        raise HTTPException(status_code=404, detail="no such artifact")
    return FileResponse(path)


@app.post("/shutdown")
async def shutdown() -> dict[str, str]:
    """Release the model so the instance can scale down without waiting out the
    idle timeout. GPU time is billed for the instance's whole lifetime, so this
    button is a real cost lever, not a nicety."""
    global _model, _model_load_seconds
    _model = None
    _model_load_seconds = None
    if vram.cuda_available():
        import torch

        torch.cuda.empty_cache()
    return {"status": "released"}
