# What GreenV actually is today

**Read this first.** It is the one file that says which parts run, which parts are written but
have never executed, and where the documentation has drifted away from the code. Every other
document in this repository describes one part correctly and is silent about the others; this one
is deliberately about the seams between them.

Written 10 September 2026 against commit `321ca57` and revised 11 September against `da7cc02`.
Anything below that names a file, a default or a number was checked against the code or against
the running deployment, not recalled.

**What changed on 11 September**, because it falsified the largest claims this file made a day
earlier: the Terraform was applied, the Azure stack is running, the RunPod depth image was built
and deployed, and four segments were measured end to end on a real GPU. The rows and the gap list
below carry the corrected status.

## The one-paragraph version

The capture chain is built and wired end to end: a phone records, the API stores, worker 1 cuts
segments, worker 2 measures grass height, and a packet lands in object storage. The chain has run
against a real GPU. **What is missing is the last hop and the first hop.** No measurement has ever
reached the dashboard, because the dashboard has no API client and a measurement has no `km` to
be placed at. And no automatic reading has ever been compared with a tape, so the numbers are
evidence to look at, not instructions to send a crew.

## What runs, and what has only been written

| Stage | Built | Tested | Ever run in the cloud |
|---|---|---|---|
| `apps/mobile` capture client | yes | `flutter test`, `flutter analyze` | n/a — it is the client. Android and web run; an iOS build needs Xcode on macOS |
| `greenv-video-api` | yes | `./gradlew check`, 76 tests | yes — `ca-greenv-mvp-api`, behind Cloudflare |
| `greenv-frame-extractor` (worker 1) | yes | `./gradlew check`, incl. real ffmpeg | yes — `ca-greenv-mvp-worker` |
| `greenv-measurement-worker` (worker 2) | yes | `npm test`, 52 tests | yes — `ca-greenv-mvp-measure` |
| depth on **Google Cloud Run GPU** | yes | — | yes, historically: the eight earliest runs and every VRAM ceiling |
| depth on **RunPod** | yes | `python test_handler.py`, no GPU | **yes, since 11 Sep 2026** — endpoint `greenv-mvp-depth`, four segments measured |
| `apps/web-mock` demo | yes | `npm run build` | n/a — mock data only, by design |
| `apps/web-prod` dashboard | yes | `npm run build` | **yes, since 11 Sep 2026** — Cloudflare Pages, reading the API |
| `infrastructure/` Terraform (Azure + Neon + R2) | yes | `terraform test`, 16 mocked | yes — applied; state in the R2 backend |

Two entries in that table changed on 11 September and are the ones people still get wrong:

- **Cloud Run is where the evidence came from.** Every VRAM ceiling and all eight of the earliest
  recorded depth runs went through the `verge-lab` Cloud Run GPU service, stood up by
  `measurement/scripts/deploy.sh`. Those numbers have never been re-measured anywhere else, so a
  ceiling quoted today is still a Cloud Run measurement.
- **RunPod is what the deployed stack actually calls.** `GREENV_INFER_ADAPTER=runpod` points at
  endpoint `greenv-mvp-depth`, which measured four segments on 11 September at 3.4 to 24.8
  GPU-seconds each. Its first day cost about five hours of a stalled image pull and twelve jobs
  that expired queued; `services/greenv-depth-runpod/README.md` records what happened and how to
  tell a slow pull from a dead one.

Both paths are still live in the configuration, and nothing has retired Cloud Run. That is the
first item in the gap list below.

## The gaps, worst first

Ranked by how much each one costs the project, not by how hard it is to fix.

### 1. Two depth deployments, and the numbers belong to the one not in use

RunPod is what the stack calls today. Cloud Run is where every VRAM ceiling, cost figure and
timeout in this repository was measured, and none of them has been re-measured on RunPod. So the
handler enforces a frame ceiling derived from an L4 while running on whatever card RunPod
allocates — on 11 September that was a `PRO 6000 MIG 24GB` partition, not the L4 the endpoint
asks for.

Keeping both also means every performance claim has to name its platform, and most still do not.

