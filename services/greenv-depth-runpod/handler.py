"""The depth stage as a RunPod serverless handler.

GreenV reaches depth reconstruction through one of two dialects. `measurement/server/` answers
`POST /infer` with the JPEGs uploaded as multipart, one request one answer, and that is what
Cloud Run runs today. RunPod serverless is a job queue instead: `POST /run` returns an id and the
answer is collected from `GET /status/<id>`, and its JSON body has a payload ceiling far below a
hundred JPEGs. So frames travel by object key, and this handler is the half that reads them from
the bucket, runs the model, puts the reconstruction back and hands out signed links.

    input   { frames: [{ name, key }], storage: { bucket, endpoint, region },
              params: { fps, process_res, max_frames, source_duration_s } }
    output  the DA3 manifest, with `artifacts` rewritten to absolute signed bucket URLs

`services/greenv-measurement-worker/src/infer-runpod.mjs` is the caller, and
`contract/depth-job-v1.example.json` is one job in and one manifest out, read by the tests on
both sides so the two cannot drift.

Two things about this file are deliberate and worth keeping.

**It does not import Verge Studio.** The DA3 service is spawned as a process and spoken to over
its own HTTP contract, exactly as `greenv-measurement-worker` spawns `assess-grass.mjs` and reads
its JSON. Nothing outside `measurement/` may import from inside it, and that boundary is what
keeps the subtree round-trippable to `github.com/henrikmm/verge-studio` (root `AGENTS.md`). The
image carries `measurement/server/` because it is built FROM the image that Dockerfile produces,
so the two can never disagree about which model revision is installed.

**It refuses a job it cannot hold before spending anything.** A 112-frame run at 504 px peaked at
21.28 GiB against an L4's 22.03 GiB usable, and 160 frames at the same resolution ran out of
memory outright (`measurement/docs/vram-measurements.json`, 2026-08-01). Past the ceiling the
process is killed rather than answered, so the caller would pay a cold start and a GPU minute to
learn nothing. The ceiling below is measured, it assumes an L4, and an endpoint on any other GPU
has to say what it can hold.
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path

#: The envelope this handler and `infer-runpod.mjs` agree on. Bump it when the shape changes.
CONTRACT = "greenv.depth-job/1"

#: What each artifact is called in the bucket, whatever the exporter called it on disk.
#:
#: DA3's own npz exporter and Verge Studio's both write into one directory, and Verge Studio
#: renames its own to `verge-result.npz` precisely so DA3's cannot overwrite it (see
#: `_run_inference` in `measurement/server/main.py`). The worker wants OUR arrays under
#: `result.npz`, which is the name `run-directory.mjs` writes, so the mapping below is by KIND
#: and never by file name -- an artifact called `result.npz` arriving under kind `npz_native` is
#: DA3's own and must not be the one published.
ARTIFACT_NAMES = {"glb": "scene.glb", "npz": "result.npz"}

#: MEASURED on the deployed L4 (2026-07-31): 22.03 GiB usable, not the advertised decimal 24 GB.
#: Mirrors L4_TOTAL_VRAM_BYTES in `measurement/server/contract.py`.
L4_USABLE_BYTES = 23_659_151_360

#: Frames that fit on an L4 at a given process resolution, from the 2026-08-01 sweeps in
#: `measurement/docs/vram-measurements.json`. Each entry is the largest count that ran with real
#: headroom, not the largest that happened to complete:
#:
#:   504 px  112 -> 21.28 GiB. 128 -> 21.94 and 144 -> 21.88 both sit at ~99% of the device,
#:                             which is a coin flip rather than an operating point; 160 OOMed.
#:   356 px  192 -> 19.39 GiB. 256 -> 21.54 is again ~98%.
#:   252 px  256 -> 15.89 GiB. Nothing larger was ever tried, so this one is a floor on the truth.
#:
#: A higher resolution costs more memory per frame, so a ceiling measured at a resolution at
#: least as large as the one asked for is a safe bound for it. There is no evidence above 504 px
#: and this table refuses rather than extrapolating.
L4_FRAME_CEILING = {252: 256, 356: 192, 504: 112}

#: How long a published link stays usable. Matches VERGE_SIGNED_URL_TTL_SECONDS in
#: `measurement/server/main.py`: comfortably longer than a measurement run, far shorter than the
#: seven days S3 allows. The worker fetches within seconds; the margin is for a retried job.
SIGNED_URL_TTL_SECONDS = int(os.environ.get("GREENV_SIGNED_URL_TTL_SECONDS", 12 * 60 * 60))

#: Where artifacts land when the frame keys do not name a segment.
DEFAULT_OUTPUT_PREFIX = os.environ.get("GREENV_DEPTH_OUTPUT_PREFIX", "depth-runs")


class JobRejected(Exception):
    """A job that will not be run, and the reason, before any GPU time is spent."""


@dataclass(frozen=True)
class Frame:
    name: str
    key: str


@dataclass(frozen=True)
class JobRequest:
    frames: tuple[Frame, ...]
    bucket: str
    endpoint: str | None
    region: str | None
    prefix: str | None
    fps: float
    process_res: int
    max_frames: int
    source_duration_s: float | None


def _text(value: object, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise JobRejected(f"{field} must be a non-empty string, got {json.dumps(value)}")
    return value.strip()


def _number(value: object, field: str, *, low: float, high: float, integer: bool) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise JobRejected(f"{field} must be a number, got {json.dumps(value)}")
    if integer and int(value) != value:
        raise JobRejected(f"{field} must be a whole number, got {json.dumps(value)}")
    if not low <= value <= high:
        raise JobRejected(f"{field} must be between {low} and {high}, got {json.dumps(value)}")
    return int(value) if integer else float(value)


def parse_job_input(payload: object) -> JobRequest:
    """Read one `input` object, or say exactly what is wrong with it.

    The bounds mirror `InferParams` in `measurement/server/contract.py`, which stays the
    authority -- the service revalidates every one of them. Repeating them here buys the caller a
    refusal in milliseconds instead of one after a cold start.
    """
    if not isinstance(payload, dict):
        raise JobRejected(f"job input must be an object, got {type(payload).__name__}")

    raw_frames = payload.get("frames")
    if not isinstance(raw_frames, list):
        raise JobRejected("job input carries no `frames` array")
    frames: list[Frame] = []
    for index, entry in enumerate(raw_frames):
        if not isinstance(entry, dict):
            raise JobRejected(f"frames[{index}] must be an object, got {json.dumps(entry)}")
        name = _text(entry.get("name"), f"frames[{index}].name")
        key = _text(entry.get("key"), f"frames[{index}].key")
        if "/" in name or "\\" in name or name.startswith("."):
            # The name becomes a file in a scratch directory, and the number Verge Studio parses
            # out of it is the segment's canonical frame number. Neither survives a path.
            raise JobRejected(f"frames[{index}].name must be a bare file name, got {json.dumps(name)}")
        frames.append(Frame(name=name, key=key))
    if len(frames) < 2:
        # Not a transport limit. Depth Anything recovers geometry by comparing views, so one
        # frame is not a small answer, it is a wrong one.
        raise JobRejected(f"depth inference needs at least 2 frames, got {len(frames)}")

    storage = payload.get("storage")
    if not isinstance(storage, dict):
        raise JobRejected("job input carries no `storage` object")
    bucket = _text(storage.get("bucket"), "storage.bucket")
    endpoint = storage.get("endpoint") or None
    region = storage.get("region") or None
    prefix = storage.get("prefix") or None

    params = payload.get("params") or {}
    if not isinstance(params, dict):
        raise JobRejected("`params` must be an object when present")
    duration = params.get("source_duration_s")

    return JobRequest(
        frames=tuple(frames),
        bucket=bucket,
        endpoint=str(endpoint) if endpoint else None,
        region=str(region) if region else None,
        prefix=str(prefix).strip("/") if prefix else None,
        fps=_number(params.get("fps", 10.0), "params.fps", low=0.001, high=60, integer=False),
        process_res=int(_number(
            params.get("process_res", 504), "params.process_res", low=126, high=1024, integer=True)),
        max_frames=int(_number(
            params.get("max_frames", 112), "params.max_frames", low=2, high=512, integer=True)),
        source_duration_s=None if duration is None else _number(
            duration, "params.source_duration_s", low=0.001, high=86400, integer=False),
    )


def frame_ceiling(process_res: int, *, device_total_bytes: int, configured: int | None = None) -> int:
    """How many frames this endpoint's GPU is known to hold at `process_res`.

    `configured` is `GREENV_DEPTH_MAX_FRAMES` and it wins outright: an operator who has measured
    their own device owns that claim. Without it the table above applies, and it applies only to a
    device at least as large as the L4 it was measured on. Anything smaller gets a refusal naming
    the variable, because guessing downwards from someone else's device is how a job gets killed
    by the driver with nothing to report.
    """
    if configured is not None:
        return configured
    if device_total_bytes < L4_USABLE_BYTES:
        raise JobRejected(
            f"this endpoint reports {device_total_bytes / 2**30:.2f} GiB of VRAM and the frame "
            f"ceilings in this handler were measured on an L4 with {L4_USABLE_BYTES / 2**30:.2f} "
            "GiB (measurement/docs/vram-measurements.json). Set GREENV_DEPTH_MAX_FRAMES to what "
            "this device has been measured to hold, or run the endpoint on an L4 or larger."
        )
    for measured_res in sorted(L4_FRAME_CEILING):
        if measured_res >= process_res:
            return L4_FRAME_CEILING[measured_res]
    raise JobRejected(
        f"no VRAM measurement exists above {max(L4_FRAME_CEILING)} px and this job asks for "
        f"process_res={process_res}; set GREENV_DEPTH_MAX_FRAMES to accept it deliberately."
    )


def output_prefix(request: JobRequest) -> str:
    """Where this run's artifacts go: beside the frames they were computed from.

    The frames of one segment share one directory -- `<outputPrefix>/sampled-frames/` in
    `greenv-measurement-worker/src/keys.mjs` -- so the segment prefix is that directory's parent,
    and artifacts land under `<segment>/depth/<run_id>/`. Frames from two different directories
    are a wiring mistake rather than a layout to accommodate, so they are refused.
    """
    if request.prefix:
        return request.prefix
    parents = {key.rsplit("/", 1)[0] if "/" in key else "" for key in (frame.key for frame in request.frames)}
    if len(parents) != 1:
        raise JobRejected(f"frames come from {len(parents)} different directories; one job is one segment")
    parent = parents.pop()
    if not parent:
        return DEFAULT_OUTPUT_PREFIX
    if parent.endswith("/sampled-frames"):
        parent = parent[: -len("/sampled-frames")]
    return parent


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def run_job(payload: object, *, depth, open_store) -> dict:
    """One job: keys in, a manifest of signed links out.

    `depth` and `open_store` are injected so every step except the forward pass itself can be
    exercised without a GPU -- see `test_handler.py`. The order is the cheapest refusal first,
    because the step after it is the one that costs money.
    """
    request = parse_job_input(payload)

    device = depth.device()
    if not device.get("available"):
        raise JobRejected(
            "this endpoint reports no CUDA device; the depth model cannot run on it and a job "
            "would only spend a cold start to find that out"
        )
    configured = os.environ.get("GREENV_DEPTH_MAX_FRAMES")
    ceiling = frame_ceiling(
        request.process_res,
        device_total_bytes=int(device.get("total_bytes") or 0),
        configured=int(configured) if configured else None,
    )
    if len(request.frames) > ceiling:
        raise JobRejected(
            f"{len(request.frames)} frames at {request.process_res} px exceeds the {ceiling} this "
            f"endpoint's {device.get('device_name') or 'GPU'} is measured to hold. Past that the "
            "process is killed rather than answered, so lower the sampling rate "
            "(GREENV_INFER_MAX_FRAMES on the worker) instead of paying to discover it."
        )

    # The service's own 422 is the second fence, and it can only fire if it is told the truth
    # about this device rather than the caller's assumption about a different one.
    effective_max_frames = min(request.max_frames, ceiling)

    store = open_store(request)
    workdir = Path(tempfile.mkdtemp(prefix="greenv-depth-"))
    try:
        paths = []
        for frame in request.frames:
            target = workdir / frame.name
            target.write_bytes(store.get(frame.key))
            paths.append(str(target))

        params = {
            "fps": request.fps,
            "process_res": request.process_res,
            "max_frames": effective_max_frames,
        }
        if request.source_duration_s:
            params["source_duration_s"] = request.source_duration_s
        manifest = depth.infer(paths, params)
    finally:
        # Frames are input. Once inference has returned nothing reads them again, and a warm
        # RunPod worker keeps its filesystem between jobs.
        shutil.rmtree(workdir, ignore_errors=True)

    run_id = _text(manifest.get("run_id"), "the depth service's run_id")
    if "/" in run_id or ".." in run_id:
        raise JobRejected(f"the depth service returned an unusable run_id: {json.dumps(run_id)}")
    described = (manifest.get("frames") or {}).get("count")
    if not isinstance(described, int) or not 2 <= described <= len(request.frames):
        raise JobRejected(
            f"the depth service describes {described} frames of the {len(request.frames)} sent; "
            "a run whose geometry and images disagree measures the wrong scene"
        )

    published: list[dict] = []
    skipped: list[str] = []
    prefix = f"{output_prefix(request)}/depth/{run_id}"
    for artifact in manifest.get("artifacts") or []:
        kind = artifact.get("kind")
        if kind not in ARTIFACT_NAMES:
            # Everything DA3 exports beside the two the worker reads -- its own npz, a depth
            # preview -- stays on the instance rather than being paid for in the bucket. Naming
            # it here is what keeps that a decision instead of a silent loss.
            skipped.append(f"{artifact.get('name')} ({kind})")
            continue
        data = depth.artifact(run_id, artifact["name"])
        digest = _sha256(data)
        if artifact.get("sha256") and digest != artifact["sha256"]:
            raise JobRejected(
                f"{artifact['name']} arrived from the depth service with digest {digest}, not the "
                f"{artifact['sha256']} it reported; the bytes are not the ones it measured"
            )
        key = f"{prefix}/{ARTIFACT_NAMES[kind]}"
        store.put(key, data)
        published.append({
            "kind": kind,
            "name": ARTIFACT_NAMES[kind],
            "size_bytes": len(data),
            "sha256": digest,
            # Absolute, always. A serverless handler has no origin of its own to serve files
            # from, and `infer-runpod.mjs` rejects a relative URL rather than resolving it
            # against RunPod's API host.
            "url": store.signed_url(key),
            "object_key": key,
        })

    missing = sorted(set(ARTIFACT_NAMES) - {artifact["kind"] for artifact in published})
    if missing:
        raise JobRejected(f"the depth service published no {' and no '.join(missing)} artifact")

    return {
        **manifest,
        "artifacts": published,
        "published": {
            "handler_contract": CONTRACT,
            "bucket": request.bucket,
            "prefix": prefix,
            "signed_url_ttl_seconds": SIGNED_URL_TTL_SECONDS,
            "frame_ceiling": ceiling,
            "skipped": skipped,
        },
    }


class DepthService:
    """The DA3 service in `measurement/server/`, run as a process and spoken to over HTTP.

    A process rather than an import, for the reason in this module's docstring: nothing outside
    `measurement/` may import from inside it. Loopback HTTP costs one extra copy of ~130 MB of
    artifacts per run, which is well under a second beside the 22 to 39 GPU-seconds a segment
    takes.

    Started once per container and kept warm, so a second job on the same RunPod worker skips the
    model load entirely -- which is the whole reason RunPod's billing of a worker's lifetime is
    survivable.
    """

    def __init__(self, *, port: int | None = None, ready_timeout_s: float = 300.0,
                 request_timeout_s: float = 900.0):
        self.port = port or int(os.environ.get("GREENV_DEPTH_SERVICE_PORT", 8080))
        self.base = f"http://127.0.0.1:{self.port}"
        self.ready_timeout_s = ready_timeout_s
        self.request_timeout_s = request_timeout_s
        self._process: subprocess.Popen | None = None
        self._client = None

    def _http(self):
        if self._client is None:
            import httpx  # lazy: the tests never reach this adapter and must not need it

            self._client = httpx.Client(timeout=self.request_timeout_s)
        return self._client

    def start(self) -> None:
        if self._process is not None and self._process.poll() is None:
            return
        environment = dict(os.environ)
        # Verge Studio publishes to GCS when this is set. Here the handler publishes to the
        # S3-compatible bucket itself, so the service keeps artifacts on local disk and serves
        # them from /artifact, which is what `artifact()` below reads.
        environment.pop("VERGE_OUTPUT_BUCKET", None)
        self._process = subprocess.Popen(
            ["uvicorn", "main:app", "--host", "127.0.0.1", "--port", str(self.port), "--workers", "1"],
            cwd=os.environ.get("GREENV_DEPTH_SERVICE_DIR", "/app"),
            env=environment,
        )
        deadline = time.monotonic() + self.ready_timeout_s
        while time.monotonic() < deadline:
            if self._process.poll() is not None:
                raise RuntimeError(
                    f"the depth service exited with code {self._process.returncode} before answering /health"
                )
            try:
                if self._http().get(f"{self.base}/health").status_code == 200:
                    return
            except Exception:  # noqa: BLE001 - "not up yet" is the expected case in this loop
                pass
            time.sleep(1.0)
        raise RuntimeError(f"the depth service did not answer /health within {self.ready_timeout_s:.0f} s")

    def device(self) -> dict:
        self.start()
        response = self._http().get(f"{self.base}/gpu")
        response.raise_for_status()
        return response.json()

    def infer(self, paths: list[str], params: dict) -> dict:
        self.start()
        handles = [open(path, "rb") for path in paths]
        try:
            files = [("frames", (Path(path).name, handle, "image/jpeg"))
                     for path, handle in zip(paths, handles)]
            response = self._http().post(
                f"{self.base}/infer", files=files, data={"params": json.dumps(params)}
            )
        finally:
            for handle in handles:
                handle.close()
        if response.status_code != 200:
            # An out-of-memory failure arrives here as a 500 naming the allocator. That is the
            # second fence; the first is the ceiling check, which runs before any of this.
            raise RuntimeError(f"POST /infer failed with {response.status_code}: {response.text[:400]}")
        return response.json()

    def artifact(self, run_id: str, name: str) -> bytes:
        response = self._http().get(f"{self.base}/artifact/{run_id}/{name}")
        response.raise_for_status()
        return response.content


class BucketStore:
    """One S3-compatible bucket, addressed the way `greenv-measurement-worker` addresses it.

    Credentials come from the ordinary AWS chain -- AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY as
    RunPod secrets -- so nothing about them is GreenV-specific and none of them is in this file.
    """

    def __init__(self, *, bucket: str, endpoint: str | None, region: str | None,
                 ttl_seconds: int = SIGNED_URL_TTL_SECONDS):
        self.bucket = bucket
        self.ttl_seconds = ttl_seconds
        import boto3  # lazy, for the same reason as httpx above
        from botocore.config import Config

        self._s3 = boto3.client(
            "s3",
            endpoint_url=endpoint or None,
            region_name=region or "auto",
            # R2 and MinIO both want path-style addressing, and presigning needs SigV4.
            config=Config(signature_version="s3v4", s3={"addressing_style": "path"}),
        )

    def get(self, key: str) -> bytes:
        return self._s3.get_object(Bucket=self.bucket, Key=key)["Body"].read()

    def put(self, key: str, data: bytes) -> None:
        self._s3.put_object(Bucket=self.bucket, Key=key, Body=data)

    def signed_url(self, key: str) -> str:
        return self._s3.generate_presigned_url(
            "get_object",
            Params={"Bucket": self.bucket, "Key": key},
            ExpiresIn=self.ttl_seconds,
        )


_service = DepthService()


def handler(job: dict) -> dict:
    """RunPod's entry point: `{"id": ..., "input": {...}}` in, the job's `output` out.

    A rejected job answers with `{"error": ...}`, which RunPod reports as FAILED and the worker
    reads back as `RunPod job <id> ended FAILED: <message>`. Anything unexpected is left to
    propagate, so a real fault keeps its traceback instead of being flattened into a sentence.
    """
    try:
        return run_job(
            (job or {}).get("input"),
            depth=_service,
            open_store=lambda request: BucketStore(
                bucket=request.bucket, endpoint=request.endpoint, region=request.region
            ),
        )
    except JobRejected as rejected:
        return {"error": str(rejected)}


if __name__ == "__main__":
    import runpod

    runpod.serverless.start({"handler": handler})
