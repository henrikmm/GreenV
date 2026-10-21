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
import math
import os
import shutil
import subprocess
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor
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

#: Frames read from the bucket at once. One at a time it is a round trip per frame from the
#: RunPod data centre to R2, for up to 112 frames, while the GPU waits. On 21 Oct 2026 a warm
#: demo job spent about 25 s outside the depth service's own 9-11 s wall time, unmeasured until
#: the `frames_fetched` and `published` lines below existed.
FRAME_FETCH_CONCURRENCY = 16

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
class Sampling:
    """How to cut a video into frames here, when the job sends a video instead of frames."""

    fps: float
    max_frames: int
    long_edge: int
    #: Where the sampled frames are published, so whoever sent the video can show them.
    frames_prefix: str


@dataclass(frozen=True)
class JobRequest:
    frames: tuple[Frame, ...]
    bucket: str
    endpoint: str | None
    region: str | None
    prefix: str | None
    #: A video to sample on this machine instead of `frames` (`video` + `sampling` in the input).
    video_key: str | None
    sampling: Sampling | None
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

    video_key: str | None = None
    sampling: Sampling | None = None
    if payload.get("video") is not None:
        # The demo's dialect: one video by key, sampled here, so the frames never cross the
        # ocean twice (API -> bucket -> this machine). Exactly one of `video` and `frames`.
        if payload.get("frames"):
            raise JobRejected("job input carries both `video` and `frames`; send one")
        video = payload.get("video")
        if not isinstance(video, dict):
            raise JobRejected("`video` must be an object with a `key`")
        video_key = _text(video.get("key"), "video.key")
        raw_sampling = payload.get("sampling")
        if not isinstance(raw_sampling, dict):
            raise JobRejected("a `video` job needs a `sampling` object")
        frames_prefix = _text(raw_sampling.get("frames_prefix"), "sampling.frames_prefix").strip("/")
        sampling = Sampling(
            fps=_number(raw_sampling.get("fps", 10.0), "sampling.fps", low=0.001, high=60, integer=False),
            max_frames=int(_number(
                raw_sampling.get("max_frames", 112), "sampling.max_frames", low=2, high=512, integer=True)),
            long_edge=int(_number(
                raw_sampling.get("long_edge", 1024), "sampling.long_edge", low=64, high=4096, integer=True)),
            frames_prefix=frames_prefix,
        )
        raw_frames: object = []
    else:
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
    if len(frames) < 2 and video_key is None:
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
        video_key=video_key,
        sampling=sampling,
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
    if request.video_key is not None:
        # Beside the video: `demo/<id>/video.mp4` publishes under `demo/<id>/depth/<run_id>/`.
        return request.video_key.rsplit("/", 1)[0] if "/" in request.video_key else DEFAULT_OUTPUT_PREFIX
    parents = {key.rsplit("/", 1)[0] if "/" in key else "" for key in (frame.key for frame in request.frames)}
    if len(parents) != 1:
        raise JobRejected(f"frames come from {len(parents)} different directories; one job is one segment")
    parent = parents.pop()
    if not parent:
        return DEFAULT_OUTPUT_PREFIX
    if parent.endswith("/sampled-frames"):
        parent = parent[: -len("/sampled-frames")]
    return parent


# ---------------------------------------------------------------- sampling a video here
#
# The plan, the size and the command are `FfmpegDemoFrameSamplerAdapter` in greenv-video-api,
# itself `SamplingPlanner` and `FfmpegExtractor` from greenv-frame-extractor. Copied, not shared:
# the API and this handler are separate deployables, and whichever samples must produce the same
# frames, so a change to one is a change to both.

#: Frames written to the bucket at once while the model runs.
FRAME_PUBLISH_CONCURRENCY = 16


def _round_half_up(value: float) -> int:
    # Java's Math.round, which the API uses: Python's round() goes to even on .5.
    return int(math.floor(value + 0.5))


