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

**One window is exactly one depth run**, and that is not a choice: 112 frames is the ceiling the
extractor plans to and an L4 runs out of memory above 144. A window is about 25 m of road, so a
ten-second segment driven at highway speed is eight or nine depth runs rather than the one it was
until 15 September 2026 — the reason is in `STATE-OF-THE-SYSTEM.md`, gap 5. Windows cannot be
merged into one inference, so there is no batching to design.

### Where the middle row runs

`GREENV_INFER_ADAPTER` chooses between two depth deployments, and only one of them has ever
executed.

| Adapter | What it talks to | Status |
|---|---|---|
| `http` (default) | A Verge Studio `server/` behind a URL — in practice the **Google Cloud Run GPU** service in the `verge-lab` project, or the local mock | **The only path that has ever run.** Every GPU-second, VRAM ceiling and cost figure in this document came from it |
| `runpod` | A RunPod serverless endpoint running [`services/greenv-depth-runpod`](../services/greenv-depth-runpod/README.md) | **Never deployed.** The image has never been built and no job has ever run. Its tests pass without a GPU |

The contract is the same on both sides and the worker's own tests cover each adapter, so this is a
deployment choice rather than a code fork. What it is not yet is a *decision*: nothing in this
repository says which one GreenV keeps, and until something does, every number about the depth
stage has to name the platform it was measured on. See
[`STATE-OF-THE-SYSTEM.md`](STATE-OF-THE-SYSTEM.md).

The RunPod side has one operational difference worth knowing before it is first woken: frames
travel to it **by object key**, not in the job body, because a RunPod job payload is far too small
for a hundred JPEGs. That makes `GREENV_OBJECT_STORAGE_ADAPTER=s3` mandatory on that path.

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
  "capturedAt": "...", "requestedAt": "...",
  "rodovia": "BR-101", "sentido": "norte" }
