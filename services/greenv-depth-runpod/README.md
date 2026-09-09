# GreenV Depth — RunPod handler

The depth stage as a serverless job: object keys in, a reconstruction in the bucket and a manifest
of signed links out. It is the other half of `GREENV_INFER_ADAPTER=runpod` in
[`greenv-measurement-worker`](../greenv-measurement-worker/README.md), which until now had no
handler to talk to.

**Nothing here has been deployed and no GPU has been woken.** The image has never been built, the
endpoint has never existed, and no job has ever run. Everything below the "What is proven" heading
is a recipe, not a record. Per the root [`AGENTS.md`](../../AGENTS.md), running any of it needs the
user's agreement in that conversation, every time.

## Why it lives here and not in `measurement/`

`measurement/` is Verge Studio, a git subtree of `github.com/henrikmm/verge-studio`, and nothing
outside it may import from inside it. This handler is GreenV's deployment of Verge Studio's depth
service, not a change to Verge Studio, so it sits beside the other deployable things in
`services/` and reaches the model the way every other GreenV process does: it spawns it and speaks
its published contract. `handler.py` imports nothing from `measurement/`; it runs `uvicorn
main:app` on loopback and calls `/gpu`, `/infer` and `/artifact`.

The image is built **from** the image `measurement/server/Dockerfile` produces, so the model
revision, the CUDA stack and the checkpoint are pinned in exactly one place.

## The contract

```
input   { frames: [{ name, key }], storage: { bucket, endpoint, region },
          params: { fps, process_res, max_frames, source_duration_s } }
output  the DA3 manifest, with `artifacts` rewritten to absolute signed bucket URLs
```

A RunPod job body is JSON with a payload ceiling far below a hundred JPEGs, which is why frames
travel by key and the handler reads them from the bucket itself. Artifact URLs are **absolute**
signed links: a serverless handler has no origin of its own to serve files from, and
`infer-runpod.mjs` rejects a relative URL rather than resolving it against RunPod's API host.

One job in and one manifest out live in
[`contract/depth-job-v1.example.json`](contract/depth-job-v1.example.json), and both sides read
that same file in their tests — `test_handler.py` here and
`greenv-measurement-worker/test/runpod-contract.test.mjs` there — so the queue's two halves
cannot drift apart without a test failing.

What lands in the bucket, beside the frames it was computed from:

```
<segment prefix>/depth/<run_id>/scene.glb      the reconstruction
<segment prefix>/depth/<run_id>/result.npz     depth, confidence, extrinsics, intrinsics
```

`result.npz` is **Verge Studio's** array bundle, not DA3's. DA3's own exporter writes a file with
that exact name into the same directory, so the handler selects by artifact *kind* and never by
file name; DA3's is left on the instance and named in `published.skipped`.

## `max_frames` is a memory ceiling, not a preference

A 112-frame run at 504 px peaked at **21.28 GiB against an L4's 22.03 GiB usable**; 128 and 144
both completed at ~99% of the device, and 160 ran out of memory
([`measurement/docs/vram-measurements.json`](../../measurement/docs/vram-measurements.json),
2026-08-01). Past the ceiling the process is killed rather than answered, so the caller pays a cold
start and a GPU minute to learn nothing.

**Every number in this handler assumes an NVIDIA L4 with 22.03 GiB usable** — the device Verge
Studio's sweeps were measured on. The ceilings, the largest count that ran with real headroom at
each resolution:

| `process_res` | Frames | Measured peak | The next step up |
|---|---:|---:|---|
| 504 px | 112 | 21.28 GiB | 128 → 21.94, 144 → 21.88 (~99%), 160 → OOM |
| 356 px | 192 | 19.39 GiB | 256 → 21.54 (~98%) |
| 252 px | 256 | 15.89 GiB | never tried, so this is a floor on the truth |

The handler checks the job against this table before it downloads a single frame, and:

- **refuses a job with more frames than the ceiling**, naming the device and the count;
- **refuses outright on a GPU smaller than an L4**, because the table does not transfer down —
  set `GREENV_DEPTH_MAX_FRAMES` to what your device has been measured to hold and you own that
  claim;
- **refuses above 504 px**, where no measurement exists, rather than extrapolating;
- **lowers the `max_frames` it forwards** to the service's own 422, so the second fence fires on
  the truth about this device rather than the caller's assumption about a different one.

The frame extractor already caps sampling at 112, so on the intended path this is a second fence
rather than the first.

## Build

Two steps, because the first one is Verge Studio's own image and is not this directory's to
duplicate.

```bash
# ~12 GB, 15-20 min. No GPU needed to build it.
docker build -t verge-da3:local measurement/server

# seconds
docker build -t <registry>/greenv-depth-runpod:<tag> \
  --build-arg DEPTH_IMAGE=verge-da3:local \
  services/greenv-depth-runpod

docker push <registry>/greenv-depth-runpod:<tag>
```

Tag by content, never `latest`: RunPod caches images on its workers, and a mutable tag is how a
worker ends up running something nobody can identify. `measurement/scripts/cloud-common.sh` hashes
`server/` for exactly this reason; hash `server/` and `handler.py` together here.

## The endpoint

Create a **Serverless** endpoint from that image. The settings that matter:

| Setting | Value | Why |
|---|---|---|
| GPU | 24 GB (L4 / A10G class) | Every ceiling above was measured on an L4. A smaller card is refused by the handler rather than silently killed. |
| Max workers | 1 | One segment is one inference; a second worker is a second cold start, not more throughput. |
| Idle timeout | see *Cost* | The whole bill lives here. |
| Execution timeout | 900 s | Matches the Cloud Run deploy. A cold worker pays a model load before a 112-frame run's ~120 s. |
| Container disk | ≥ 20 GB | ~12 GB image plus ~130 MB of artifacts and the frames per run. Not measured — check it on the first build. |
| Flashboot | on | Cheaper warm starts, and it changes nothing about the contract. |

Environment, set on the endpoint:

| Variable | Default | What it is |
|---|---|---|
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | — | **Secrets.** The bucket credentials, read through the ordinary AWS chain. Nothing GreenV-specific, nothing in the image. |
| `GREENV_DEPTH_MAX_FRAMES` | unset | Overrides the ceiling table. Required on any GPU other than an L4 or larger; the operator owns the number. |
| `GREENV_SIGNED_URL_TTL_SECONDS` | `43200` | How long a published link stays usable. Matches `VERGE_SIGNED_URL_TTL_SECONDS` in `measurement/server/main.py`. |
| `GREENV_DEPTH_OUTPUT_PREFIX` | `depth-runs` | Only used when the frame keys do not name a segment directory. |
| `GREENV_DEPTH_SERVICE_PORT` | `8080` | The loopback port the DA3 service listens on. |
| `VERGE_OUTPUT_BUCKET` | *unset, deliberately* | Setting it sends artifacts to **GCS** instead of local disk, which this deployment has no credentials for. The image clears it and the handler clears it again. |

The bucket is the same R2 bucket the rest of the stack uses (`GREENV_S3_BUCKET`,
`GREENV_S3_ENDPOINT` in `infrastructure/locals.tf`). The credentials need read on
`<prefix>/sampled-frames/*` and write on `<prefix>/depth/*`.

Then point the worker at it:

```
GREENV_INFER_ADAPTER=runpod
GREENV_RUNPOD_ENDPOINT=https://api.runpod.ai/v2/<endpoint-id>
GREENV_RUNPOD_API_KEY=<key>
GREENV_RUNPOD_POLL_MS=5000
GREENV_OBJECT_STORAGE_ADAPTER=s3        # required; the handler reads frames from the bucket
GREENV_S3_BUCKET=<bucket>
GREENV_S3_ENDPOINT=https://<account>.r2.cloudflarestorage.com
```

## Cost

**A GPU endpoint bills for the worker's lifetime, not for the seconds it computes.** On the Cloud
Run deployment a run costs about a minute of work and up to fifteen minutes of billed idle: 64 to
112 frames at 504 px took **21.9 to 38.9 GPU-seconds** (41 to 117 s wall) across the eight runs on
record, behind a ~64 s cold start. RunPod's shape is the same and its idle timeout is the dial:

- Segments arriving back to back from one drive ride one warm worker. A long idle timeout is
  cheap and saves a model load per segment.
- A lone segment pays the entire tail. A short idle timeout is the only defence.

There is no batching to design: one segment is exactly one inference, because two segments cannot
be merged into one and an L4 runs out of memory above 144 frames anyway.

Two more things this does not decide. **The depth model is licensed for personal and research use
only** — fine for a pilot, not fine for a concessionaire. And **no automatic reading has ever been
graded against a tape**; see [`docs/AUTOMATIC-HEIGHT.md`](../../docs/AUTOMATIC-HEIGHT.md).

## What is proven, and what is not

```bash
python services/greenv-depth-runpod/test_handler.py     # 12 groups of asserts, stdlib only
cd services/greenv-measurement-worker && npm test       # includes runpod-contract.test.mjs
```

Proven, on 9 Sep 2026, without a GPU:

- the job envelope, both directions, against the shared example both suites read;
- frames travel by object key, in recorded order, and their bytes never enter the job body;
- the published `result.npz` is Verge Studio's bundle and not DA3's same-named export;
- every refusal that has to happen before money is spent — over the ceiling, a GPU smaller than an
  L4, no CUDA device, a resolution with no measurement, a frame with no key, a single frame;
- the artifact digest check, the run-id and frame-count checks, and a rejected job answering with
  an error RunPod reports as `FAILED`.

**Not proven, and not provable without waking a paid endpoint:**

- **the image has never been built.** The base tag, the `pip` resolution in `requirements.txt` and
  the build-time import check are all unexecuted.
- **`DepthService` and `BucketStore` have never run.** Spawning uvicorn, the multipart `/infer`
  call, the `/artifact` fetch, the S3 get/put and the presigned URL are written and untested — the
  test doubles stand in for exactly these.
- **no reconstruction has been produced through this path**, so nothing says the manifest a real
  DA3 run returns matches the example above field for field.
- **the ceiling table has never been checked on a RunPod L4.** It was measured on Cloud Run's.
- **RunPod's own queue semantics are taken from its documentation**, not observed — `/run`,
  `/status/<id>`, and `{"error": ...}` becoming `FAILED`.
