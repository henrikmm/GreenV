# GreenV infrastructure

Everything the deployed system is made of: what Terraform creates, what a person has to create
first, every environment variable each service reads, and which hop carries what between them.

Four workloads run. Three are Azure Container Apps that scale to zero; the fourth is a GPU
endpoint that Terraform deliberately does not own. Nothing here bills when nobody is capturing,
except the depth endpoint while a worker of its own is alive.

```text
apps/mobile (Flutter)
  |  HTTPS + Bearer token
  v
Cloudflare DNS/TLS
  |
  v
ca-greenv-mvp-api ....................... public ingress, 0..3 replicas, 1 vCPU / 2 GiB
  |--> Neon PostgreSQL ................... capture_sessions, capture_segments
  |--> R2 greenv-mvp-captures ............ source.mp4, telemetry.json
  `--> queue greenv-segment-extract-v2 ... send only
              |  KEDA scales on queue length
              v
ca-greenv-mvp-worker (frame extractor) .. no ingress, 0..4 replicas, 2 vCPU / 4 GiB
  |--> R2 ................................ sampled-frames/, segment-manifest-v2.json,
  |                                        frame-metadata-v2.json
  |--> Neon .............................. segment state, frame count, manifest key
  `--> queue greenv-segment-measure-v1 ... send, and only when measurement is enabled
              |  KEDA scales on queue length
              v
ca-greenv-mvp-measure (measurement) ..... no ingress, 0..1 replicas, 2 vCPU / 4 GiB
  |--> R2 ................................ reads sampled-frames/
  |--> RunPod depth endpoint ............. sends frame KEYS; the handler reads R2 itself
  |--> spawns measurement/scripts/assess-grass.mjs as a child process
  |--> R2 ................................ writes measurement/measurement-result-v1.json
  |                                        and the packet beside it
  `--> queue greenv-segment-measured-v1 .. send only
              |  the API polls it every second
              v