def sampling_plan(requested_fps: float, duration_s: float, max_frames: int) -> tuple[int, float, bool, int]:
    """`(count, effective_fps, capped, requested_count)`. Over budget, the rate drops so the
    frames still span the whole clip — the far side of the object is at the end of the orbit."""
    if not requested_fps > 0 or not duration_s > 0 or max_frames < 2:
        raise JobRejected("sampling needs a positive rate, a positive duration and at least 2 frames")
    requested = max(1, math.floor(requested_fps * duration_s))
    if requested <= max_frames:
        return requested, requested_fps, False, requested
    return max_frames, max_frames / duration_s, True, requested


def scaled_size(width: int, height: int, long_edge: int) -> tuple[int, int, bool]:
    """Even sides, never enlarged."""
    longest = max(width, height)
    if long_edge <= 0 or longest <= long_edge:
        return width, height, False
    factor = long_edge / longest

    def even(value: int) -> int:
        return max(2, _round_half_up((value * factor) / 2.0) * 2)

    return even(width), even(height), True


def parse_probe(root: dict) -> dict:
    """ffprobe's JSON reduced to what sampling needs, with the displayed size: ffmpeg rotates a
    phone's portrait video before any filter runs, so planning from the stored size would squash
    every portrait clip."""
    streams = root.get("streams") or []
    if not streams:
        raise JobRejected("the video has no video stream")
    stream = streams[0]
    rotation = 0
    for entry in stream.get("side_data_list") or []:
        if "rotation" in entry:
            rotation = int(entry["rotation"])
            break
    else:
        rotation = int((stream.get("tags") or {}).get("rotate", 0) or 0)
    rotation %= 360
    width, height = int(stream.get("width") or 0), int(stream.get("height") or 0)
    try:
        duration = float((root.get("format") or {}).get("duration"))
    except (TypeError, ValueError):
        duration = 0.0
    swapped = rotation in (90, 270)
    return {
        "codec": stream.get("codec_name"),
        "width": width,
        "height": height,
        "rotation": rotation,
        "display_width": height if swapped else width,
        "display_height": width if swapped else height,
        "duration_s": duration if duration > 0 else None,
    }


def sample_video(video: Path, out_dir: Path, sampling: Sampling, max_frames: int) -> dict:
    """Cut `video` into JPEGs in `out_dir` and say what was cut."""
    probed = subprocess.run(
        # Whole streams and format rather than `-show_entries`: this image's ffprobe predates the
        # `stream_side_data` section the API's names, and the full output carries the same fields.
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_streams", "-show_format",
         "-of", "json", str(video)],
        capture_output=True, text=True, timeout=120,
    )
    if probed.returncode != 0:
        raise JobRejected(f"ffprobe rejected the video: {probed.stderr.strip()[:300]}")
    probe = parse_probe(json.loads(probed.stdout or "{}"))
    if not probe["duration_s"] or probe["display_width"] <= 0 or probe["display_height"] <= 0:
        raise JobRejected("could not determine the video's duration or dimensions")
    count, effective_fps, capped, requested = sampling_plan(sampling.fps, probe["duration_s"], max_frames)
    width, height, scaled = scaled_size(probe["display_width"], probe["display_height"], sampling.long_edge)

    out_dir.mkdir(parents=True, exist_ok=True)
    filters = [f"fps={effective_fps:.9f}"]
    if scaled:
        filters.append(f"scale={width}:{height}")
    # This machine has the cores the API's single vCPU lacks; that is half the reason to be here.
    threads = str(min(os.cpu_count() or 2, 8))
    cut = subprocess.run(
        ["ffmpeg", "-nostdin", "-v", "error", "-threads", threads, "-i", str(video), "-map", "0:v:0",
         "-vf", ",".join(filters), "-frames:v", str(count), "-q:v", "2", str(out_dir / "frame-%04d.jpg")],
        capture_output=True, text=True, timeout=300,
    )
    if cut.returncode != 0:
        raise JobRejected(f"ffmpeg: {cut.stderr.strip()[:300] or 'could not extract frames'}")
    frames = sorted(path for path in out_dir.iterdir() if path.suffix == ".jpg")
    if len(frames) < 2:
        raise JobRejected(f"extraction produced {len(frames)} frame(s); at least 2 views are required")
    return {
        "paths": frames,
        "frames": len(frames),
        "fps": round(effective_fps, 3),
        "capped": capped,
        "requested_frames": requested,
        "width": width,
        "height": height,
        "duration_s": round(probe["duration_s"], 3),
        "codec": probe["codec"],
    }


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def say(fields: dict) -> None:
    """One JSON line on stdout, which is where RunPod's worker log reads from.

    Nothing in this handler used to print. A refused job therefore answered `{"error": ...}`,
    RunPod marked it FAILED, and its log held only the SDK's own `Started.` and `Finished.` -- so
    the reason a job was refused existed nowhere a person could read it. Twelve jobs failed that
    way on 10 September 2026 and the cause had to be inferred from which HTTP calls the worker
    did and did not make. The device line below is the one that would have answered it outright.
    """
    print(json.dumps(fields), flush=True)