Cost of leaving it: a ceiling that is wrong in the unsafe direction kills a job mid-run after
paying for it, and a ceiling wrong in the safe direction silently caps quality. Neither shows up
as an error.

### 2. `km` — the last hop to the map, and it is closer than the docs admit

`docs/AUTOMATIC-HEIGHT.md` says the highway linear reference is something "GreenV does not have in
this repository yet". That is no longer accurate, and the correction matters because it changes
the size of the remaining work:

- `apps/web-mock/public/marco_km.geojson` holds **30 km-marker points, KM 0 to KM 29, all for
  SP-021**, and the same points now also live at
  `services/greenv-frame-extractor/src/main/resources/reference/highways.geojson`, ordered into a
  line a service can read.
- `apps/web-core/src/utils/routePlanner.js` assigns a km with `nearestKm()` — nearest marker to a
  polygon centroid.

So a linear reference exists. What does not exist is a reference any *service* can read: the file
is a frontend display asset, it covers one highway, and the measured spacing between consecutive
markers runs **626 m to 2042 m, averaging 1066 m**. Nearest-marker snapping at that spacing places
a reading within about half a kilometre. The measurement it would be labelling resolves grass in
**0.5 m cells**. Those two numbers are three orders of magnitude apart, and pretending otherwise is
how a `trecho` gets mown at the wrong marker.

The measurement worker is correct to leave `km` null and raise `road-metadata-missing`. What it
needs is a shared reference — every highway in scope, ordered along the road rather than as loose
points, readable by a service — plus a decision about how much error an operations team will accept
in a km label.

### 3. The dashboard had never seen a measurement, until the one that does

**Corrected 11 September 2026: `apps/web-prod` does, and is deployed.** What follows describes
`apps/web-mock`, which is now the demo and keeps every word of it true.

It contains no API client. The only two network calls in the whole application are
`fetch('/rocada_polygons.geojson')` and `fetch('/marco_km.geojson')` in `src/App.jsx`. Login,
teams, trends and service orders are `apps/web-mock/src/data/mock*.js` and `localStorage`. The 642 polygons come
from Motiva's KMZ and their vegetation levels from a spreadsheet, not from anything this system
measured.

That is a reasonable place for a demo to be. It is worth stating loudly because the dashboard is
what everyone sees, so the system looks finished from the only angle most people look at it from.

### 4. No accuracy has been established for anything automatic

Every tape-graded measurement in the project used a hand-painted mask. **Zero automatic readings
have been graded against a tape.** The packets say so themselves — `operationalStatus` is
`"not-ready"` on every one, and Verge Studio's verifier fails if it ever says otherwise.

This is not a documentation gap; the documentation is honest about it. It is on this list because
it is the gap that decides whether the product works, and no amount of plumbing closes it.

### 5. A driven segment measures its first 30 m, and the first driven day measured almost nothing

The extractor's `distance-groups` sampling spends its 112-frame budget on the first groups of
consecutive frames — 102 frames at 59 fps is 1.7 s of video. At the 63–90 km/h the seven sessions
of 13 September 2026 were driven, a ten-second segment covers 170–250 m and its measurement
covers the first 30–40 m. **Roughly four fifths of the road is never seen by the depth model.**
The extractor says so in every manifest (`groups[].published`); nothing downstream reported it.
Closing it is a decision, not a parameter: shorter segments in the app, more than one depth run
per segment at proportional GPU cost, or sparser sampling with a baseline DA3 has not been graded
at. Nothing has been chosen.

What the 41 measured segments of that day did contain was wrong for three separate reasons, all
found on a local bench that re-measures from the `scene.glb` and `result.npz` the RunPod handler
keeps beside the frames, with no GPU (`docs/AUTOMATIC-HEIGHT.md`, "Driving, not walking", and
`measurement/docs/evidence/2026-09-13-car-mount.md`):

- the band lay on the road side of the camera track in every usable segment, so twelve of them
  measured no cell at all although every frame carried a vegetation mask;
