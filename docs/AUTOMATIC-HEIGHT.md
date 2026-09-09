# Automatic vegetation height

**A captured segment now becomes a grass measurement without anyone touching it.** The frame
extractor announces a finished segment, `greenv-measurement-worker` reconstructs it in three
dimensions, segments the vegetation, measures its height in half-metre cells along the road, and
publishes a packet beside the frames it came from. Nothing in the chain is manual.

**The numbers it produces are not ready for an operations decision, and the system says so
itself.** Every packet carries `operationalStatus: "not-ready"` with the blockers named, and
Verge Studio's own verifier *fails* if that field says anything else. Treat a reading as evidence
to look at, not as an instruction to send a crew. The rest of this document is mostly about why.

This is GreenV's side of the boundary. What the measurement itself does, and what it has been
graded against, lives in [`measurement/docs/GRASS-QUALITY.md`](../measurement/docs/GRASS-QUALITY.md);
this file does not restate it.

## The chain

| Stage | Runs on | Cost | Produces |
|---|---|---|---|
| `greenv-frame-extractor` | CPU | free | ~100 JPEGs + `frame-metadata-v2.json` per 10 s segment |
| **depth reconstruction** (Verge Studio `server/`) | **L4 GPU** | **real money** | `scene.glb`, `result.npz` |
| `greenv-measurement-worker` | CPU | free | `assessment.json`, `report.html`, `SHA256SUMS` |

The middle row is the whole difficulty. Everything else is free and local.

One segment is exactly one depth run, and that is not a choice: the extractor samples 10 fps
capped at 112 frames, which is about 100 JPEGs for a ten-second segment, and an L4 runs out of
memory above 144. Two segments cannot be merged into one inference, so there is no batching to
design.

## How to call it

Three entry points, all the same code path.

### Automatically, on a queue — the normal path

The frame extractor publishes a `MeasurementRequest` to the `greenv.capture` exchange with
routing key `segment.measure.v1` as its last act, and the worker consumes it. Turn it on with
`GREENV_MEASUREMENT_ENABLED=true` on the extractor; it is `false` by default, because a queue
nobody drains only grows. `compose.yaml` sets it.

```json
{ "schemaVersion": 1, "sessionId": "...", "segmentIndex": 0,
  "idempotencyKey": "mobile:session:0", "outputPrefix": "capture-sessions/<id>/segments/00000000",
  "sourceGeneration": "<sha256 of the source mp4>", "sampledFrameCount": 100,
  "capturedAt": "...", "requestedAt": "..." }
```

The message carries identifiers only. The worker reads the manifest, the frame metadata and the
frames themselves from object storage under `outputPrefix`, so this envelope never has to be kept
in step with what those files contain.

### Over HTTP — for a backfill, or a re-measure

```bash
curl -X POST http://localhost:8090/measurements \
  -H 'content-type: application/json' \
  -d '{"sessionId":"<uuid>","segmentIndex":0,"rodovia":"SP-021","sentido":"norte"}'
```

Answers `202 {"id": "...", "status": "running"}`; poll `GET /measurements/<id>`. Accepted and
polled rather than answered in one request, because a measurement is a depth run plus a CPU pass
— minutes, not seconds — and every proxy in between would give up first.

### As a process — what the worker itself does

```bash
node measurement/scripts/assess-grass.mjs --stdin --out ./packet
```

No import crosses into `measurement/`. That is deliberate and it is what keeps the subtree
round-trippable to `github.com/henrikmm/verge-studio`; see the rule in [`AGENTS.md`](../AGENTS.md).

## What comes back

Three artifacts land under `<outputPrefix>/measurement/`, plus one envelope:

| Object | What it is |
|---|---|
| `assessment.json` | The full evidence contract: cells, per-frame masks, ground fit, quality, provenance |
| `report.html` | A self-contained review page — every frame, its mask, the retained pixels. No server, no network |
| `SHA256SUMS` | Checksums of both |
| `measurement-result-v1.json` | GreenV's envelope: run id, class policy, quality summary, and the per-frame positions |

