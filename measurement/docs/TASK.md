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
way a person can check.** `geometry/grass-height-grid.ts` already does the measuring. What it
lacks is a mask it can get by itself, and a readout that shows what it decided.

The order below is not arbitrary. Every task produces the instrument the next one is graded with,
because the failure this project keeps hitting is not a wrong number, it is a wrong number nobody
could see was wrong.

**The road edge is deliberately postponed.** Every reconstruction on this disk is a garden, a lawn
close-up or a paved patio; none has a road in it, so nothing about a road edge could be graded
today. The band stays where it is — placed from the camera path, labelled as an assumption — until
there is a roadside clip to test against. Task 4 holds that work and is blocked on purpose.

---

## Now

### 1. Segment a whole clip, with nobody clicking

**Why.** The grid wants a mask on at least three frames and the only way to make one is to paint it
by hand. That is why the run in the 2026-09-04 screenshot never started: `paint the verge on at
least 3 frames — 1 so far`. Hand-painting is also the thing the delivered product cannot do.

The seam that makes this small: **a semantic mask is a normal mask.** The store already keys masks
by (target, frame) and already has a `"model"` source. Write the mask there and every instrument we
already own applies to it for free — Depth 2D draws it, the grid consumes it, recorded evidence
keeps it, and task 1's inspector can score it.

**Gate.** One action segments the verge on every frame of a run, and the grid then runs over all of
them with no click anywhere in the sequence. On run `20260814-174814-b245bc` that means at least 3
frames masked automatically and a grid result produced from them. The masks are visible in Depth 2D
and their provenance says which model, which revision and what fraction of pixels the floor
excluded.

**Regression.** The click path must not change. SlimSAM stays exactly as it is, `collect-evidence`
replays all 26 trials with no failures, and the grid's 33 tests pass unchanged — a segmentation
change has no business moving a percentile.

**Approval.** `none`. The runs are already on disk and the model runs locally.

**Start.** `geometry/semantic-mask.ts` turns logits into the mask and is done; what is missing is
a caller inside the app. `scripts/inspect/segment-model.mjs` shows how the model is pinned and run
in Node, and `app/src/measurement/segmenter.ts` how one is loaded on WebGPU in the browser.
`setMaskData` in `app/src/measurement/measurement-store.ts` takes a source and a provenance already;
`SegmentationProvenance` is click-shaped — prompts, candidate scores — so a semantic mask needs its
own provenance kind rather than being forced into that one.

- [ ] A provenance record for a semantic mask: model, revision, runtime, device, floor, and the
      pixel counts the floor excluded.
- [ ] Segment every frame of the run, writing normal masks.
- [ ] Run the grid from those masks and record what came out.
- [ ] Say what it cost in wall-clock time, measured. 173–240 ms per frame for logits on a Node CPU
      is the only number we have; the browser on WebGPU is untested.
- [ ] Check what dry grass does to it. The Registry's 2026-09-04 entry records the mask following
      the green and leaving brown grass out, on the run this task uses.

### 2. Show what the grid measured

**Why.** H50 and H90 are computed, exported, and displayed **nowhere**. Cells are shaded by H95
alone, the panel prints one H95 range for the whole run, and selecting a cell shows its coordinate
and a pixel count with no height at all. So the app can produce a grass measurement and then
decline to tell you what it is.

That is tolerable while a person is painting one clump and reading the JSON. It is not tolerable
once task 3 lands, because then the masks, the cells and the numbers are all produced automatically
and the only remaining human job is judging whether they are right.

**Gate.** Selecting a cell states its H50, H90 and H95, how many frames voted, and how many samples
they contributed — in the app, on screen. An abstained cell states why it abstained in the words the
measurement used (`too-few-frames`, `too-few-samples`) rather than showing nothing.

**Regression.** The overlay's brightness ramp still encodes H95 over the run's own range, and
DESIGN.md's rule that hue may not carry state still holds. Adding numbers must not turn the ramp
into a colour scale.

**Approval.** `none`.

**Start.** `app/src/panes/depth-2d.tsx` around line 617 is the selected-cell readout that currently
prints a coordinate and a pixel count. `GrassCellMeasurement` in `geometry/grass-height-grid.ts`
already carries every field this needs, `evidenceFrameIndices` included.

- [ ] Per-cell H50/H90/H95 where the cell is selected.
- [ ] Frame count and sample count beside them, because three frames agreeing is a different claim
      from twelve.
- [ ] An abstained cell says which of the two reasons applied.
- [ ] Look at it in the browser pane and screenshot it. There are no component tests here; the
      design-review workflow and a screenshot are the check.

### 3. Grade the automatic mask against the human one

**Why.** Tasks 1–4 make an automatic measurement that can be looked at. They do not establish that
it is right. The cheap half of that is available today and costs nothing: brush masks are recorded
at full frame resolution in `~/verge-runs/*/measurements/*.json`, so model-against-human is
scoreable offline on runs already on disk.

What is missing is the other side of the pair. Every recorded brush is a **clump** — Grass-Exe1,
Garden Light — and the class we are betting on is `terrain`, the lawn. So there is currently no
human mask of the thing we intend to measure.

**Gate.** The lawn is painted by hand on at least three frames of two different runs, and the
`terrain` mask is scored against those brushes: IoU, recall and precision per frame, reported in
the Registry with the run ids and frame numbers. A number below which we would not ship is agreed
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
- [ ] Score, and report every frame including the bad ones.
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
compared against the camera-path band on the same run. The comparison is the point: if the two
disagree, we learn how much the assumption was costing, and if they agree we learn the assumption
was cheap.

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

**Start.** REGISTRY section 3, "Roadside grass is measured in road-local cells", has what V1
established and every limit it carries. `MEASUREMENTS.md` is the format to match.

- [ ] Propose two or three candidate protocols with what each one would let us claim.
- [ ] Agree one.
- [ ] Execute, grade, and report the abstention rate beside the reading — a height measured over
      30% of the cells is a different claim from one measured over 90%.