def run_job(payload: object, *, depth, open_store, progress=lambda update: None) -> dict:
    """One job: keys in, a manifest of signed links out.

    `depth` and `open_store` are injected so every step except the forward pass itself can be
    exercised without a GPU -- see `test_handler.py`. The order is the cheapest refusal first,
    because the step after it is the one that costs money.

    `progress` receives what a caller polling the job can be shown before it ends: for a video
    job, the sampling as soon as it is done, then the published frame keys. Each update repeats
    everything known so far, because a poller sees only the latest one.
    """
    request = parse_job_input(payload)

    device = depth.device()
    total_bytes = int(device.get("total_bytes") or 0)
    say({
        "event": "device",
        "available": bool(device.get("available")),
        "name": device.get("device_name"),
        "total_bytes": total_bytes,
        "total_gib": round(total_bytes / 2**30, 2),
        "l4_reference_gib": round(L4_USABLE_BYTES / 2**30, 2),
        "frames": len(request.frames),
        "process_res": request.process_res,
    })
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
    seconds: dict[str, float] = {}
    sampled: dict | None = None
    publishing = None
    publish_pool = None
    fps, source_duration_s = request.fps, request.source_duration_s
    try:
        if request.video_key is not None:
            started = time.monotonic()
            video = workdir / request.video_key.rsplit("/", 1)[-1]
            video.write_bytes(store.get(request.video_key))
            seconds["fetch_video"] = round(time.monotonic() - started, 2)
            started = time.monotonic()
            sampled = sample_video(
                video, workdir / "frames", request.sampling,
                min(request.sampling.max_frames, effective_max_frames))
            seconds["sample"] = round(time.monotonic() - started, 2)
            paths = [str(path) for path in sampled.pop("paths")]
            fps, source_duration_s = sampled["fps"], sampled["duration_s"]
            sampled["seconds"] = dict(seconds)
            say({"event": "sampled", **sampled})
            progress({"stage": "sampled", "sampling": sampled, "frames": None})

            # The site wants the frames as thumbnails and as the colours of the cloud. They go up
            # while the model runs, read into memory first so the workdir can be removed at will.
            prefix = request.sampling.frames_prefix
            uploads = [(f"{prefix}/{Path(path).name}", Path(path).read_bytes()) for path in paths]
            publish_pool = ThreadPoolExecutor(max_workers=FRAME_PUBLISH_CONCURRENCY)
            started_publish = time.monotonic()
            put = [publish_pool.submit(store.put, key, data, "image/jpeg") for key, data in uploads]

            announced = threading.Lock()

            def finished(_):
                # Several callbacks can see the last upload done at once; one announces it.
                if not all(future.done() and future.exception() is None for future in put):
                    return
                if announced.acquire(blocking=False):
                    seconds["publish_frames"] = round(time.monotonic() - started_publish, 2)
                    progress({"stage": "frames", "sampling": sampled, "frames": [key for key, _ in uploads]})

            for future in put:
                future.add_done_callback(finished)
            publishing = (put, [key for key, _ in uploads])
        else:
            started = time.monotonic()
            with ThreadPoolExecutor(max_workers=FRAME_FETCH_CONCURRENCY) as pool:
                payloads = list(pool.map(lambda frame: store.get(frame.key), request.frames))
            paths = []
            for frame, data in zip(request.frames, payloads):
                target = workdir / frame.name
                target.write_bytes(data)
                paths.append(str(target))
            seconds["fetch_frames"] = round(time.monotonic() - started, 2)
            say({
                "event": "frames_fetched",
                "frames": len(paths),
                "bytes": sum(len(data) for data in payloads),
                "seconds": seconds["fetch_frames"],
            })

        params = {
            "fps": fps,
            "process_res": request.process_res,
            "max_frames": effective_max_frames,
        }
        if source_duration_s:
            params["source_duration_s"] = source_duration_s
        started = time.monotonic()
        manifest = depth.infer(paths, params)
        seconds["infer"] = round(time.monotonic() - started, 2)
        say({"event": "inferred", "seconds": seconds["infer"]})
        if publishing is not None:
            for future in publishing[0]:
                future.result()  # a frame that could not be published fails the job, loudly
            sampled["frames_published"] = len(publishing[1])
    finally:
        if publish_pool is not None:
            publish_pool.shutdown(wait=True)
        # Frames are input. Once inference has returned nothing reads them again, and a warm
        # RunPod worker keeps its filesystem between jobs.
        shutil.rmtree(workdir, ignore_errors=True)

    run_id = _text(manifest.get("run_id"), "the depth service's run_id")
    if "/" in run_id or ".." in run_id:
        raise JobRejected(f"the depth service returned an unusable run_id: {json.dumps(run_id)}")
    described = (manifest.get("frames") or {}).get("count")
    if not isinstance(described, int) or not 2 <= described <= len(paths):
        raise JobRejected(
            f"the depth service describes {described} frames of the {len(paths)} sent; "
            "a run whose geometry and images disagree measures the wrong scene"
        )

    published: list[dict] = []
    skipped: list[str] = []
    prefix = f"{output_prefix(request)}/depth/{run_id}"
    started = time.monotonic()
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

    seconds["publish"] = round(time.monotonic() - started, 2)
    say({
        "event": "published",
        "bytes": sum(artifact["size_bytes"] for artifact in published),
        "seconds": seconds["publish"],
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
            # Where this handler's own time went, so a slow job says which step was slow.
            "handler_seconds": seconds,
            # A video job only: how the video was cut here and where its frames now are.
            **({"sampled": {**sampled, "frame_keys": publishing[1]}} if sampled is not None else {}),
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
            # One pooled connection per concurrent frame read; urllib3 otherwise keeps ten and
            # opens and discards the rest on every job.
            config=Config(
                signature_version="s3v4",
                s3={"addressing_style": "path"},
                max_pool_connections=FRAME_FETCH_CONCURRENCY,
            ),
        )

    def get(self, key: str) -> bytes:
        return self._s3.get_object(Bucket=self.bucket, Key=key)["Body"].read()

    def put(self, key: str, data: bytes, content_type: str | None = None) -> None:
        extra = {"ContentType": content_type} if content_type else {}
        self._s3.put_object(Bucket=self.bucket, Key=key, Body=data, **extra)

    def signed_url(self, key: str) -> str:
        return self._s3.generate_presigned_url(
            "get_object",
            Params={"Bucket": self.bucket, "Key": key},
            ExpiresIn=self.ttl_seconds,
        )


_service = DepthService()


def _progress(job: dict, update: dict) -> None:
    """Hands an update to RunPod, which shows it to whoever polls `/status` while the job runs.
    Best effort: a progress line that cannot be sent must never fail the job it describes."""
    try:
        import runpod

        runpod.serverless.progress_update(job, update)
    except Exception as error:  # noqa: BLE001 -- see the docstring
        say({"event": "progress-not-sent", "reason": str(error)[:200]})


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
            progress=lambda update: _progress(job, update),
        )
    except JobRejected as rejected:
        say({"event": "job-rejected", "reason": str(rejected)})
        return {"error": str(rejected)}


if __name__ == "__main__":
    import runpod

    runpod.serverless.start({"handler": handler})