- DA3's per-clip scale ran from 0.78× to 1.86× against the GPS path length of the very frames it
  reconstructed, which the manifest already records per frame;
- four segments whose camera track collapsed to under 2 m — a phone still being mounted, a
  stopped car — were reported as 1.1 to 3.9 m of vegetation.

The pipeline and the worker now place the band from the masks, anchor the scale to the manifest's
path length, and refuse a collapsed track, all recorded in the packet; the worker can re-measure
a segment from the kept reconstruction (`reuseDepth`). A second pass the next day found three
more things a walk never showed: the frames of a driven capture float 24–52 cm against each
other, so each frame is now measured against its own ground; a tree's low branch is told from
a hedge by the air under it, not by its height; and the band is held to the five-metre mowing
corridor rather than to wherever the vegetation ends. With all of it on, the 43 segments read a
p50 of 2–18 cm on 37 of them, against the 16–28 cm the first pass gave a verge the photographs
put at 10–15. The corridor now ends where each cell's own ground starts to climb — two
consecutive rises of more than 10 cm per half-metre cell mark the embankment's foot, and 1,971
of 17,060 cells on that day were set aside as slope — and the stretch stands for the 90th
percentile of its cells rather than the 95th, because the top twentieth was the last half metre
against the guardrail: by p95 the day sat at 6 / 14 / 20 stretches on levels 1 / 2 / 3, by p90
at 9 / 21 / 10. Which aggregate and which thresholds a mowing policy should use remains the
question `measurement/docs/GRASS-QUALITY.md` leaves to Motiva; p90 is a draft.