ca-greenv-mvp-api ....................... capture_segments.measurement_state = 'measured'
```

The API is the only workload with ingress. Neither worker has any. The measurement worker's lack
of ingress is the stronger statement of the two: it answers `POST /measurements` as a manual
backfill trigger, that route has no authentication of its own, and every accepted call wakes a
paid GPU.

No resource is created by `fmt`, `validate` or `test`. `terraform plan` reads cloud APIs and
creates nothing. `terraform apply` creates billable resources and must be reviewed first.

---

## How each service talks to the next

Every hop, with the name it travels under. A name that disagrees at either end is the most
expensive failure this stack has had: three days so far, always silently, because a misconfigured
service either starts and drains nothing, or fails to activate while the platform quietly keeps
serving the previous revision.

| # | From | To | Transport | Name | Carries |
|---|---|---|---|---|---|
| 1 | mobile | API | HTTPS | `POST /v2/capture-sessions/.../video`, `.../telemetry`, `.../complete` | the ten-second MP4 and its telemetry JSON |
| 2 | API | R2 | S3 API | `capture-sessions/<id>/segments/<8 digits>/source.mp4`, `.../telemetry.json` | the uploaded bytes |
| 3 | API | Neon | JDBC, pooled | `capture_sessions`, `capture_segments` | session and segment state |
| 4 | API | Azure Queue | managed identity | `greenv-segment-extract-v2` | one extraction request per completed segment |
| 5 | queue | frame extractor | KEDA `azure-queue`, queueLength 1 | same queue | wakes worker 1 from zero |
| 6 | extractor | R2 | S3 API | `.../sampled-frames/*.jpg`, `.../segment-manifest-v2.json`, `.../frame-metadata-v2.json` | the frames and what is known about each |
| 7 | extractor | Neon | JDBC | `capture_segments` | state, frame count, manifest key, error code |
| 8 | extractor | Azure Queue | managed identity | `greenv-segment-measure-v1` | one announcement per segment that published frames |
| 9 | queue | measurement worker | KEDA `azure-queue`, queueLength 1 | same queue | wakes worker 2 from zero |
| 10 | measurement worker | R2 | S3 API | `.../sampled-frames/*` | the frames, read both as bytes and as keys |
| 11 | measurement worker | RunPod | `POST /v2/<endpoint>/run`, then `GET /status/<id>` | endpoint id, Bearer key | frame **keys**, not frames |
| 12 | RunPod handler | R2 | S3 API | `.../depth/*` | `scene.glb` and `result.npz`, returned as absolute signed URLs |
| 13 | measurement worker | Verge Studio | child process | `measurement/scripts/assess-grass.mjs --stdin --out <dir>` | JSON in, JSON out. Never an import |
| 14 | measurement worker | R2 | S3 API | `.../measurement/measurement-result-v1.json`, plus `assessment.json`, `report.html`, `SHA256SUMS` | the packet |
| 15 | measurement worker | Azure Queue | managed identity | `greenv-segment-measured-v1` | session, segment, run id, mock flag, measured-at |
| 16 | API | Neon | JDBC | `capture_segments.measurement_state = 'measured'` | plus object key, run id, mock flag, `measured_at` |
| 17 | dashboard or operator | API | HTTPS | `GET /v2/capture-sessions/{id}/segments/{n}/measurement` | the stored packet, byte for byte |

Three things follow from that table and are worth stating on their own.

**Frames reach the GPU by reference, never by upload.** A RunPod job body cannot hold a hundred
JPEGs, so the worker sends keys and the handler fetches them from the same bucket. That is why
the RunPod adapter refuses to start unless `GREENV_OBJECT_STORAGE_ADAPTER=s3`, and why the
endpoint needs an R2 credential of its own.

**Nothing crosses the `measurement/` boundary as code.** The worker spawns Verge Studio and reads
its JSON. That is what keeps the subtree round-trippable, and it is why the worker image builds
from the repository root rather than from its own directory.

**One segment is one directory.** Source, telemetry, frames, manifest, depth artifacts and the
measurement packet all live under
`capture-sessions/<sessionId>/segments/<segmentIndex padded to 8>/`. No join across two naming
schemes.

---

## What Terraform creates

- one Azure resource group, a Consumption Container Apps environment and a capped Log Analytics
  workspace;
- three Container Apps: the API with public ingress, the frame extractor and the measurement
  worker with none, all at minimum replicas 0;
- one LRS StorageV2 account holding **six queues**: `greenv-segment-extract-v2` and its poison
  queue, `greenv-segment-measure-v1`, `greenv-segment-measured-v1`, and a poison queue for each
  of those last two;
- three user-assigned managed identities with least-purpose queue roles. The API may send on the
  account and *process* the measured queue; the extractor is Queue Data Contributor on the
  account; the measurement identity gets three roles scoped to **one queue each**, so a worker
  misconfigured onto the extraction queue gets a 403 instead of deleting another worker's
  messages;
- one Neon PostgreSQL 17 project with pooled connections, autoscaling and whatever scale-to-zero
  interval the account plan permits;
- an RSA signing key for the API's token issuer, held as a Container Apps secret and published as
  a public PEM output;
- optional Cloudflare CNAME and TXT records, an Azure managed certificate, and three edge
  rulesets;
- optional Azure budget notifications at 50%, forecasted 80% and actual 100%;
- a separate bootstrap root that creates deletion-protected R2 state and application buckets.

## What Terraform deliberately does not create

| Not created | Why | What to do instead |
|---|---|---|
| **The depth GPU endpoint** | It bills for a worker's whole lifetime rather than for the seconds it computes, and `AGENTS.md` requires agreement in conversation before anything wakes it. The community provider also fails every apply that owns it: it plans `compute_type = "GPU"` and never reads the field back, so the apply ends in *Provider produced inconsistent result after apply*, on create and on update alike. Observed twice on 9 Sep 2026. `ignore_changes` cannot help, because the attribute is `computed`. | Create the endpoint once in RunPod's console, and name it in `depth_service_endpoint_id`. It holds no GPU at rest, so an unmanaged endpoint costs nothing and drifts in nothing but its own name. |
| **A container registry** | An always-on ACR Basic charge buys nothing for an MVP. | Public GHCR, or any OCI registry through `container_registry`. |
| **The R2 S3 credentials** | Cloudflare's provider can create a bucket but cannot mint the S3 key pair the Java AWS SDK and the Terraform backend need. | Create two pairs in the dashboard, each scoped to its own bucket. Never reuse the Cloudflare management token inside a container. |
| **Azure resource-provider registration** | Registration is subscription-wide; unregistering during a destroy could reach unrelated workloads. | `az provider register --namespace Microsoft.App --wait`, and the same for `Microsoft.OperationalInsights`, once. |
| **Application images** | Terraform deploys digests; it does not build them. | Publish to GHCR and pin `@sha256:` in `terraform.tfvars`. |
| **A retention rule on R2** | Nothing here decides how long a capture is worth keeping. | See *Nothing deletes capture artifacts* under Operations. Decide before a pilot runs for more than a few hours. |

---

## Before the first apply

In this order. Each step is a prerequisite for the one below it.

1. **Register the Azure providers** and confirm both print `Registered`:

   ```bash
   az provider register --namespace Microsoft.App --wait
   az provider register --namespace Microsoft.OperationalInsights --wait
   az provider show --namespace Microsoft.App --query registrationState --output tsv
   ```

2. **Mint a Cloudflare API token.** Phase one needs **Zone -> DNS -> Edit** and R2 bucket write.
   Phase two adds **Zone -> Cache Rules -> Edit** and **Zone -> Zone WAF -> Edit**. A token
   missing the latter fails the apply with `403` and Cloudflare error 10000, which reads as an
   authentication problem even though the token is valid.

3. **Get a Neon API key** for the organization the project will live in.

4. **Run the bootstrap** below to create the state and application buckets, then create one S3
   credential pair per bucket.

5. **Publish the images.** Three go to Container Apps; the fourth never does.

   ```bash
   docker build -f services/greenv-video-api/Dockerfile services/greenv-video-api
   docker build -f services/greenv-frame-extractor/Dockerfile services/greenv-frame-extractor
   # from the repository root: this image carries measurement/ and a baked model cache
   docker build -f services/greenv-measurement-worker/Dockerfile .
   # the depth image, for RunPod only
   docker build -t verge-da3:local measurement/server
   docker build --build-arg DEPTH_IMAGE=verge-da3:local services/greenv-depth-runpod
   ```

   Pin every one by digest. `latest` in `terraform.tfvars` makes the deployed revision
   unknowable.

6. **Create the RunPod endpoint**, only if this deployment measures. It needs a template carrying
   the depth image, 50 GB of container disk, and the R2 credentials as `AWS_ACCESS_KEY_ID` and
   `AWS_SECRET_ACCESS_KEY`. `services/greenv-depth-runpod/provision.mjs` creates that template
   idempotently by name; the endpoint itself is one form in the console. Allow more than one GPU
   type: an endpoint restricted to a single card waits for capacity that may not come, and every
   job the measurement worker sends while it waits times out after fifteen minutes and is
   resubmitted. Four segments became twelve queued jobs that way on 10 September 2026. Read
   `services/greenv-depth-runpod/README.md` before creating it.

---

## Environment variables

Terraform composes these in `locals.tf` from four maps: `common_environment`, shared by all three
workloads, one map per app, and a secret map that mounts Container Apps secrets by reference
rather than by value. Sharing the maps is what makes the three services agree by construction
instead of by review.

### Names that must agree across services

Read this before changing any queue or bucket setting. Every row here has been a real outage.

| Setting | API reads | Frame extractor reads | Measurement worker reads |
|---|---|---|---|
| Extraction queue | `GREENV_AZURE_QUEUE_NAME` | `GREENV_AZURE_QUEUE_NAME` | must never see it |
| Measurement queue | — | `GREENV_AZURE_MEASUREMENT_QUEUE_NAME` | `GREENV_AZURE_MEASUREMENT_QUEUE_NAME`, and `GREENV_AZURE_QUEUE_NAME` as "the queue I consume" |
| Measured queue | `GREENV_AZURE_MEASURED_QUEUE_NAME` | — | `GREENV_AZURE_MEASURED_QUEUE_NAME` |
| Poison queue | `GREENV_AZURE_MEASURED_POISON_QUEUE_NAME` | `GREENV_AZURE_POISON_QUEUE_NAME` | `GREENV_AZURE_MEASUREMENT_POISON_QUEUE_NAME` |
| Bucket | `GREENV_S3_BUCKET` | `GREENV_S3_BUCKET` | `GREENV_S3_BUCKET` |
| Bucket credentials | `GREENV_AWS_ACCESS_KEY` and `GREENV_AWS_SECRET_KEY` | the same two | the same two |
| Region | `GREENV_AWS_REGION` | `GREENV_AWS_REGION` | `GREENV_AWS_REGION` |

Two spellings of one queue is how two services end up talking past each other:

- The extractor's revision `ca-greenv-mvp-worker--measurement` failed to activate on 9 Sep 2026
  because Terraform set `GREENV_MEASUREMENT_QUEUE` while the Azure client binds
  `greenv.queue.azure-queue.measurement-queue` from `GREENV_AZURE_MEASUREMENT_QUEUE_NAME`.
  Container Apps never retries a failed revision, so it kept serving the image from 7 September,
  and every capture that day was extracted by the previous build.
- The measurement worker failed to start for the same class of reason a revision later, refusing
  with `GREENV_AZURE_MEASUREMENT_QUEUE_NAME and GREENV_AZURE_MEASURED_QUEUE_NAME are required`,
  while four announcements sat in the queue with `dequeueCount: 0`.

Both are fixed. The lesson stands: when a service reads a setting under a name of its own, add
that name; do not rename the other side.

### Shared by all three workloads

| Variable | Value | What it does |
|---|---|---|
| `GREENV_DATABASE_ADAPTER` | `jdbc` | Neon, rather than the H2 file the default would pick |
| `GREENV_DATABASE_URL` | Neon **pooled** host | normal application traffic |
| `GREENV_DATABASE_USER`, `GREENV_DATABASE_PASSWORD` | from Neon, the password as a secret | — |
| `GREENV_OBJECT_STORAGE_ADAPTER` | `s3` | R2 through the S3 API |
| `GREENV_S3_BUCKET` | `greenv-mvp-captures` | one bucket for the whole pipeline |
| `GREENV_S3_ENDPOINT` | `https://<account>.r2.cloudflarestorage.com` | — |
| `GREENV_S3_PATH_STYLE_ACCESS` | `false` | R2 uses virtual-host style |
| `GREENV_AWS_REGION` | `auto` | R2's region, not `us-east-1` |
| `GREENV_AWS_ACCESS_KEY`, `GREENV_AWS_SECRET_KEY` | secrets | both or neither. Half a pair is refused at startup by all three services |
| `GREENV_SEGMENT_QUEUE_ADAPTER` | `azure-queue` | picks the transport for every queue hop |
| `GREENV_AZURE_QUEUE_ENDPOINT` | the storage account's queue endpoint | with no connection string, the SDK uses the managed identity |
| `GREENV_AZURE_QUEUE_CREATE` | `false` | queues are infrastructure; a service must not declare them |
| `GREENV_AZURE_QUEUE_NAME` | **the queue this service consumes** | extraction for the API and worker 1, measurement for worker 2 |
| `MANAGEMENT_HEALTH_RABBIT_ENABLED` | `false` | there is no broker in the cloud deployment |
| `SERVER_ADDRESS` | `0.0.0.0` | the probes are not on loopback |
| `AZURE_CLIENT_ID` | the app's own identity | which of the three identities `DefaultAzureCredential` should use |

### API only

| Variable | Value | What it does |
|---|---|---|
| `PORT` | `8080` | ingress target and probe port |
| `GREENV_API_TOKEN` | secret; 48 generated characters, or `TF_VAR_api_bearer_token` | the shared MVP Bearer token every route except `/actuator/health` requires |
| `GREENV_ALLOWED_ORIGINS` | `api_allowed_origins`, comma joined | CORS. Empty disables it, which is what a phone-only deployment wants |
| `GREENV_JWT_PRIVATE_KEY` | secret, base64 PKCS#8 | signs access and refresh tokens. Base64 because a PEM's newlines travel through ARM, a revision template and a container environment, and any one of those normalising a line ending breaks the parse |
| `GREENV_JWT_ISSUER` | the custom hostname, else the Azure origin | what `/.well-known/jwks.json` is checked against |
| `GREENV_JWT_AUDIENCE` | `greenv-video-api` | — |
| `GREENV_ACCESS_TOKEN_TTL`, `GREENV_REFRESH_TOKEN_TTL`, `GREENV_CLIENT_CREDENTIALS_TTL` | `PT15M`, `P7D`, `PT4H` | — |
| `GREENV_COOKIE_SAME_SITE` | `Lax` | right while the dashboard and the API share a registrable domain. `None` would make it a third-party cookie, which Safari and Firefox already block |
| `SPRING_FLYWAY_URL`, `SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD` | Neon **direct** host | migrations do not go through the pooler |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | `5` | — |
| `GREENV_AZURE_MEASURED_QUEUE_NAME` | `greenv-segment-measured-v1` | set unconditionally. Empty here leaves the API deaf to measurements: no listener is created, the queue fills and nothing reads it |
| `GREENV_AZURE_MEASURED_POISON_QUEUE_NAME` | `greenv-segment-measured-poison` | named explicitly, because the API's own default pointed at a queue this stack never created |
| `GREENV_RABBITMQ_DYNAMIC` | `false` | — |

`GREENV_JWT_EPHEMERAL_KEY` is deliberately absent, and `tests/mvp.tftest.hcl` asserts its
absence. A deployment must never fall back to a signing key that dies with the process.

### Frame extractor only

| Variable | Value | What it does |
|---|---|---|
| `PORT` | `8081` | probes only; no ingress |
| `GREENV_AZURE_POISON_QUEUE_NAME` | `greenv-segment-extract-poison` | where an exhausted extraction message goes |
| `GREENV_AZURE_QUEUE_MAX_DEQUEUE_COUNT` | `5` | attempts before poisoning. This is CPU work, so five is cheap |
| `GREENV_AZURE_QUEUE_MAX_MESSAGES` | `1` | one segment at a time |
| `GREENV_QUEUE_VISIBILITY_SECONDS` | `queue_visibility_timeout_seconds`, default 300 | must stay above the worst measured FFmpeg attempt |
| `GREENV_LOCAL_POLLING_ENABLED` | `false` | the legacy whole-video filesystem poller must not be scheduled in the cloud |
| `GREENV_RABBITMQ_LISTENER_ENABLED` | `false` | — |
| `GREENV_MEASUREMENT_ENABLED` | `measurement_enabled`, default `false` | whether a finished segment is announced for measurement **at all**. This one setting is the difference between a deployment that costs nothing idle and one that spends on every capture |
| `GREENV_MEASUREMENT_QUEUE` | `greenv-segment-measure-v1` | the announcer's name for it |
| `GREENV_AZURE_MEASUREMENT_QUEUE_NAME` | the same queue | the Azure client's name for it. Both are required; see the divergence table |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | `2` | — |

### Measurement worker only

| Variable | Value | What it does |
|---|---|---|
| `PORT` | `8090` | `/health`, and the manual `POST /measurements`. No ingress |
| `GREENV_AZURE_QUEUE_NAME` | `greenv-segment-measure-v1` | overridden, not inherited: this worker must never be able to address the extraction queue |
| `GREENV_AZURE_MEASUREMENT_QUEUE_NAME` | the same queue | the name the Node worker actually reads. It refuses to start without it |
| `GREENV_AZURE_MEASURED_QUEUE_NAME` | `greenv-segment-measured-v1` | where a finished packet is announced. Also required at startup |
| `GREENV_AZURE_MEASUREMENT_POISON_QUEUE_NAME` | `greenv-segment-measure-poison` | optional. Without it a hopeless message is deleted rather than kept |
| `GREENV_MEASUREMENT_QUEUE_ENABLED` | `true` | poll the queue, rather than waiting for the HTTP trigger alone |
| `GREENV_MEASUREMENT_RESULT_ROUTING_KEY` | `greenv-segment-measured-v1` | Azure Queue has no exchange, so the routing key is the queue name |
| `GREENV_MEASUREMENT_ALLOW_MOCK` | `false`, and **not a variable** | without a reachable depth service the worker falls back to Verge Studio's fixture mock, which answers every request with the same reconstruction of an unrelated scene. A packet built that way pairs this road's frames with someone else's geometry. A mock run was mistaken for a real one on 2026-08-05 |
| `GREENV_MEASUREMENT_CLASSES` | `terrain,vegetation` | which Cityscapes labels count. `terrain` alone reads 0.000 m on a plant taped at 0.980 m |
| `GREENV_MEASUREMENT_OFFSET_SIDE` | `measurement_offset_side`, default `auto` | which side of the camera track the band goes to. Verge Studio's fixed side was the road's on every driven segment of 2026-09-13, and twelve of them measured nothing |
| `GREENV_MEASUREMENT_MIN_TRACK_M` | `measurement_min_track_m`, default 3 | a run whose camera moved less than this on the road plane is not measured: a phone still being mounted, a stopped car |
| `GREENV_MEASUREMENT_SCALE_ANCHOR` | `measurement_scale_anchor`, default `telemetry` | where the metric scale comes from: the GPS path length of the sampled frames, which the extractor writes into every manifest. DA3's per-clip scale ran from 0.78x to 1.86x against it on neighbouring segments of one drive |
| `GREENV_MEASUREMENT_CAMERA_HEIGHT_M` | `measurement_camera_height_m`, default unset | the lens's height above the road for the mount in use, when someone has taped it. Wins over the telemetry anchor |
| `GREENV_MEASUREMENT_MAX_HEIGHT_M` | `measurement_max_height_m`, default 3 | a point higher than this above the road is a crown, a wall top or a cut face and never enters a cell. `vegetation` captures trees with the tall grass; their height is what tells them apart |
| `GREENV_MEASUREMENT_CANOPY_EXTENT_M` | `measurement_canopy_extent_m`, default 2 | a cell whose extent above its own ground still exceeds this is reported as `canopy` with its numbers and counted in no aggregate |
| `GREENV_MEASUREMENT_GROUND_FALLBACK` | `measurement_ground_fallback`, default `true` | one coarser ground fit when the strict one finds no floor, as on a wet road; kept only if the camera stands a plausible height above it, and named `ground-fit-relaxed` in the packet |
| `GREENV_MEASUREMENT_DATUM` | `measurement_datum`, default `per-frame` | each frame measured against its own ground, then the median. The frames of a driven capture float 24–52 cm against each other, and the pooled datum read half the float as grass |
| `GREENV_MEASUREMENT_CANOPY_GAP_M` | `measurement_canopy_gap_m`, default 0.5 | a cell whose frames see that much empty air between the ground and the foliage is a crown, whatever its extent |
| `GREENV_MEASUREMENT_BAND_WIDTH_M` | `measurement_band_width_m`, default 5.5 | the band reaches this far from the detected road edge: five metres of verge, the mowing corridor, not the slope behind it |
| `GREENV_MEASUREMENT_EXCLUDE_NEAR` | `measurement_exclude_near`, default `fence,wall,pole,building` | pixels next to these classes are not measured: at the model's resolution the grass against a guardrail carries the rail's lower edge with it. `GREENV_MEASUREMENT_EXCLUDE_NEAR_PX` (default 1) is the radius in logit pixels |
| `GREENV_MEASUREMENT_SLOPE_RISE_M` | `measurement_slope_rise_m`, default 0.1 | the corridor ends where the embankment begins: two consecutive rises of each cell's own ground by more than this per half-metre cell mark the slope's foot, and everything beyond is reported as `slope`, aggregated nowhere |
| `GREENV_MEASUREMENT_STRUCTURE_FRAMES` | `measurement_structure_frames`, default 3 | a cell this many frames saw a fence, a wall, a pole or a building standing in is reported as `structure` and aggregated nowhere, whatever the other frames called it: the segmentation never learned a guardrail or a concrete barrier and calls it grass in some frames |
| `GREENV_MEASUREMENT_STRUCTURE_MODEL` | `measurement_structure_model`, default `ade20k-b4` | a second segmentation asked only what is not grass: the Cityscapes grass model was never taught a guardrail and calls a wet W-beam or a concrete barrier `terrain`; ADE20K knows `fence`, `railing`, `wall` and `bannister`. About 0.9 s a frame of CPU on top of the grass model's 0.14 s |
| `GREENV_MEASUREMENT_STRUCTURE_CLASSES` | `measurement_structure_classes`, default `fence,railing,wall,bannister,pole,column,signboard,building,house,streetlight,step` | the second model's classes that are a structure, in its own names: out of the grass mask with the exclusion margin, and into the cells the structure bar counts |
| `GREENV_MEASUREMENT_STRUCTURE_FLOOR` | `measurement_structure_floor`, default 0.4 | a pixel is a structure to the second model when the probability it gives those classes, summed, reaches this; null leaves Verge Studio's 0.5. 0.4 took a median strip in fog from a p90 of 0.16 m to 0.14 and changed nothing on a mown lawn |
| `GREENV_MEASUREMENT_STRUCTURE_MODEL_MASK` | `measurement_structure_model_mask`, default `always` | what the second model's structure pixels do besides voting on cells: `always` also leaves the grass mask with the margin, `band` leaves only the copy that places the band, `never` leaves the mask alone; `band` for a query model whose mask fades onto the grass beside a rail |
| `GREENV_MEASUREMENT_STRUCTURE2_MODEL` | `measurement_structure2_model`, default empty | a third segmentation standing beside the second, because no one model sees everything: the Vistas model that places the band and vetoes a rail has no class for a bush, which the Cityscapes `vegetation` mask measured as 1.81 m of grass on 2026-09-16; `ade20k-b4` calls that bush `tree` in 84-98% of its pixels and the mown strip beside it `grass`. Its classes vote on cells like the second model's. About 1.3 s a frame on top of the other two |
| `GREENV_MEASUREMENT_STRUCTURE2_CLASSES` | `measurement_structure2_classes`, default empty | the third model's classes a crew cannot cut, in its own names; `tree,palm` for the ADE20K model. `plant` is left out because nobody has measured what it makes of tall grass |
| `GREENV_MEASUREMENT_STRUCTURE2_FLOOR` | `measurement_structure2_floor`, default null | the third model's probability floor, as for the second; null leaves Verge Studio's 0.5 |
| `GREENV_MEASUREMENT_STRUCTURE2_MODEL_MASK` | `measurement_structure2_model_mask`, default `never` | what the third model's pixels do besides voting, with the same three answers as the second's. `never` on purpose: letting the tree pixels leave the mask pushed the band past the bush to the grass 12 m out on 2026-09-16 - right by the wrong route |
| `GREENV_MEASUREMENT_PAST_ENDS` | `measurement_past_ends`, default `drop` | what becomes of a point past either end of the camera track: `drop` leaves it out; `fold`, Verge Studio's default for a walked polyline, piles it onto the nearer end with the overshoot turned into distance from the road |
| `GREENV_INFER_ADAPTER` | `http` or `runpod` | which depth dialect |
| `GREENV_INFER_BASE_URL` | set only for `http` | the FastAPI service |
| `GREENV_INFER_RUNPOD_ENDPOINT_ID` | set only for `runpod` | the endpoint id, not a URL |
| `GREENV_INFER_TOKEN` | secret, either adapter | a Bearer header for FastAPI, an API key for RunPod. One credential, one thing to rotate |
| `GREENV_INFER_MAX_FRAMES` | `depth_max_frames`, default 112 | a second fence behind the extractor's own cap |
| `GREENV_MEASUREMENT_MAX_ATTEMPTS` | `2` | deliveries before a segment is poisoned. Two, where extraction gets five, because a retry here wakes the GPU again for the same segment |
| `GREENV_MEASUREMENT_VISIBILITY_SECONDS` | `measurement_queue_visibility_timeout_seconds`, default 1800 | the lease, matching the worker's own assessment timeout |

The last two carried the Java spellings until 11 September 2026 —
`GREENV_AZURE_QUEUE_MAX_DEQUEUE_COUNT` and `GREENV_QUEUE_VISIBILITY_SECONDS` — which this
container has no reader for. The attempt limit therefore fell back to its own default of three,
and four segments became twelve RunPod jobs on 10 September while the depth endpoint could not
start a worker.

### The depth endpoint's own environment

Set on the RunPod template, not by Terraform:

| Variable | Value |
|---|---|
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | the R2 credentials. The handler reads frames and writes artifacts itself |
| `GREENV_DEPTH_MAX_FRAMES` | unset on an L4. **Required on any other card**: the handler's frame ceilings were measured on an L4's 22.03 GiB usable, and it refuses a smaller device rather than being killed mid-run |
| `GREENV_SIGNED_URL_TTL_SECONDS` | `43200` |
| `VERGE_OUTPUT_BUCKET` | deliberately empty. Setting it sends artifacts to GCS, which this deployment has no credentials for |

---

## Validate locally

```powershell
terraform fmt -check -recursive
terraform init -backend=false
terraform validate
terraform test
```

The native tests use mocked providers. They need no credentials and contact nothing.

## Bootstrap remote state

Terraform cannot create the bucket it needs before backend initialization, so a small separate
root creates the state and application buckets with explicit names. Creating both together lets
the next step issue a different bucket-scoped credential for each.

```powershell
cd bootstrap
Copy-Item terraform.tfvars.example terraform.tfvars
$env:CLOUDFLARE_API_TOKEN = '<management-token>'
terraform init
terraform plan -out bootstrap.tfplan
terraform apply bootstrap.tfplan
cd ..
```

That apply is the first billable action. R2's free tier may cover it; account usage decides. Read
the plan before approving it.

Then move the bootstrap's own state into the state bucket:

```powershell
cd bootstrap
Copy-Item backend.r2.tf.example backend.tf
Copy-Item backend.r2.hcl.example backend.r2.hcl
$env:AWS_ACCESS_KEY_ID = '<state-bucket-access-key>'
$env:AWS_SECRET_ACCESS_KEY = '<state-bucket-secret-key>'
terraform init -migrate-state -backend-config=backend.r2.hcl
cd ..
```

And configure the main root with the same credential but a different state key:

```powershell
Copy-Item backend.r2.tf.example backend.tf
Copy-Item backend.r2.hcl.example backend.r2.hcl
$env:AWS_ACCESS_KEY_ID = '<state-bucket-access-key>'
$env:AWS_SECRET_ACCESS_KEY = '<state-bucket-secret-key>'
terraform init -reconfigure -backend-config=backend.r2.hcl
```

`backend.tf`, `backend.r2.hcl`, state and plans are ignored; `.terraform.lock.hcl` is tracked.
The backend uses an R2 lock object to reject concurrent writers. The state bucket has
`prevent_destroy`: deleting it needs an explicit code change, and a state backup first.

## Configure and deploy

Terraform state holds the generated Neon password, the API Bearer token, the token signing key
and the R2 keys, so the state credential must stay narrowly scoped. Secrets go in the
environment, never in a file.

```powershell
Copy-Item terraform.tfvars.example terraform.tfvars
$env:CLOUDFLARE_API_TOKEN = '<management-token>'
$env:NEON_API_KEY = '<neon-api-key>'
$env:TF_VAR_r2_access_key_id = '<application-bucket-access-key>'
$env:TF_VAR_r2_secret_access_key = '<application-bucket-secret-key>'

terraform plan -out mvp.tfplan
terraform show mvp.tfplan
terraform apply mvp.tfplan
```

For a private GHCR package, export the registry object rather than writing the token into
`terraform.tfvars`. A `.tfvars` value takes precedence over `TF_VAR_*`, so defining it in both
places silently ignores the environment:

```bash
read -rsp "GHCR token with read:packages: " GHCR_READ_TOKEN
echo
export TF_VAR_container_registry="{\"server\":\"ghcr.io\",\"username\":\"Matomomitsu\",\"password\":\"$GHCR_READ_TOKEN\"}"
unset GHCR_READ_TOKEN
```

Set `api_hostname = null` and `cloudflare_zone_id = null` to deploy without a custom domain.

### `deployment_revision` changes on every deploy

A Container Apps revision suffix is immutable. Once `ca-greenv-mvp-worker--measure-queues` has
existed, applying that name again deploys nothing at all, even with a new image digest. Worse,
Container Apps never re-attempts a revision it marked `ActivationFailed`, so a workload that died
on a bad credential or a missing variable stays dead after the fix, and a further apply reports
no changes because the declared state already matches. `az containerapp revision restart` does
not help either: at `min_replicas = 0` the platform has no reason to try.

Change `deployment_revision` and apply. It rolls a fresh revision of all three workloads and
nothing else.

```bash
az containerapp revision list -n ca-greenv-mvp-worker -g rg-greenv-mvp -o table
```

### The variables worth knowing

| Variable | Default | Note |
|---|---|---|
| `api_image`, `worker_image`, `measurement_worker_image` | — | pin by digest |
| `depth_image` | a pinned digest | never pulled by Azure; it reaches RunPod through the template |
| `deployment_revision` | `null` | change it on every deploy |
| `measurement_enabled` | `false` | the spend switch |
| `depth_service_adapter` | `http` | or `runpod` |
| `depth_service_endpoint_id` | `null` | naming one keeps Terraform out of the endpoint |
| `depth_max_frames` | `112` | Verge Studio's graded setting, and the extractor's cap |
| `measurement_worker_max_replicas` | `1` | a 112-frame run fills an L4 to 99.95%; a second replica cannot get a GPU |
| `queue_visibility_timeout_seconds` | `300` | the extraction lease |
| `measurement_queue_visibility_timeout_seconds` | `1800` | the measurement lease. See *Known gaps* |
| `api_max_replicas`, `worker_max_replicas` | `3`, `4` | — |
| `api_allowed_origins` | `[]` | exact origins, port included |
| `cloudflare_proxy_enabled`, `restrict_api_origin_to_cloudflare` | `false` | phase two |
| `budget_amount`, `budget_contact_emails` | `30`, `[]` | the subscription's billing currency, not necessarily USD |

---

## Automatic measurement is off by default

`measurement_enabled = false` keeps the third app deployed and idle at zero replicas, which costs
nothing: the extractor simply never announces a finished segment. The queues, the consumer and
the role assignments exist either way.

Turning it on is what buys GPU time. One segment is one depth run, and that service bills for the
machine's whole lifetime: about a minute of work and up to fifteen minutes of billed idle for a
lone segment, with back-to-back segments from one drive riding a single warm instance. A drive
that uploads continuously is affordable; one segment an hour is not. The numbers and the licence
restriction are in [`docs/AUTOMATIC-HEIGHT.md`](../docs/AUTOMATIC-HEIGHT.md), and
[`AGENTS.md`](../AGENTS.md) requires the user's agreement in conversation before anything wakes
it.

Terraform refuses to enable measurement without a depth service, because announcing segments with
nowhere to send them fills the poison queue with messages that each cost GPU time to fail:

```hcl
measurement_enabled    = true
depth_service_base_url = "https://verge-da3.example.com"
```

```hcl
measurement_enabled       = true
depth_service_adapter     = "runpod"
depth_service_endpoint_id = "<runpod endpoint id>"
```

Either adapter takes its credential the same way:

```bash
export TF_VAR_depth_service_token='<depth service bearer token or RunPod API key>'
```

A backfill or a re-measure runs through the replica, after agreeing the spend:

```bash
az containerapp exec --resource-group "$(terraform output -raw resource_group_name)" \
  --name "$(terraform output -raw measurement_worker_container_app_name)" \
  --command "sh"
```

`terraform output measurement_state` says which of the two states the configuration is in and,
when enabled, which depth service will answer.

---

## The custom hostname takes two applies

Azure issues the managed certificate by reaching the Container Apps origin directly, and an
intermediate proxy can block that. So the DNS record starts unproxied, the certificate is issued,
and only then does the edge protection go on. Doing both in one apply is what leaves the hostname
resolving to an origin that resets the TLS handshake.

**Phase one, DNS-only, so the certificate can be issued.** Keep these at their defaults:

```hcl
cloudflare_proxy_enabled          = false
restrict_api_origin_to_cloudflare = false
cloudflare_api_waf_enabled        = false
cloudflare_api_rate_limit_enabled = false
```

```bash
terraform plan -out=bootstrap.tfplan
terraform show bootstrap.tfplan
terraform apply bootstrap.tfplan
```

That creates the DNS-only CNAME, the `asuid` ownership TXT record, the Azure custom domain, the
managed certificate and its binding, in that order: Azure refuses to issue a certificate for a
hostname that is not registered yet. Nothing else, so the Cloudflare token needs only
**Zone -> DNS -> Edit** for this phase.

**This apply needs the Azure CLI signed in on the machine running Terraform**, because the
binding is a `local-exec`. The provider cannot do it:
`container_app_environment_certificate_id` parses only an environment certificate id
(`.../certificates/<name>`) and rejects a managed certificate id
(`.../managedCertificates/<name>`) at plan time. Both certificate fields are therefore in
`ignore_changes`, as the provider documents, and `terraform_data.api_certificate_binding` runs
the one command that completes the hostname:

```bash
az containerapp hostname bind --resource-group rg-greenv-mvp --name ca-greenv-mvp-api \
  --hostname greenvapi.matomomitsu.com --environment cae-greenv-mvp-eqvs07 \
  --certificate mc-greenvapi-matomomitsu-com
```

Binding is create-or-update, so repeating it is safe; Terraform re-runs it only when the
certificate or the container app is replaced. Where the CLI is absent, such as CI using `ARM_*`
service-principal variables, run that command by hand once instead.

Confirm the binding before going further:

```bash
az containerapp hostname list --resource-group rg-greenv-mvp --name ca-greenv-mvp-api --output table
curl -I https://greenvapi.matomomitsu.com/actuator/health
```

`bindingType` must read `SniEnabled`. A TLS handshake that resets means the certificate is not
bound: Azure's ingress rejects the SNI for a hostname it has registered but has no certificate
for, which looks like a network fault rather than a configuration one. Wait and repeat; do not
move on.

### How the certificate is issued

`azurerm_container_app_environment_managed_certificate.api` requests it and
`azurerm_container_app_custom_domain.api` binds it. Azure issues and renews it for free,
validating ownership from public DNS: the `asuid.<hostname>` TXT record proves ownership and the
CNAME must resolve to the Container App.

- **The record must be DNS-only while the certificate is issued.** A proxied record answers with
  Cloudflare's addresses, and CNAME validation has nothing to match. That is the whole reason the
  rollout is split in two.
- **Renewal validates again**, so the constraint applies for the certificate's whole life. If
  renewal fails behind the proxy, set `cloudflare_proxy_enabled` back to `false` long enough for
  Azure to renew.

The provider documents putting `certificate_binding_type` and
`container_app_environment_certificate_id` in `ignore_changes` when a managed certificate is
used, because Azure sets them asynchronously. This stack sets them explicitly instead. Following
that advice is what left the hostname registered but unbound for weeks: with both ignored and no
certificate resource, nothing ever bound anything. If Azure does churn those fields between
applies, add `ignore_changes` back **after** the first successful bind, not before it.

**Phase two, proxy the record and close the origin.** Only after the certificate is bound:

```hcl
cloudflare_proxy_enabled          = true
restrict_api_origin_to_cloudflare = true
cloudflare_api_waf_enabled        = true
cloudflare_api_rate_limit_enabled = true
```

```bash
terraform plan -out=edge-security.tfplan
terraform show edge-security.tfplan
terraform apply edge-security.tfplan
```

Then check that the public hostname works and the origin no longer answers anyone else:

```bash
curl -i https://greenvapi.matomomitsu.com/actuator/health

API_ORIGIN="$(terraform output -raw api_origin_url)"
curl -i "$API_ORIGIN/actuator/health"
```

The custom domain must answer; the direct origin must be rejected. If both answer, the origin
restriction did not apply and the API is still reachable around Cloudflare.
`terraform output edge_security_state` names the phase the configuration is in.

### What the edge does and does not do

- **`proxied = true` alone protects nothing.** The Azure origin FQDN stays public and resolvable.
  The configuration is complete only when the record is proxied *and*
  `restrict_api_origin_to_cloudflare` has allowed only Cloudflare's ranges on the Container App.
  Container Apps denies every other address once any Allow rule exists.
- **The Cloudflare proxy is not authentication.** Every route except `/actuator/health` still
  requires a credential, however the request arrives.
- **Never put a Cloudflare service token in the mobile app.** A shipped client cannot hold a
  secret; anyone who unpacks the build reads it.
- **The rules arrive with the proxy, not before it.** All three rulesets are created only when
  `cloudflare_proxy_enabled` is true, which keeps phase one to DNS permissions alone.
- **Cloudflare allows one entry-point ruleset per zone phase.** If the zone already has one in
  `http_request_cache_settings`, `http_request_firewall_custom` or `http_ratelimit`, import it
  (`terraform import cloudflare_ruleset.api_cache_bypass zones/<zone id>/<ruleset id>`) and add
  the rule inside it rather than creating a second, conflicting entry point. `matomomitsu.com`
  already has one in `http_request_firewall_custom`, id `f020eb3f1cbd4b02b5fe9b8dc5e8f5f0`, which
  is why `cloudflare_api_waf_enabled` stays false there: creating a second would fail the apply.
  List them before turning any of the three on:

  ```bash
  curl -s -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
    "https://api.cloudflare.com/client/v4/zones/<zone id>/rulesets" |
    python3 -c "import json,sys; [print(r['phase'], r['kind'], r['id']) for r in json.load(sys.stdin)['result']]"
  ```
- **WAF and rate limiting depend on the Cloudflare plan.** Both are off by default; the custom
  rules here avoid Managed Rules, which need a paid entitlement.
- **`manage_cloudflare_zone_security_settings` is zone-wide.** Strict SSL, a TLS 1.2 minimum and
  TLS 1.3 would apply to every hostname in the zone, not just the API. Off by default for that
  reason.
- **To recreate the custom domain from scratch**, set the flags back to the phase-one values,
  apply, let Azure reissue the certificate, then repeat phase two.

`api_allowed_origins` is a separate concern. It fills `GREENV_ALLOWED_ORIGINS`, which is what
lets a browser page read an API response at all. Leave it empty for a phone-only deployment.

### Regions and transfer

The examples use `eastus2`, Neon `aws-us-east-2` and the R2 bootstrap hint `enam`; this
deployment uses `brazilsouth` and `aws-sa-east-1`. Keep the three data-plane services close, and
override all three together.

R2 does not charge direct egress, but Container Apps can charge Azure internet outbound for the
bytes the API and workers send to R2: uploaded video forwarded by the API, frames written by
worker 1, artifacts read by worker 2. Measure those bytes in the pilot. R2 removes one side of
the transfer bill, not both.

---

## Verify the deployment

Take these in order. Each one tells you a different hop is alive.

**1. The API answers and the migrations ran.**

```powershell
$api = terraform output -raw api_public_url
$apiToken = terraform output -raw api_bearer_token
Invoke-RestMethod "$api/actuator/health"
```

Do not enqueue work until health is `UP` and the Flyway rows exist. Applied migrations today are
`V1`, `V2`, `V3`, `V6` and `V7`. `V4` and `V5` belong to the unmerged dashboard branch and must
be renumbered before that merge, or Flyway fails at boot.

**2. Credentials behave.** Health is public, a missing or invalid token is rejected, and a valid
one reaches the application. The script creates and modifies nothing:

```bash
export API_URL="$(terraform output -raw api_public_url)"
export GREENV_API_TOKEN="$(terraform output -raw api_bearer_token)"
bash ../services/greenv-video-api/scripts/test-api-auth.sh
```

**3. All three apps have a revision that activated.**

```bash
rg=$(terraform output -raw resource_group_name)
for app in api worker measure; do
  az containerapp revision list -g "$rg" -n "ca-greenv-mvp-$app" \
    --query "[].{name:name, active:properties.active, state:properties.runningState}" -o table
done
```

`ActivationFailed` on the newest revision means the platform is still serving the previous one.
Read the container's own log before changing anything: both activation failures this stack has
had named the exact missing variable in their first log line.

```bash
az containerapp logs show -g "$rg" -n ca-greenv-mvp-measure --type console --tail 100
```

**4. One real capture.**

```bash
cd ../apps/mobile
flutter run \
  --dart-define=GREENV_API_URL="$API_URL" \
  --dart-define=GREENV_API_TOKEN="$GREENV_API_TOKEN"
unset GREENV_API_TOKEN
```

**5. Follow it through, in this order.** The signals appear in the order the pipeline runs, so
the first one missing is the hop that broke.

| Stage | Where to look | What worked looks like |
|---|---|---|
| Upload | R2 | `capture-sessions/<id>/segments/00000000/source.mp4` and `telemetry.json` |
| Extraction queued | queue | `greenv-segment-extract-v2` briefly non-empty, then empty |
| Frames | R2 | `sampled-frames/`, `segment-manifest-v2.json`, `frame-metadata-v2.json` |
| Extraction recorded | Neon | `capture_segments.state`, `frame_count` non-null |
| Announced | queue | a message on `greenv-segment-measure-v1` |
| Measurement started | worker log | `event: "downloading"`, then `event: "inferring"` with `service: runpod:<id>` |
| Depth ran | worker log | no `depth_inference_failed`; `event: "measuring"` follows |
| Packet written | R2 | `measurement/measurement-result-v1.json`, plus `assessment.json`, `report.html`, `SHA256SUMS` |
| Announced back | queue | a message on `greenv-segment-measured-v1`, then empty |
| Recorded | Neon | `capture_segments.measurement_state = 'measured'`, `measured_at` set |

A `measurement_state` still NULL with the packet present in R2 means hop 15 or 16 broke: the
worker wrote the packet but the API did not consume the announcement.

Both poison queues should be empty. A poison message is retained for inspection; moving or
deleting one is an operational decision, not something Terraform does.

---

## Operations and recovery

- `terraform plan` before every apply, and apply a saved plan, so the reviewed graph is the
  deployed graph.
- **Each worker's queue scale rule always shows as a change, and that is expected.** Azure stores
  it as a native `azureQueue` rule and the provider reads that back as `azure_queue_scale_rule`,
  which never matches the declared `custom_scale_rule`. The declaration cannot change:
  `azure_queue_scale_rule` requires an `authentication` block with a storage connection string
  and has no identity option, so `custom_scale_rule` with `custom_rule_type = "azure-queue"` is
  the only form that carries a managed identity. Applying it rewrites the same values, identity
  included. Do not "fix" the diff by switching blocks; that would replace the identity with a
  connection string.
- **A revision stuck at `ActivationFailed` needs a new revision, not a retry.** See
  `deployment_revision` above. Verify the registry credential before rolling, or the new revision
  fails the same way:

  ```bash
  tok=$(printf '%s' "$TF_VAR_container_registry" | sed -E 's/.*"password" *: *"([^"]*)".*/\1/')
  curl -s -o /dev/null -w "GHCR: HTTP %{http_code}\n" -u "Matomomitsu:$tok" \
    "https://ghcr.io/token?scope=repository:matomomitsu/greenv-video-api:pull&service=ghcr.io"
  ```
- A failed apply does not roll back what it already created. Inspect `terraform state list`,
  correct the configuration and save a new plan; never reuse the plan from a failed apply.
- The Neon project deliberately omits `suspend_timeout_seconds`. Free accounts reject attempts to
  modify that interval, so Neon applies whatever the account plan allows.
- Rotate the R2 application key by updating the two sensitive variables; Container Apps creates
  new revisions with the changed secret. Rotate the MVP API token with `TF_VAR_api_bearer_token`,
  rebuild the pilot mobile app and apply a reviewed plan. Existing builds stop uploading as soon
  as the new revision takes traffic.
- A queue message can be delivered more than once. Database generation checks and object
  checksums are the idempotency boundary. The measurement worker adds one of its own: a packet
  whose `sourceGeneration` matches the manifest is returned rather than recomputed, because
  recomputing means paying for the GPU twice for the same answer.
- Keep the extraction visibility timeout above the measured worst FFmpeg attempt. The default is
  300 seconds and should change from evidence, not guesswork.
- The measurement lease is much longer, 1,800 seconds, matching the worker's assessment timeout.
  A measurement is a depth run plus a CPU pass, minutes rather than seconds, and a lease that
  expires mid-run redelivers the message and pays for the same segment's GPU time twice.
- **Every message in the measurement poison queue cost GPU time to get there.** Read it before
  re-enqueueing anything, and confirm the depth service is healthy first: a broken endpoint turns
  a drive's worth of segments into a drive's worth of billed failures.
- **Nothing deletes capture artifacts.** R2 has no lifecycle rule, `expires_at` is recorded but
  no scheduler acts on it, and worker 1 keeps the source segment so a later stage can re-sample
  it. Roughly 8.5 MB per ten-second segment, about 3 GB per hour of driving, against R2's 10 GB
  free tier. Each measured segment adds a packet under `<prefix>/measurement/`, which has not
  been sized here. Agree a retention rule before a pilot runs for more than a few hours.
- The capture and state buckets use `prevent_destroy`. Retiring the environment means exporting
  the data, removing that guard in a reviewed change, and a fresh plan before deletion.
- The API uses Neon's pooled endpoint for JDBC and its direct endpoint for Flyway. The first
  application start runs the migrations.

## Known gaps

- **Every endpoint setting in `runpod.tf` is inert while `depth_service_endpoint_id` is set.**
  `depth_gpu_type_ids`, `depth_idle_timeout_seconds`, the worker counts, the execution timeout and
  Flashboot are attributes of `runpod_endpoint.depth`, whose `count` is 0. The endpoint that
  actually answers was created by hand, so its GPU, its idle timeout and its container disk are
  whatever the console form said, and they can differ from what this configuration declares.
  Read them from the RunPod API rather than from here:

  ```bash
  curl -s -H "Authorization: Bearer $TF_VAR_depth_service_token" \
    https://rest.runpod.io/v1/endpoints/<endpoint id>
  ```

  On 10 September 2026 that endpoint declared `gpuTypeIds: ["NVIDIA L4"]` and the worker RunPod
  supplied showed in the console as a `PRO 6000 MIG 24GB`. The handler's frame ceilings were
  measured on an L4 and it refuses a device reporting less, so every job it received was refused
  before inference.

- **`GREENV_INFER_TIMEOUT_MS` is not a variable.** The worker gives a depth job fifteen minutes
  end to end, and a job that spends them queued is retried with a *new* job. An endpoint that
  cannot get capacity therefore accumulates work rather than failing fast: four segments became
  twelve queued jobs that way. The attempt limit above now caps it at eight, but the deadline
  itself is still the worker's built-in default.

Two gaps recorded here on 10 September were fixed on the 11th: the measurement worker's attempt
limit and lease are now set under the names it reads, and the queue the API moves an unrecordable
announcement to now exists, with the grant that lets it write there.

## Authentication inputs

| Provider or runtime | Recommended local input | CI input |
|---|---|---|
| Azure provider | `az login` | `ARM_CLIENT_ID`, `ARM_CLIENT_SECRET`, `ARM_TENANT_ID`, `ARM_SUBSCRIPTION_ID` |
| Cloudflare provider | `CLOUDFLARE_API_TOKEN` | masked secret with R2 and DNS resource scope |
| Neon provider | `NEON_API_KEY` | masked organization API key |
| R2 Terraform backend | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | bucket-scoped masked secrets |
| R2 application adapter | `TF_VAR_r2_access_key_id`, `TF_VAR_r2_secret_access_key` | application-bucket-scoped masked secrets |
| MVP API Bearer token | generated when omitted, or `TF_VAR_api_bearer_token` | masked secret, at least 32 random characters |
| Depth service (GPU) | `TF_VAR_depth_service_token` | masked secret; anyone holding it can spend GPU time |
| RunPod management | `TF_VAR_runpod_api_key`, or `RUNPOD_API_KEY` | needed only to provision a template |

Do not pass secret values with `-var`: command history retains them.