```

The message carries identifiers and the road, and nothing else. The worker reads the manifest, the
frame metadata and the frames themselves from object storage under `outputPrefix`, so this envelope
never has to be kept in step with what those files contain.

The road is the exception, and it has to be: the worker calls Verge Studio, which keeps exactly
four fields per frame and cannot look anything up. `rodovia` and `sentido` come from the capture
session — the operator names them on the capture screen before recording — and travel
`POST /v2/capture-sessions` → `capture_sessions` → the extraction request → `segment-manifest-v2.json`
→ this announcement. Both are `null` for a capture recorded before the app asked, and null reaches
the packet as null. `sentido` is one of `norte`, `sul`, `leste`, `oeste`; the API refuses anything
else with `invalid_sentido`, and the extractor refuses a queue message carrying anything else with
`invalid_segment_request`.

The deployment runs no broker, so the same three hops also travel over Azure Queue Storage:
`GREENV_SEGMENT_QUEUE_ADAPTER=azure-queue` switches all three services at once. The bodies above
and below are unchanged — a queue is not a contract — but Azure Queue has no exchange, so each
routing key becomes a queue of its own, named by `GREENV_AZURE_MEASUREMENT_QUEUE_NAME` and
`GREENV_AZURE_MEASURED_QUEUE_NAME`. Retry changes shape with it: there is no delayed republish, a
failed segment simply reappears when its visibility timeout expires, and one that will never
succeed goes to a poison queue instead of being dropped.

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

Three artifacts land under `<outputPrefix>/measurement/`, plus one envelope. A segment cut into
windows puts each window's four objects under `<outputPrefix>/measurement/wNN/` instead, two
digits and zero padded; a segment measured whole keeps the unprefixed path, which is where every
packet measured before 15 September 2026 still is:

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
- `rodovia` and `sentido` come from the capture session, named once by the operator before the
  route starts. Null when nobody named them, which is honest rather than convenient.
- `km` stays `null`, and that raises the `road-metadata-missing` blocker in the packet. Correct
  and visible, rather than silent and wrong.

What the worker keeps beside the packet, in `measurement-result-v1.json`, is `positions` — one
row per sampled frame with its latitude, longitude, GNSS quality, speed, course and matched
encoded-frame index. **Turning those into `km` needs a highway linear reference no service in this
repository can read.** That reference is the remaining piece of work between a measurement and a
`trecho` on the map, and it is worth being precise about how much of it is missing, because a
partial one already exists:

`apps/web/public/marco_km.geojson` holds 30 km-marker points, KM 0 to KM 29, all for SP-021, and
`apps/web/src/utils/routePlanner.js` already snaps a polygon to the nearest one. Three things stop
that from being the answer here. It is a frontend display asset, so nothing server-side reads it.
It covers one highway. And the measured spacing between consecutive markers runs **626 m to 2042 m,
averaging 1066 m** — so nearest-marker snapping places a reading within roughly half a kilometre,
against cells this worker resolves at 0.5 m. Labelling a 0.5 m cell with a km good to 500 m is not
a rounding difference; it is a different instrument.

What is needed is a reference covering every highway in scope, ordered along the road rather than
held as loose points, readable by a service — and a decision about how much error an operations
team will accept in a km label.

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

## Driving, not walking

Everything Verge Studio graded was walked: a person beside a garden bed, the camera a metre or
two from the plant. The first day of driving (13 September 2026, seven sessions at 63–90 km/h)
measured 41 segments and got twelve with no cell at all and readings of 2–15 cm on verges that
were not that short. Re-measured on a local bench from the reconstructions the RunPod handler
keeps beside the frames — no GPU — the causes were three, and none of them was the mask
(`measurement/docs/evidence/2026-09-13-car-mount.md`):

| What a walk assumes | What a car does | What the worker asks for now |
|---|---|---|
| The verge is 2 m to one fixed side of the camera track (`GREENV_MEASUREMENT_OFFSET_M`) | It is 5–9 m out, behind a shoulder and a guardrail, on the *other* side in 37 of 37 usable segments | `GREENV_MEASUREMENT_OFFSET_SIDE=auto`: the band's side, distance and width come from the run's own masks, and the packet records them under `corridor.band` |
| DA3's metric scale is right, as it was on the graded walk | It ran from 0.78× to 1.86× against the GPS path of the very frames it reconstructed | `GREENV_MEASUREMENT_SCALE_ANCHOR=telemetry`: the manifest's `distanceMeters` span becomes the track's expected length; one factor per run, recorded under `scale`, refused outside 0.5–2× |
| The camera moved | Four segments had a camera track under 2 m — a phone still being mounted, a stopped car — and reported a door handle and a tree as 1.1–3.9 m of vegetation | `GREENV_MEASUREMENT_MIN_TRACK_M=3`: a shorter track is refused with the blocker `camera-track-too-short` |
| The road is a surface the depth model can see | A wet road reflects the sky, and the reflection reads as depth scattered below the surface; one segment found no ground plane (0.80% support against the 1% floor) and measured nothing | `GREENV_MEASUREMENT_GROUND_FALLBACK=true`: one coarser fit when the strict one fails, kept only if the camera stands a plausible height above it, named `ground-fit-relaxed` in the packet. That segment then measures 601 cells |
| The frames agree about where the ground is | Within one frame a cell's heights spread 1–8 cm; between frames the same cell floated 24–52 cm. The pooled datum sat on the lowest frame, the canopy percentile on the middle one, and a mown verge read half the float as grass: 16–28 cm where the photographs show 10–15 | `GREENV_MEASUREMENT_DATUM=per-frame`: each frame's extent against that frame's own ground, then the median. The same verges read 9–11 cm |
| A crown is taller than a verge | A branch hanging at two metres passes under a 3 m ceiling and inside a 2 m extent; one segment read a p95 of 1.58 m from the tree beside the road | `GREENV_MEASUREMENT_CANOPY_GAP_M=0.5`: a crown floats — ground, then half a metre or more of nothing, then foliage — and a hedge or tall grass does not. A cell whose frames see that gap is `canopy` |
| The verge is wherever the vegetation is | A band that follows the vegetation out to 10 m climbs the slope behind the mowing corridor, and the corridor's mown strip reads the slope's brush | `GREENV_MEASUREMENT_BAND_WIDTH_M=5.5`: the band starts half a metre before the vegetation and reaches five metres into it, which is what a crew cuts |
| The road is not vegetation | In rain the wet asphalt reflects the trees and the segmentation calls a fifth of the frame `vegetation`; a band that folds both sides of the edge together counted the road as cells of 0 cm | The band keeps the vegetation's side of the edge only (`bandSide`, set by the auto edge), so the road is out whatever the mask says of it |
| A grass pixel is grass | At 128×128 logits one class pixel is 4.5 by 8 photograph pixels, and the grass against a guardrail carried the rail's lower edge: the tallest cells of a mown strip were its last half metre against the rail, 40–57 cm where the strip read 3–9 | `GREENV_MEASUREMENT_EXCLUDE_NEAR=fence,wall,pole,building`, one logit pixel around: a pixel next to a structure is not measured |
| The corridor is as wide as the band | Behind a 5 m corridor an embankment climbs, and a band that reaches it measures the brush on the slope with the strip: one segment read a p95 of 0.82 m on a strip cut short | `GREENV_MEASUREMENT_SLOPE_RISE_M=0.1`: two consecutive rises of each cell's own ground by more than that per half-metre cell mark the slope's foot, and every cell beyond is `slope`, aggregated nowhere. That segment reads 0.17 m; 1,971 of the day's 17,060 cells were slope |
| The stretch is its top twentieth of cells | That twentieth was the last half metre against the guardrail, and it held 20 of 40 stretches at level 3 over grass of 2–18 cm. The aggregate dropped to the 90th percentile for a few hours of 14 September 2026 while that was true | The measurement stopped measuring the rail the same day, and with it gone the two percentiles agree: over the 43 segments the gap is 0–6 cm, usually 2, and no stretch reaches level 3 at either. So `STRETCH_PERCENTILE` is back at 0.95, the aggregate that is actually about mowing. The field keeps its `extent95P95M` name because the installed capture app reads it |
| A structure is a structure in every frame | Cityscapes never trained on a guardrail, and a wet rail or a concrete barrier is `fence` or `wall` in some frames and `terrain` in the rest; the frames that call it grass measure it, 0.7–0.8 m agreed across twenty frames, because it is an object of that height. A pixel margin around the structure classes cannot reach a frame that saw none | `GREENV_MEASUREMENT_STRUCTURE_FRAMES=3`: each frame's structure pixels are back-projected into the same cells as its grass, and a cell that three frames saw a structure standing in is `structure`, kept with its numbers, aggregated nowhere. One stretch went from a p90 of 0.59 m to 0.20 m |
| The grass model knows what a guardrail is | Cityscapes never trained it on one: a wet W-beam or a concrete barrier is `terrain` to it in frame after frame, and a stretch of mown lawn behind a barrier read a p90 of 0.55 m from the barrier alone | `GREENV_MEASUREMENT_STRUCTURE_MODEL=ade20k-b4`: a second SegFormer, trained on ADE20K's 150 classes (`fence`, `railing`, `wall`, `bannister` among them), asked only what is not grass; its structure pixels join the structure map. That stretch reads 0.05 m. About 0.9 s a frame of CPU |
| The road edge spans the stretch | The edge is the camera track and stops at the last pose, while the depth reaches on down the road; a point past the end folds onto it with the overshoot turned into distance, so a guardrail one metre out filled the last along-road cell at every distance to the band's width, 65–75 cm in each | `GREENV_MEASUREMENT_PAST_ENDS=drop`: nothing before the first pose or beyond the last is measured |
| Everything `vegetation` claims is verge | A band wide enough to hold the verge reaches the tree line: in nine segments 10–25% of the cells were crowns and cut faces 2–8 m up, and the segment's p95 read 3.9–8.7 m | `GREENV_MEASUREMENT_MAX_HEIGHT_M=3` drops a point higher than that above the road before it can reach a cell; `GREENV_MEASUREMENT_CANOPY_EXTENT_M=2` reports a cell still taller than that as `canopy`, with its numbers, outside every aggregate. The class stays `terrain,vegetation` — `terrain` alone reads 0.000 m on a plant taped at 0.980 m, because tall grass is `vegetation` too |

All three are off in Verge Studio's own defaults and on in the deployment's Terraform. A packet
built without them from a driven capture is unreliable, and the worker can rebuild it from the
kept reconstruction rather than paying for depth again: a queue message or `POST /measurements`
body carrying `force: true, reuseDepth: true` skips the GPU when `depth/<runId>/scene.glb` and
`result.npz` are still beside the frames.

**What driving exposed, and what was done about it.** The extractor's `distance-groups` sampling
spent its 112-frame budget on the first groups of consecutive frames, and at highway speed that was
30–40 m of a 170–250 m segment: 1,545 m of the 7,923 m the 43 segments of 13 September planned, 19%
of the road. Since 15 September the extractor cuts at 25 m and publishes every group, and the
worker gives each window its own reconstruction, packet and announcement, so a stretch is a window
rather than a segment. It costs about five times the depth frames, and deployed on 16 September 2026;
`STATE-OF-THE-SYSTEM.md`, gap 5, carries the evidence and what it measured in production.

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

**The depth model bills for the machine's whole lifetime, not for the seconds it computes.** The
figures below were all measured on **Google Cloud Run GPU**, which is the only depth deployment
that has ever run; the RunPod path costs the same shape and has no measurements of its own. A
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
| Depth adapter | `GREENV_INFER_ADAPTER` | `http` (or `runpod`) |
| Depth service, on `http` | `GREENV_INFER_BASE_URL` | `http://127.0.0.1:5173/api` (the local mock) |
| Depth endpoint, on `runpod` | `GREENV_INFER_RUNPOD_ENDPOINT_ID` | none. An endpoint id, not a URL |
| Depth credential | `GREENV_INFER_TOKEN` | none. A Bearer header for `http`, an API key for `runpod` |
| Accept mock packets | `GREENV_MEASUREMENT_ALLOW_MOCK` | `false` |
| Cityscapes classes | `GREENV_MEASUREMENT_CLASSES` | `terrain,vegetation` |
| Corridor offset, metres | `GREENV_MEASUREMENT_OFFSET_M` | `2` |
| Which side of the camera track the band goes to | `GREENV_MEASUREMENT_OFFSET_SIDE` | `given` (or `auto`, which reads it off the masks — see [Driving, not walking](#driving-not-walking)) |
| Shortest camera track worth measuring, metres | `GREENV_MEASUREMENT_MIN_TRACK_M` | `0`, off. The deployment sets 3 |
| Where the metric scale comes from | `GREENV_MEASUREMENT_SCALE_ANCHOR` | `none`. The deployment sets `telemetry`: the GPS path length of the sampled frames, from the manifest |
| The lens's height above the road, metres | `GREENV_MEASUREMENT_CAMERA_HEIGHT_M` | unset. A taped height wins over the telemetry anchor when given |
| Highest point that is still verge, metres above the road | `GREENV_MEASUREMENT_MAX_HEIGHT_M` | unset, every point kept. The deployment sets 3: higher is a crown |
| Tallest extent that is still verge, metres above the cell's own ground | `GREENV_MEASUREMENT_CANOPY_EXTENT_M` | unset. The deployment sets 2: a taller cell is reported as `canopy` and counted in no aggregate |
| A second, coarser ground fit when the first finds no floor | `GREENV_MEASUREMENT_GROUND_FALLBACK` | `false`. The deployment sets `true`; a relaxed fit is named in the blockers as `ground-fit-relaxed` |
| Where a cell's own ground comes from | `GREENV_MEASUREMENT_DATUM` | `pooled`, all voting frames together. The deployment sets `per-frame`: each frame against its own ground, then the median |
| Vertical gap that marks a crown, metres | `GREENV_MEASUREMENT_CANOPY_GAP_M` | unset. The deployment sets 0.5: a cell whose frames see that much empty air under the foliage is `canopy` |
| Band width from the detected road edge, metres | `GREENV_MEASUREMENT_BAND_WIDTH_M` | unset, which lets the band follow the vegetation out to 10 m. The deployment sets 5.5: the mowing corridor |
| Classes whose neighbourhood is not measured | `GREENV_MEASUREMENT_EXCLUDE_NEAR` | empty. The deployment sets `fence,wall,pole,building`, one logit pixel around (`GREENV_MEASUREMENT_EXCLUDE_NEAR_PX`) |
| Rise per half-metre cell that marks a slope's foot | `GREENV_MEASUREMENT_SLOPE_RISE_M` | unset. The deployment sets 0.1: two consecutive rises of more than that end the corridor, and every cell beyond is `slope` |
| Frames that must see a structure standing in a cell to refuse it | `GREENV_MEASUREMENT_STRUCTURE_FRAMES` | unset. The deployment sets 3: a cell three frames saw one of the excluded classes in is `structure`, whatever the other frames called it |
| Points past the camera track's ends | `GREENV_MEASUREMENT_PAST_ENDS` | `fold`, Verge Studio's own behaviour for a walked polyline. The deployment sets `drop`: nothing beyond the first or last pose is measured |
| A second segmentation asked only what is not grass | `GREENV_MEASUREMENT_STRUCTURE_MODEL` | empty. The deployment sets `ade20k-b4`, a SegFormer trained on ADE20K's 150 classes, because the Cityscapes grass model was never taught a guardrail; about 0.9 s a frame on the worker's CPU |
| The probability the second model must give those classes, summed, for a pixel to be a structure | `GREENV_MEASUREMENT_STRUCTURE_FLOOR` | unset, which leaves Verge Studio's 0.5, a majority of the probability. The deployment sets 0.4: no single class need win a pixel the model spreads over fence, railing, wall and bannister, and a rail in fog it half-sees still counts |
| What the second model's pixels do besides voting on cells | `GREENV_MEASUREMENT_STRUCTURE_MODEL_MASK` | `always`: they also leave the grass mask with the margin. A query model's mask fades a metre onto the grass beside a rail and, out of the mask in every frame, starved a median strip of two thirds of its points; `band` takes them out only of the copy that places the band, so it starts where the grass starts, and `never` leaves the mask alone |
| That model's structure classes, in its own names | `GREENV_MEASUREMENT_STRUCTURE_CLASSES` | empty. The deployment sets `fence,railing,wall,bannister,pole,column,signboard,building,house,streetlight,step`: out of the grass mask with the exclusion margin, and into the cells the structure bar counts |
| A third segmentation standing beside the second | `GREENV_MEASUREMENT_STRUCTURE2_MODEL`, `_CLASSES`, `_FLOOR`, `_MODEL_MASK` | empty. The deployment sets `ade20k-b4` with `tree,palm`, floor 0.5, mask `never`, because no one model sees everything: the Vistas model that places the band and vetoes a rail has no class for a bush, and on 16 September 2026 a Cityscapes `vegetation` mask measured one as 1.81 m of grass 1 m behind a barrier — tall grass lives in that class too, so the class cannot go. The ADE20K model called that bush `tree` in 84-98% of its pixels and the mown strip beside it `grass`; its classes vote on cells like the second model's and touch neither the grass mask nor the band. Verge Studio takes the two as `structureModels`, a list; one model still travels under the four names above |
| Re-measure from the reconstruction already in the bucket | `GREENV_MEASUREMENT_REUSE_DEPTH` | `false`; a request can also ask per segment with `reuseDepth: true` |
| Object storage | `GREENV_OBJECT_STORAGE_ADAPTER` | `local` (or `s3`) |
| Trigger queue | `GREENV_MEASUREMENT_QUEUE` | `greenv.segment.measure.v1` |
| Announce from the extractor | `GREENV_MEASUREMENT_ENABLED` | `false` |
| Queue transport | `GREENV_SEGMENT_QUEUE_ADAPTER` | `rabbitmq` (or `azure-queue`) |
| Trigger queue, on Azure | `GREENV_AZURE_MEASUREMENT_QUEUE_NAME` | none |
| Result queue, on Azure | `GREENV_AZURE_MEASURED_QUEUE_NAME` | none |
| Poison queue, on Azure | `GREENV_AZURE_MEASUREMENT_POISON_QUEUE_NAME` | none. Without it a hopeless message is deleted |
| Measurement visibility, seconds | `GREENV_MEASUREMENT_VISIBILITY_SECONDS` | `1800`, the measurement timeout |

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
- **The worker's tests pass**: 23 on 2026-09-08, and 52 on 2026-09-10 (51 pass, 1 skipped
  without the saved run), run as `npm test` in `services/greenv-measurement-worker`.
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

Observed on 2026-09-10, in the cloud deployment:

- **The queue path is wired end to end and the first RunPod job was dispatched.** Both workers
  had failed to activate on missing queue-name variables; with those set, the measurement worker
  starts, drains `greenv-segment-measure-v1` and reaches the endpoint `greenv-mvp-depth`. At the
  time of writing the job was queued behind the endpoint's first image pull.
- **No measurement has come back from that endpoint.** No packet in R2 was produced by a GPU, and
  every `measurement_state` in the database is still NULL.

Not verified:

- **`docker compose up` has not been run.** Docker is not installed on the machine this was built
  on, so the compose wiring and the `Dockerfile` are written and unexecuted.
- **No accuracy claim of any kind.** Both runs above are reproducibility and plumbing evidence.
  No automatic reading has ever been compared with a tape. See Limitations.