Two stretches still read 0.55–0.59 m over grass of 5–15 cm, and both turned out to be the
guardrail or the concrete barrier itself: the segmentation never learned a guard rail
(Cityscapes leaves that label out of its nineteen classes) and calls it `terrain` in the frames
where it does not call it `fence` or `wall`. A cell that three frames saw a structure standing in
is now refused whatever the other frames read there, and nothing beyond the camera track's ends
is measured any more; that took one of the two from 0.59 m to 0.20 m. The other stayed at
0.55 m: its second half is a guardrail no frame calls anything but terrain, and nothing in the
geometry of a packet tells a 0.6 m rail from a 0.6 m stand of grass. So a second segmentation
now runs beside the first, a SegFormer trained on ADE20K, whose classes include `fence`,
`railing` and `wall` and whose guardrails are annotated `fence`; it is asked only what is not
grass, and its answer joins the structure map. That stretch reads 0.05 m. It costs 0.9 s of CPU
a frame on top of the 0.14 s of the grass model, and a wet rail in fog is still grass to both
models in some frames (`measurement/docs/evidence/2026-09-13-car-mount.md`, "A second model
that knows a fence").

### 6. The default stretch is now shorter than the graded band

As of 10 September 2026, `GroupPlanner.DEFAULT_GROUP_METERS` is **10.0 m**, lowered from 20 m so
that a person walking can exercise the pipeline without a car. Verge Studio's graded evidence
covers camera paths of roughly 14–25 m, so **at the default setting no group is graded at any
speed**. The code says this and the envelope flag reports it, and
`services/greenv-frame-extractor/README.md` was corrected on 11 September to describe the 10 m
default and what it costs. The four segments measured that day confirm it: every packet reports
`operationalStatus: "not-ready"` with the graded-envelope blocker among its seven.

### 7. Five cloud vendors, one MVP, and no record of which combination is real

The API and worker 1 between them ship three object-storage adapters (`local`, `s3`, `azure-blob`)
and four queue adapters (`rabbitmq`, `sqs`, `azure-queue`, `azure-service-bus`). Add Neon for
PostgreSQL, Cloudflare for R2 and DNS, Google Cloud for the depth service that ran and RunPod for
the one that has not, and the repository describes work across **AWS, Azure, Cloudflare, Google
Cloud, Neon and RunPod**.

The adapter design is sound — application services depend on ports, and queue payloads carry the
same versioned JSON whichever transport carries them. The gap was that no document said which
combination is deployed. Since 11 September one does: `infrastructure/README.md` describes the
running stack hop by hop, and the answer is Azure Container Apps with `azure-queue`, Cloudflare R2
through `s3`, Neon for PostgreSQL and RunPod for the GPU.

What remains is that the other adapters — `azure-blob`, `sqs`, `azure-service-bus`, `local`,
`rabbitmq` outside compose — are supported in code and exercised only by their own tests. Reading
the repository there is still no way to tell a path someone operates from a path someone wrote,
except by that one document.

### 8. The API does not authenticate a capture device

`infrastructure/README.md` states it and it belongs on this list: Terraform provisions TLS and
cloud identity between services, not application authentication. No external pilot user should be
invited until the API authorizes each device and each capture session.

### 9. The depth model's licence has no answer

Personal and research use only. Fine for a pilot, not fine for a concessionaire, and it needs an
answer before this chain carries operational traffic. Unchanged, and unresolved, since it was first
written down.

## Where the documentation was wrong, and what was corrected

Fixed in the same change that added this file:

| File | Was | Now |
|---|---|---|
| `README.md` | "The connection between the capture pipeline and `measurement/` is not built yet" | It is built and has run; the gaps are the dashboard and `km` |
| `docs/AUTOMATIC-HEIGHT.md` | No mention of RunPod; cost and timeouts described Cloud Run as the only deployment | Both depth adapters named, with which one has actually run |
| `docs/AUTOMATIC-HEIGHT.md` | "a highway linear reference that GreenV does not have in this repository yet" | It has 30 points for one road in the web app; what is missing is named precisely |
| `docs/INFRASTRUCTURE_MVP.md` | Topology stopped at the API and worker 1 | The measurement worker and the depth stage are in the diagram, and the doc's scope is dated |
| `services/greenv-frame-extractor/README.md` | "groups of about 20 m" | 10 m default, and what that costs |
| `apps/web/README.md` | `cd frontend` — a directory that has not existed since the repository was restructured | `cd apps/web`, and the mock-data boundary stated at the top |

Corrected on 11 September 2026, after the stack was applied and the first measurements ran:

| File | Was | Now |
|---|---|---|
| This file | RunPod never deployed; Terraform never applied; no service had run in a cloud | All three container apps running, RunPod measuring, Terraform applied |
| `infrastructure/README.md` | 516 lines that predated worker 2 and the measurement queues | Every hop with the name it travels under, per-service environment tables, and the names that must agree across services |
| `infrastructure/locals.tf` | The measurement worker's attempt limit and lease under the Java spellings, which that container has no reader for | `GREENV_MEASUREMENT_MAX_ATTEMPTS` and `GREENV_MEASUREMENT_VISIBILITY_SECONDS`, the names the Node worker reads |
| `services/greenv-depth-runpod/handler.py` | Printed nothing, so a refused job left only the SDK's `Started.` and `Finished.` | Logs the device it judged the job against, and every refusal |
| The API | No route returned a collection; nothing could be listed or discovered | Sessions, segments, measurements and frames all list, and each measurement is projected into its row |
| `apps/web` | One app on mock data | `web-core` shared, `web-mock` the demo, `web-prod` reading the API |
| The capture screen | Asked the operator for rodovia and sentido, optional, so both sessions came back null | Gone; the frame extractor derives both from the fixes and abstains where they do not support an answer |

## For an agent starting here

The repository is large and most of it is not your problem. In order:

1. Read `AGENTS.md`. It is the working agreement and it is short.
2. Read this file for what is real.
3. Start your session **in the directory you are changing**. Starting at the root pulls
   `measurement/`'s own 190-line agreement into context, which is most of it, and that agreement
   does not apply outside that subtree.
4. Do not cross the `measurement/` boundary in either direction. It is a git subtree of
   `github.com/henrikmm/verge-studio` and imports are what would break the round trip. A worker
   that needs a measurement spawns a process and reads JSON.
5. Believe a claim only where it carries its evidence. This project has twice paid real money for
   the difference between code that was written and code that was run.