Verify any packet without a model, a browser or a GPU:

```bash
node measurement/scripts/check-grass-quality.mjs <directory>
```

It checks byte integrity, that every mask digest matches its pixels, and that the report and the
JSON agree. It establishes integrity, never truth.

## Joining the two systems: the four fields

This is the part that was not obvious, and it is the one that matters most for the dashboard.

Every artifact in GreenV carries `(rodovia, sentido, km, capturado_em)`. **The measurement cannot
produce `km` and must never be asked to.** Verge Studio measures in road-local coordinates —
metres along a reconstructed camera track — and substituting that for an official `marco km` is
the one thing `GRASS-QUALITY.md` explicitly forbids.

The seam is `frameContext`, keyed by canonical frame number, and the worker fills what it can:

- `capturado_em` comes from the frame's own telemetry.
- `rodovia` and `sentido` are passed through from the request, when GreenV knows them.
- `km` stays `null`, and that raises the `road-metadata-missing` blocker in the packet. Correct
  and visible, rather than silent and wrong.

What the worker keeps beside the packet, in `measurement-result-v1.json`, is `positions` — one
row per sampled frame with its latitude, longitude, GNSS quality, speed, course and matched
encoded-frame index. **Turning those into `km` needs a highway linear reference that GreenV does
not have in this repository yet.** That reference is the remaining piece of work between a
measurement and a `trecho` on the map.

