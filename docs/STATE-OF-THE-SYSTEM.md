# What GreenV actually is today

**Read this first.** It is the one file that says which parts run, which parts are written but
have never executed, and where the documentation has drifted away from the code. Every other
document in this repository describes one part correctly and is silent about the others; this one
is deliberately about the seams between them.

Written 10 September 2026 against commit `321ca57`. Anything below that names a file, a default
or a number was checked against the code on that day, not recalled.

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
| `greenv-video-api` | yes | `./gradlew check` | no |
| `greenv-frame-extractor` (worker 1) | yes | `./gradlew check`, incl. real ffmpeg | no |
| `greenv-measurement-worker` (worker 2) | yes | `npm test` | no |
| depth on **Google Cloud Run GPU** | yes | — | **yes — the only thing ever deployed** |
| depth on **RunPod** | yes | `python test_handler.py`, no GPU | **never. Image never built, endpoint never woken** |
| `apps/web` dashboard | yes | `npm run build` | n/a — mock data only |
| `infrastructure/` Terraform (Azure + Neon + R2) | yes | `terraform test`, mocked | **never applied. No state file exists** |

Two entries in that table are the ones people get wrong:

- **Cloud Run is what has actually run.** Every VRAM ceiling and all eight recorded depth runs went
  through the `verge-lab` Cloud Run GPU service. `measurement/scripts/deploy.sh` is what stands it
  up (`gcloud run deploy verge-da3`), and it is the only deployment script in the repository that
  has ever been executed against a cloud.
- **RunPod is the intended replacement and has never executed.** `services/greenv-depth-runpod/`
  is complete — handler, Dockerfile, Terraform, a shared contract example both sides test against
  — and its own README says plainly that the image has never been built. Both paths are live in
  the configuration today: `GREENV_INFER_ADAPTER` selects `http` (Cloud Run) or `runpod`.

Nothing in this repository has yet said which of those two is the one to keep. That decision is
the first item in the gap list below.

## The gaps, worst first

Ranked by how much each one costs the project, not by how hard it is to fix.

### 1. Two depth deployments, no decision between them

Cloud Run has the runs and the measurements; RunPod has the newer code, the Terraform and nobody's
observation. Keeping both means every cost figure, every VRAM ceiling and every timeout in the
documentation has to say which platform it came from — and today most of them do not say. The
ceiling table in `services/greenv-depth-runpod/README.md` is honest about this: it was measured on
Cloud Run's L4 and has never been checked on RunPod's.

Cost of leaving it: the first RunPod job pays a cold start and a GPU minute to discover whether an
untested image even boots, and there is no baseline to compare it against.

### 2. `km` — the last hop to the map, and it is closer than the docs admit

`docs/AUTOMATIC-HEIGHT.md` says the highway linear reference is something "GreenV does not have in
this repository yet". That is no longer accurate, and the correction matters because it changes
the size of the remaining work:

- `apps/web/public/marco_km.geojson` holds **30 km-marker points, KM 0 to KM 29, all for SP-021**.
- `apps/web/src/utils/routePlanner.js` already assigns a km with `nearestKm()` — nearest marker to
  a polygon centroid.

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

### 3. The dashboard has never seen a measurement

`apps/web` contains no API client. The only two network calls in the whole application are
`fetch('/rocada_polygons.geojson')` and `fetch('/marco_km.geojson')` in `src/App.jsx`. Login,
teams, trends and service orders are `apps/web/src/data/mock*.js` and `localStorage`. The 642 polygons come
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

### 5. The default stretch is now shorter than the graded band

As of 10 September 2026, `GroupPlanner.DEFAULT_GROUP_METERS` is **10.0 m**, lowered from 20 m so
that a person walking can exercise the pipeline without a car. Verge Studio's graded evidence
covers camera paths of roughly 14–25 m, so **at the default setting no group is graded at any
speed**. The code says this and the envelope flag reports it; `services/greenv-frame-extractor/README.md`
still describes the old 20 m default in prose.

### 6. Five cloud vendors, one MVP, and no record of which combination is real

The API and worker 1 between them ship three object-storage adapters (`local`, `s3`, `azure-blob`)
and four queue adapters (`rabbitmq`, `sqs`, `azure-queue`, `azure-service-bus`). Add Neon for
PostgreSQL, Cloudflare for R2 and DNS, Google Cloud for the depth service that ran and RunPod for
the one that has not, and the repository describes work across **AWS, Azure, Cloudflare, Google
Cloud, Neon and RunPod**.

The adapter design is sound — application services depend on ports, and queue payloads carry the
same versioned JSON whichever transport carries them. The gap is that no document says which
combination is deployed, and the answer today is *none of them*: the Terraform has never been
applied and there is no state file. `docs/INFRASTRUCTURE_MVP.md` recommends a topology; it does not
record one.

For an agent, this is the single most expensive ambiguity in the repository. Reading it, there is
no way to tell a supported path from a hypothetical one without checking each adapter's tests.

### 7. The API does not authenticate a capture device

`infrastructure/README.md` states it and it belongs on this list: Terraform provisions TLS and
cloud identity between services, not application authentication. No external pilot user should be
invited until the API authorizes each device and each capture session.

### 8. The depth model's licence has no answer

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
