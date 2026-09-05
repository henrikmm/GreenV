# Tasks — what to do next

Only unfinished work lives here. What already works and why is in [REGISTRY.md](REGISTRY.md); how
to work in this repository is in [../AGENTS.md](../AGENTS.md).

**When a task is done, delete it from this file and write what it established into the Registry,
in the same change.** Git history is the archive. Never keep a finished task here as a record.

Every task is owned by the agent. None of them is a request for the user to go and do something,
even when a step needs the user's hands or their permission.

## How a task is written

A task is a test with a plan attached. It states the pass condition **before** the work starts, so
finishing is a fact rather than an opinion. Five fields, then the steps:

- **Why** — the reason this task exists. What is wrong today, what it costs us, what breaks if we
  skip it. This is the most important field: a task whose Why is thin is a task nobody should
  start.
- **Gate** — the condition that decides whether the work succeeded. Written so it can be run,
  with the numbers it has to beat.
- **Regression** — what must not get worse. Nearly every change here can improve one number while
  quietly ruining another, so name the thing being protected and where to check it.
- **Approval** — `none`, `user confirmation`, or `cloud spend + user confirmation`. Where you stop
  and ask. It is not permission to leave the task half done.
- **Start** — the files and commands to read first.

Then a checklist of steps. Tick a box when that step is done and verified, not when the code is
written. Unticked boxes are allowed in this file and nowhere else in the repository.

---

## What this file is about right now

One thing: **measuring the height of grass from a whole video, with nobody clicking anything, in a
way a person can check.** `geometry/grass-height-grid.ts` already does the measuring. The whole-run CPU path and evidence report now expose its masks,
source pixels and decisions. The remaining gates establish whether those decisions can be trusted.

The order below is not arbitrary. Every task produces the instrument the next one is graded with,
because the failure this project keeps hitting is not a wrong number, it is a wrong number nobody
could see was wrong.

**The road edge is deliberately postponed.** Every reconstruction on this disk is a garden, a lawn
close-up or a paved patio; none has a road in it, so nothing about a road edge could be graded
today. The band stays where it is — placed from the camera path, labelled as an assumption — until
there is a roadside clip to test against. Task 4 holds that work and is blocked on purpose.

---

## Now

### 3. Grade the automatic mask against the human one

**Why.** The automatic pipeline makes measurements that can be looked at. They do not establish that
it is right. The cheap half of that is available today and costs nothing: brush masks are recorded
at full frame resolution in `~/verge-runs/*/measurements/*.json`, so model-against-human is
scoreable offline on runs already on disk.

What is missing is the other side of the pair. Every recorded brush is a **clump** — Grass-Exe1,
Garden Light — and the class we are betting on is `terrain`, the lawn. So there is currently no
human mask of the thing we intend to measure.

**Gate.** The lawn is painted by hand on at least three frames of two different runs, and the
`terrain` mask is scored against those brushes: IoU, recall and precision per frame, plus H95 deltas, lost cells and draft threshold crossings using
the same geometry, reported with run ids and exact frame identities. A number below which we would not ship is agreed
**before** the scoring runs, not after seeing it.

**Regression.** The recorded clump trials are evidence and must not be touched. New brushes are new
targets, never edits of existing ones.

**Approval.** `user confirmation` for the pass threshold, since it is the number that decides
whether the automatic path is trusted. The painting itself is the agent's task to drive: prepare the
run, open the frame, ask for the one action.

**Start.** `node scripts/inspect.mjs segment <run> --against <trial>` is the scoring instrument and
already works. `readMeasurementEvidence` in
`scripts/inspect/source.mjs` loads recorded brushes; the RLE codec is `decodeMask` in
`app/src/measurement/measurement-store.ts`.

- [ ] Agree the threshold first, and write it here before running anything.
- [ ] Paint the lawn on three frames of `20260814-174814-b245bc` and three of
      `20260814-164826-0e4e4c`. These are different scenes; one clip cannot establish this.
- [ ] Score held-out frames, including empty predictions as zero recall; keep correction effort,
      repeatability, abstention and intended-area coverage beside overlap scores.
- [ ] Where the model and the brush disagree, look at the frame and say what the disagreement is —
      a boundary, a hole, or a different object. Three failures named are worth more than one mean.

---

## Later

### 4. The road edge, and the clip that could test one

**Why.** The band the grid measures inside is placed from the reconstructed camera path pushed
sideways by a slider. That is an assumption, it is labelled as one everywhere it appears, and it
cannot be improved on this disk: no saved run contains a road.