Two frame numberings meet here and confusing them attaches the wrong position to a reading:
`frame-metadata-v2.json` has one row per *encoded* frame (~300 per segment), `sampledFrames` has
one per *JPEG* (~100), and Verge Studio's canonical number is the integer in the file name — so
canonical = `FrameRecord.index + 1`. The bridge is time, and `FrameRecord.timestampSeconds` is
nominal (`index / effectiveFps`, not the output frame's own presentation timestamp), so the match
is approximate by construction. At 60 km/h one frame is about 0.55 m of road.

## What is actually measured

**An extent, per half-metre cell, above that cell's own ground.** `extent50M`, `extent90M` and
`extent95M` are percentiles of vegetation height measured from a low percentile of the same cell's
retained points — which is what a tape measures against the base of a plant. `localGroundM`
reports where that datum sits above the fitted plane, so a raised bed or a shoulder crown stays
visible instead of being counted as grass.

That distinction is not cosmetic. On run `20260814-174814-b245bc` the tallest cell fell from
1.188 m to 0.391 m once its datum was its own ground, because 0.798 m of it was the bank the plant
stood on. Against the one taped object in the project, the extent read +1.9% and the
plane-relative H95 read +20.1%.

`h50M`, `h90M` and `h95M` are the same percentiles measured from the fitted plane, kept beside
them so the pedestal stays inspectable. `h95M − localGroundM === extent95M` for every measured
cell.

A cell is 0.5 m for a reason: ground that falls across a cell is added straight onto its extent as
grass that is not there. Build a longer `trecho` by aggregating cell *results*, never by widening
the cell.

## The class policy — read this before trusting a number

Segmentation is SegFormer-B0 finetuned on **Cityscapes**, so `classes` must name labels from that
fixed 19-item list. That part of the requirement is real: anything else is rejected outright.

**But "vegetation + terrain" is not a requirement, and `terrain` alone is not a safe default.**
Cityscapes files *horizontally spreading* growth under `terrain` and *vertically growing* plants
under `vegetation` — and roçada is about the vertical kind. On the one object in this project with
a tape measure against it, a plant at 0.980 m:

| `classes` | Its cell reads | Measured cells | Median extent | Recall on the recorded brush |
|---|---:|---:|---:|---:|
| `terrain` (Verge Studio's default) | **0.000 m** | 78 | 0.020 m | 0% |
| `vegetation` | 0.732 m | 61 | 0.665 m | 41.0% |
| `terrain,vegetation` | 0.761 m | 101 | 0.454 m | 41.0% |

A median that moves 33× between two policies is a statement about the mask, not about the verge.

**This worker sends `terrain,vegetation`** (`GREENV_MEASUREMENT_CLASSES`), and the choice is
recorded on every frame of every packet so a reader never has to assume. It is not the validated
policy — there is no validated policy — it is the one whose failure mode is visible rather than
silent. The union still misses most of the recorded plant: the missing pixels are upper blades
read as `fence` against a pale wall, which is exactly what pulls a P95 down.

Evidence: [`2026-09-05-class-fit.md`](../measurement/docs/evidence/2026-09-05-class-fit.md) and
[`2026-09-05-extent-vs-percentile.md`](../measurement/docs/evidence/2026-09-05-extent-vs-percentile.md).

## Limitations

Ordered by how likely each is to mislead someone reading a dashboard.

- **No physical accuracy has been established for any automatic reading.** Every tape-graded
  measurement in the project used a hand-painted mask. Zero automatic readings have been graded.
- **`km` is absent.** A measurement cannot be placed on the highway without the linear reference
  described above.
- **An empty cell means unknown, not short grass.** Unobserved, occluded, excluded and
  genuinely-bare all look identical in the output. `missingAreaMeaning` says so in every packet.
- **The corridor is assumed, not observed.** The band follows the estimated camera track at a
  fixed offset, folds both sides of the road together unsigned, and is not occlusion-tested. So
  *which side of the road* is not established, and `intendedAreaCoverage` is `null` on purpose —
  there is no honest denominator.
- **Dry grass is under-detected.** On `20260814-164826-0e4e4c` a green lawn gave 5,216 terrain
  pixels of 16,384 where the same lawn brown gave 225 and 540.
- **The ground plane is global and unvalidated.** It cannot establish soil beneath a crowned
  shoulder, a ditch or hidden ground.
- **Between-frame spread is not an error bar.** `h95SpreadM` is disagreement between views, not a
  calibrated interval.
- **The 10 cm and 30 cm thresholds are inherited dashboard fixtures**, not Motiva requirements,
  and no mowing policy has been agreed.
- **A full-length segment sits on the GPU's memory ceiling.** The extractor caps sampling at 112
  frames, which is Verge Studio's best graded setting, and a recorded 112-frame run peaked at
  22.02 GiB against the L4's 22.03 GiB usable — 99.95%. A ten-second segment produces about 100
  frames and has headroom; a thirty-second one is capped to 112 and does not. Lower
  `GREENV_INFER_MAX_FRAMES` before running long segments through this automatically.
- **Segmentation runs at 128×128 logits** and is deliberately not upsampled: on a 576×1024 frame
  one cell covers roughly 4.5 × 8 pixels. That is the honest resolution of the instrument.

## Cost, and the licence

**The depth model bills for the machine's whole lifetime, not for the seconds it computes.** A
run wakes an instance (~64 s cold start) which then lingers about fifteen minutes idle before
Cloud Run scales to zero. The compute itself is the small part: across the eight runs saved on
this machine, 64 to 112 frames at 504 px took **21.9 to 38.9 GPU-seconds** (41 to 117 s wall).
So a segment costs about a minute of work and up to fifteen minutes of billed idle. Segments arriving back to back from one drive ride a single warm
instance; a lone segment pays the entire tail. A drive that uploads continuously is affordable;
one segment an hour is not.

Per [`AGENTS.md`](../AGENTS.md), **waking that service needs the user's agreement in that
conversation, every time**, and the service is deleted when the work is done.

**The depth model is licensed for personal and research use only.** That is fine for a pilot and
it is not fine for a production deployment for a concessionaire. It needs an answer before this
chain carries operational traffic.

## Configuration

| Setting | Variable | Default |
|---|---|---|
| Depth service | `GREENV_INFER_BASE_URL` | `http://127.0.0.1:5173/api` (the local mock) |
| Accept mock packets | `GREENV_MEASUREMENT_ALLOW_MOCK` | `false` |
| Cityscapes classes | `GREENV_MEASUREMENT_CLASSES` | `terrain,vegetation` |
| Corridor offset, metres | `GREENV_MEASUREMENT_OFFSET_M` | `2` |
| Object storage | `GREENV_OBJECT_STORAGE_ADAPTER` | `local` (or `s3`) |
| Trigger queue | `GREENV_MEASUREMENT_QUEUE` | `greenv.segment.measure.v1` |
| Announce from the extractor | `GREENV_MEASUREMENT_ENABLED` | `false` |

**About the mock.** With no depth service configured, the worker talks to Verge Studio's
fixture-backed stand-in, which answers every request with the same four-frame reconstruction of an
unrelated scene. A packet built that way pairs your frames with someone else's geometry. It is
worth producing — it exercises every seam for free — and it is never a measurement. The worker
**refuses** to publish one unless `GREENV_MEASUREMENT_ALLOW_MOCK=true`, and any packet that does
get through carries `mock: true`. A mock run was mistaken for a real one once, on 2026-08-05, and
the guards exist because of it.

## What has been verified, and what has not

Observed on 2026-09-08, on one machine:

- **The whole chain, on real geometry.** `test/end-to-end.test.mjs` replays a reconstruction Verge
  Studio actually computed on an L4 from run `20260814-164826-0e4e4c`'s own 100 frames, and drives
  the real segmentation, the real ground fit and the real packet writer through it. Result: **14
  measured cells, 100% observed-cell coverage, 29.2 s**, and the packet passes
  `check-grass-quality.mjs` on checksums, mask digests and report/JSON agreement. The only
  simulated part is the HTTP call that would have produced the reconstruction.
- **23 worker tests pass** (`npm test` in `services/greenv-measurement-worker`).
- **The extractor's trigger compiles and is asserted.** `./gradlew check` passes with the new
  publish, and both `SegmentExtractionServiceTest` and `SegmentExtractionServiceIntegrationTest`
  assert that a finished segment announces itself — the integration one against real ffmpeg.

- **Two runs against the real GPU service**, deployed and torn down the same session. Both
  segments went through the production pipeline against a live L4, and both packets pass
  `check-grass-quality.mjs`:

  | Segment | Frames | GPU | Measured / abstained cells | Coverage | Max extent |
  |---|---:|---:|---:|---:|---:|
  | lawn (`20260814-164826-0e4e4c`) | 100 | 31.5 s | 14 / 0 | 100% | 0.161 m |
  | garden (`20260814-174814-b245bc`) | 94 | 23.8 s | 66 / 12 | 84.6% | 1.128 m |

  Provenance on both reads `depth-anything/DA3NESTED-GIANT-LARGE-1.1` at the pinned revision,
  `mock: false`, `operationalStatus: "not-ready"`, `km: null`, and the `road-metadata-missing`
  blocker — the designed behaviour, observed rather than assumed.

- **A fresh reconstruction reproduces the recorded one.** These frames were last reconstructed in
  August; today's independent run of the garden clip fitted a ground plane at 23.3° tilt, 17 mm
  RMSE and 28.3% inliers, against the 23.489°, 17.1 mm and 27.8% on record. The lawn returned the
  same 14 measured cells and 0 abstentions as its recorded result. Nothing here grades height
  against a tape; it says the geometry stage is reproducible.

Not verified:

- **`docker compose up` has not been run.** Docker is not installed on the machine this was built
  on, so the compose wiring and the `Dockerfile` are written and unexecuted.
- **No accuracy claim of any kind.** Both runs above are reproducibility and plumbing evidence.
  No automatic reading has ever been compared with a tape. See Limitations.