The one attempt to get a road edge from a model is already a warning rather than a lead. SegFormer
B2 called 9.74% of a garden frame `road`, and what it was looking at was a wall. A road edge derived
from that class would be confidently wrong in exactly the way this project cannot detect — a
plausible polyline, in the wrong place, producing plausible heights of the wrong ground.

**Gate.** A roadside clip is recorded and reconstructed; the road edge is derived from it and
graded against independent boundary annotations or surveyed points, including visible and occluded
regions. Compare the camera-path band as a baseline; agreement between two estimates is not truth.

**Regression.** The camera-path band stays available and stays labelled. Whatever produces a
polyline, `measureGrassHeightGrid` keeps taking one as input — that boundary is what lets the
extractor change without touching the measurement.

**Approval.** `cloud spend + user confirmation` — reconstruction needs the GPU service. The
recording needs the user, and the protocol for it is written before they are asked.

**Start.** The camera-path band is `roadEdgeFromCameraTrack` in
`app/src/measurement/grass-grid.ts`, and `measureGrassHeightGrid` in
`geometry/grass-height-grid.ts` states why the polyline is an input rather than something it
derives. `node scripts/inspect.mjs segment <run>` shows what the `road` class does on a frame, and
on a wall.

**Blocked** on a roadside clip existing. Do not start the extractor before there is one to test it
against.

- [ ] Write the recording protocol: what to shoot, from where, how long, at what speed, and what to
      tape while standing there so the result can be graded.
- [ ] Reconstruct one clip, batching any other GPU work into the same warm machine.
- [ ] Only then, derive a polyline, and test it against the frame where the boundary is visible and
      the frame where it is occluded.

### 5. Grade a height against a real lawn

**Why.** Every result the grid produces is stamped `validationStatus: "unvalidated"`, and a person
accepting one deliberately does not change that. Nothing here has been graded against a lawn, and
the reason is real rather than neglect: a mown surface has no single top to hold a tape against —
press harder and the number shrinks — so the reference protocol is a decision about what we are
claiming, not a measurement technique.

**Gate.** A physical reference protocol is agreed with the user, executed, and the reading graded in
`MEASUREMENTS.md` with its coverage and abstention rate beside it. Only this can move anything off
`unvalidated`.

**Regression.** Nothing in the measurement path moves to make a number look better. If the protocol
says we read 4 cm high, that is the finding.

**Approval.** `user confirmation` of the protocol before it is executed, because it decides what
number the project is claiming.

**Start.** [GRASS-QUALITY.md](GRASS-QUALITY.md) proposes three reference definitions and a field
protocol for agreement. REGISTRY section 3, "Roadside grass is measured in road-local cells", has what V1
established and every limit it carries. `MEASUREMENTS.md` is the format to match.

- [ ] Agree one.
- [ ] Execute, grade, and report the abstention rate beside the reading — a height measured over
      30% of the cells is a different claim from one measured over 90%.


### 6. Connect reviewed evidence to road operations

**Why.** A successful local process is not a mowing decision. Cells currently lack surveyed road
coordinates, local soil validation, an intended-area denominator and an approved severity policy.

**Gate.** An adapter calls the measurement process without cross-subtree imports, retains the JSON
and HTML with checksums, joins `(rodovia, sentido, km, capturado_em)` from actual telemetry, and
links the evidence from each stretch. Pending, rejected, failed and insufficiently covered results
cannot enter operational aggregates. Agree stretch length, severity percentile, height thresholds,
minimum coverage, exceedance fraction and near-threshold handling before the pilot.

**Regression.** Unknown height remains unknown. Acceptance of visual plausibility never changes
physical validation. The standalone measurement checkout continues to run independently.

**Approval.** `user confirmation` for the decision policy and release gate.

**Start.** [GRASS-QUALITY.md](GRASS-QUALITY.md), `scripts/assess-grass.mjs`.

- [ ] Agree the operational and field-accuracy gates before held-out scoring.
- [ ] Establish local soil and a signed corridor footprint; abstain where either is unavailable.
- [ ] Build and exercise the worker adapter and dashboard evidence links with real route telemetry.


### 7. Finish two existing presentation inconsistencies

**Why.** The evidence review found an amber hint and pane percentages that appeared only after
resize; both conflict with the existing interface contract.

**Gate.** DESIGN items 26 and 28 pass on a fresh 1280×800 layout: build readiness uses neutral
text, and each pane shows its percentage before any resize.

**Regression.** Actual warnings remain visible. No cloud action is triggered by the display fix.

**Approval.** `none`.

**Start.** `docs/design-review-log.md`, the Setup cloud status and pane-share readouts.

- [ ] Correct the hint styling and initial pane-share rendering; verify in the browser.
