# GreenV Frame Extractor

The Frame Extractor is the CPU worker for mobile segments and legacy whole videos. For each mobile
segment it verifies both inputs, probes every encoded presentation timestamp, associates eligible
GNSS and inertial samples with every frame, extracts representative JPEGs across the full segment,
checks every output and publishes the manifest last. It keeps the source MP4.

It does not identify grass, estimate height, or call the depth model. Those are downstream stages
that consume its versioned manifest.

## Two frame counts, and only one of them is images

A manifest reports both, and confusing them makes the sampling look far denser or far sparser than
it is.

- **`encodedFrameCount`** is every frame the camera encoded, read from the presentation timestamps
  by ffprobe. No image is produced for these. Each one becomes a row in `frame-metadata-v2.json`
  carrying its timestamp, the GNSS and motion samples eligible for it, and a quality stamp. It
  tracks the camera: a 30 fps phone gives about 300 rows for a ten-second segment.
- **`sampledFrames`** is the JPEGs actually written to object storage, and it is the number that
  matters downstream.

Sampling is by **distance travelled**, in groups. See "Groups, not one long clip" below. The
budget is still Verge Studio's
numbers rather than this worker's. The depth model recovers geometry by comparing many views of one
scene, so the frame rate is the accuracy knob, and `measurement/MEASUREMENTS.md` grades 112 frames
at 504 px as the best setting tried. The cap keeps a long segment off the GPU's memory ceiling; an
L4 fits 0.0700 GiB per frame plus 9.39 GiB and fails above 144 frames. When the cap binds,
`SamplingPlanner` lowers the rate rather than truncating the clip, so the frames still span the
whole segment — never "N frames spread across it".

A ten-second segment therefore publishes about 100 JPEGs. At 1024 px that is roughly 5 MB, against
about 3.4 MB for the MP4 they came from, so the frames now cost more storage than the source.

## Groups, not one long clip

A ten-second segment is an **upload** bound, not an analysis unit. At 100 km/h it covers 278 m,
which is many stretches of verge rather than one scene, so the worker cuts the distance travelled
into groups and treats each as its own reconstruction.

**The target is 10 m, and that is a test setting.** Verge Studio's graded evidence covers camera
paths of roughly 14–25 m, so the planner never plans a group longer than 25 m and marks one
`withinGradedEnvelope` only when it is at least 14 m long and holds at least 64 frames. A 10 m
target sits below that on purpose: ten seconds on foot covers 8 to 14 m, and at a 20 m target a
walk could not exercise the pipeline at all. Ten metres makes a walk testable and gives up the
graded label to do it. Put it back to 20 m before any measurement is meant to be trusted.

Frames inside a group are spaced by distance, which is what the depth model actually depends on.
Sampling by time crowds frames together wherever the vehicle is slow — pulling away from a light
puts half of them in the first twenty metres — and spreads them thin where it is fast.

At a 10 m target, from a 30 fps camera and its ~300 encoded frames per segment:

| km/h | distance / 10 s | groups | group length | frames / group | spacing | inside the graded range |
|---:|---:|---:|---:|---:|---:|---|
| 5, walking | 14 m | 1 | 13.9 m | 112 | 0.13 m | no, by 10 cm of path |
| 20 | 56 m | 6 | 9.3 m | 50 | 0.19 m | no |
| 30 | 83 m | 8 | 10.4 m | 37 | 0.29 m | no |
| 40 | 111 m | 11 | 10.1 m | 27 | 0.39 m | no |
| 60 | 167 m | 17 | 9.8 m | 17 | 0.61 m | no |
| 100 | 278 m | 28 | 9.9 m | 10 | 1.10 m | no |

So at this setting essentially nothing is graded: the only band that reaches the envelope is a
path of 14 to 15 m, where the planner still makes one group and 112 frames fit inside it. Every
group records `withinGradedEnvelope` either way, so a consumer can always tell which side of the
line it is on.

Two of those limits belong to the camera and the vehicle rather than to the code. **Above roughly
34 km/h a group cannot hold 64 frames**, because a 30 fps camera did not record them that close
together; more frames per second would not help until the segment is re-cut. And the frame budget
— 112 JPEGs for the whole segment, not per group — binds long before the camera does.

A vehicle or a person that never moved produces no groups and publishes no frames. The floor is
3 m, and **which distance it is compared against follows how that distance was measured**:

- **The phone reported speed.** The path is the integral of Doppler-derived speed, which never
  reads a position, so the fixes' horizontal accuracy says nothing about how well it was measured.
  The floor stays at 3 m. Scaling it by accuracy instead demanded 25 m in ten seconds — 9 km/h —
  and refused 8.8 m of walking the phone had measured perfectly well (segment `01a08885` #2,
  9 September 2026).
- **The phone reported no speed.** The path is then a sum of great-circle hops between positions,
  which accumulates fix noise instead of cancelling it: at 5 m accuracy a parked phone sums tens
  of metres that never happened. So the comparison uses **net displacement**, and the floor scales
  with the fixes' own accuracy at twice the median horizontal accuracy. A browser's IP-derived
  50 km fix therefore stands at a 100 km floor, which no ten-second segment reaches at any speed;
  "did not move" is the only output the arithmetic permits there, and the manifest says that
  rather than calling it a finding.

The manifest records **every** group, but publishes JPEGs only for as many as the frame budget
allows. Publishing all of them would triple storage and triple a GPU bill that already runs to about
four GPU-hours per hour driven. The source is kept, so a group that was only planned can be
materialised later from its recorded frame indices.

## The source segment is kept

The worker used to delete the source MP4 as its last act, and `sourceDeleted` in the manifest
recorded that. It no longer does, and that field is now always `false`.

Sampled frames are a derivative at one rate and one resolution. The measurement stage that consumes
them may want another — a different frame budget for the GPU's memory ceiling, or a different long
edge — and it cannot ask for it once the only copy is gone. Keeping a 3.4 MB segment is also
cheaper than the 5 MB of frames already kept from it.

**Nothing expires capture objects.** `expires_at` is written to the database and returned by the
API, but no scheduler acts on it, and the API's `TransientCleanupService` covers only legacy v1
jobs. R2 has no lifecycle rule either. Deleting the source was the only bound on growth, so storage
now grows with every capture until a retention rule exists. At roughly 8.5 MB per ten-second
segment, an hour of driving is about 3 GB, against R2's 10 GB free tier.

The legacy whole-video path resolves every artifact to a path under `GREENV_PIPELINE_ROOT`, so it
exists only while `GREENV_OBJECT_STORAGE_ADAPTER` is `local`. `@ConditionalOnLocalPipeline` removes
that controller, handler and service together with the local store they depend on; a cloud
deployment runs the segment path alone. Wiring them unconditionally is what made the deployed
worker fail to start with "No qualifying bean of type LegacyPipelineStore", which left every
uploaded segment sitting in `queued` — `CloudProfileApplicationTest` now boots the cloud adapter
set to keep that from returning.

## Code map

| Path | Responsibility |
|---|---|
| `src/main/java/.../port/` | Inbound use cases and replaceable state, storage, queue, workspace and media contracts |
| `src/main/java/.../task/` | RabbitMQ, SQS, Azure Queue and Service Bus input/output adapters |
| `src/main/java/.../service/` | Validation, ffprobe association, FFmpeg extraction and publication |
| `src/main/java/.../storage/` | JDBC, local/S3/Azure object storage and ephemeral workspace adapters |
| `src/main/resources/contracts/` | Queue, telemetry and manifest JSON Schemas copied from the API |
| `src/test/` | Unit, contract and real-FFmpeg integration coverage |

## Requirements

- Docker Engine with Compose for the supported full stack.
- Java 21, FFmpeg and ffprobe for a native worker run.
- PostgreSQL, RabbitMQ and a compatible Video API producer for the mobile path.

On Ubuntu or WSL Ubuntu:

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk ffmpeg
java -version
ffmpeg -version
ffprobe -version
```

No machine-wide Gradle installation is needed. The checked-in wrapper downloads the pinned
Gradle version on first use.

## Run the complete local stack

From the repository root:

```bash
docker compose up -d --build postgres rabbitmq video-api frame-worker
docker compose ps
curl -fsS http://127.0.0.1:8081/actuator/health
docker compose logs -f frame-worker
```

Stop containers without deleting queued data:

```bash
docker compose down
```

Reset PostgreSQL, RabbitMQ and every capture artifact:

```bash
docker compose down -v
```

`down -v` permanently deletes the local stack's named volumes. Preserve any required capture
first.

## Configuration

| Setting | Environment variable | Native default |
|---|---|---|
| Database adapter | `GREENV_DATABASE_ADAPTER` | `jdbc` |
| Object-storage adapter | `GREENV_OBJECT_STORAGE_ADAPTER` | `local` |
| Segment-queue adapter | `GREENV_SEGMENT_QUEUE_ADAPTER` | `rabbitmq` |
| Bind address/port | `SERVER_ADDRESS`, `PORT` | `127.0.0.1:8081` |
| Pipeline root | `GREENV_PIPELINE_ROOT` | OS temp directory under `greenv-pipeline` |
| FFmpeg executable | `GREENV_FFMPEG` | `ffmpeg` |
| ffprobe executable | `GREENV_FFPROBE` | `ffprobe` |
| Maximum media duration | `GREENV_MAX_DURATION_SECONDS` | 300 seconds |
| Maximum attempts | `GREENV_MAX_ATTEMPTS` | 3 |
| Legacy poll delay | `GREENV_POLL_DELAY_MS` | 1000 ms |
| Legacy local polling | `GREENV_LOCAL_POLLING_ENABLED` | `true` |
| Database URL | `GREENV_DATABASE_URL` | local H2 file |
| Database user/password | `GREENV_DATABASE_USER`, `GREENV_DATABASE_PASSWORD` | `sa`, empty |
| RabbitMQ address | `GREENV_RABBITMQ_HOST`, `GREENV_RABBITMQ_PORT` | `127.0.0.1:5672` |
| RabbitMQ credentials | `GREENV_RABBITMQ_USER`, `GREENV_RABBITMQ_PASSWORD` | `guest`, `guest` |
| Rabbit listener | `GREENV_RABBITMQ_LISTENER_ENABLED` | `false` |
| Exchange/queue/key | `GREENV_SEGMENT_EXCHANGE`, `GREENV_SEGMENT_QUEUE`, `GREENV_SEGMENT_ROUTING_KEY` | `greenv.capture`, `greenv.segment.extract.v2`, `segment.extract.v2` |

### Cloud adapter selection

Use the same `GREENV_OBJECT_STORAGE_ADAPTER` and `GREENV_SEGMENT_QUEUE_ADAPTER` values as the API.

| Port | Adapter value | Worker-specific behavior |
|---|---|---|
| Object storage | `local` | Reads and writes below `GREENV_PIPELINE_ROOT` |
| Object storage | `s3` | AWS S3 or an S3-compatible endpoint such as R2 |
| Object storage | `azure-blob` | Azure Blob through connection string or managed identity |
| Segment queue | `rabbitmq` | Spring AMQP listener plus retry publisher |
| Segment queue | `sqs` | Long polling plus delete-after-success; supports standard and FIFO queues |
| Segment queue | `azure-queue` | Visibility lease, delete-after-success and application poison queue |
| Segment queue | `azure-service-bus` | Peek-lock receive, complete-after-success and abandon-on-failure |

S3 and SQS use `GREENV_AWS_REGION`, optional endpoint overrides and either explicit
`GREENV_AWS_ACCESS_KEY`/`GREENV_AWS_SECRET_KEY` values or the AWS default credential chain. Azure
Blob and Queue use `GREENV_AZURE_STORAGE_CONNECTION_STRING`, or their endpoint plus
`DefaultAzureCredential`. Service Bus uses a connection string or
`GREENV_AZURE_SERVICE_BUS_NAMESPACE`. The API README lists all shared variable names.

Cloud polling is controlled by `GREENV_CLOUD_QUEUE_POLL_DELAY_MS`. SQS additionally uses
`GREENV_SQS_MAX_MESSAGES`, `GREENV_SQS_WAIT_SECONDS` and `GREENV_QUEUE_VISIBILITY_SECONDS`.
Azure Queue uses `GREENV_AZURE_QUEUE_MAX_MESSAGES`, `GREENV_QUEUE_VISIBILITY_SECONDS`,
`GREENV_AZURE_POISON_QUEUE_NAME` and `GREENV_AZURE_QUEUE_MAX_DEQUEUE_COUNT`. The poison queue is
mandatory when that adapter is selected and defaults to `greenv-segment-extract-poison`. Service
Bus uses `GREENV_AZURE_SERVICE_BUS_MAX_MESSAGES` and `GREENV_AZURE_SERVICE_BUS_WAIT_SECONDS`.
Set `MANAGEMENT_HEALTH_RABBIT_ENABLED=false` whenever RabbitMQ is not the selected adapter.

The MVP Terraform selects R2 together with Azure Queue and managed identity. A configuration
context test starts the S3 client plus both Azure Queue clients together. See
[`../../infrastructure/README.md`](../../infrastructure/README.md) for the production settings.

Configure an SQS redrive policy and a Service Bus maximum delivery count/DLQ on the cloud
resource. Azure Queue Storage has no native DLQ, so this adapter copies an exhausted message to
the configured poison queue before deleting it from the source. Until the threshold is reached,
failed deliveries are left unacknowledged for the provider visibility timeout.

Compose disables the legacy poller and enables the RabbitMQ listener. A standalone
`./gradlew bootRun` enables only the legacy local poller unless these variables are overridden.
For direct internal testing, `POST /internal/v1/extractions` accepts the same versioned request.
The internal endpoint binds to loopback and has no authentication.

## Mobile processing flow

```text
durable queue request
  -> validate schema, opaque object keys and both SHA-256 values
  -> mark PostgreSQL segment validating
  -> ffprobe media duration, dimensions and every encoded timestamp/key-frame flag
  -> join each frame to bounded-age telemetry
  -> write and re-read frame-metadata-v2.json through object storage
  -> extract full-duration JPEG sample and verify every checksum
  -> write and re-read unpublished manifest
  -> keep source.mp4 (a later measurement stage may want another rate or resolution)
  -> mark manifest published, re-read it and commit segment ready
```

A retryable failure is republished with an incremented attempt until `GREENV_MAX_ATTEMPTS`; a
terminal failure is written to the segment row. Delivery for an already-ready segment is ignored.
If a manifest for the same video and telemetry generation already exists, it is reused and source
cleanup is completed without decoding again. A different generation at the same destination is a
terminal conflict.

## Frame and telemetry contract

`frame-metadata-v2.json` contains one row for every encoded frame, not only the sampled JPEGs. Each
row includes presentation timestamp, derived UTC and monotonic capture time, key-frame flag,
location fields/quality/age and motion fields/age.

- GNSS older than 2 seconds is unavailable.
- Horizontal accuracy up to 10 m is `good`; up to 25 m is `degraded`; worse is unavailable.
- Motion older than 100 ms is unavailable.
- Missing or stale evidence remains absent; it is never silently carried forward.
- Sampled JPEGs are spaced by distance, at most 112 frames for the whole segment, with a
  1024-pixel long edge and no upscaling.

The phone camera provides a completed segment rather than a hardware timestamp for each frame.
Per-frame UTC/monotonic time is therefore derived from the encoded presentation timestamp plus
the segment anchor. The quaternion is relative gyroscope integration, not absolute attitude.

The request, telemetry and manifest schemas in `src/main/resources/contracts/` must remain
byte-for-byte equal to the API copies. SHA-256 values are the generation and idempotency boundary.
The worker does not generate business identifiers: it preserves the session and job UUIDs received
from the API. New producers use UUIDv7, while queued UUIDv4 captures remain valid during rollout.

## Persisted outputs

For a segment at index zero, the shared pipeline volume ends with:

```text
capture-sessions/<session UUID>/segments/00000000/
  telemetry.json
  frame-metadata-v2.json       # one record for every encoded frame
  sampled-frames/
    frame-0001.jpg
    ...
  segment-manifest-v2.json     # publication marker; sourceDeleted=true when complete
```

`source.mp4` is present until all output files and their checksums have been verified. Temporary
`attempts/<video checksum prefix>/` output is removed after publication. The manifest records
input generations, media shape/duration, encoded-frame and location-quality counts, metadata
checksum/size, every sampled JPEG checksum/size, and publication state.

The legacy v1 filesystem queue lives below `tasks/{pending,processing,done,failed}`. Claiming,
status writes and publication use atomic moves. Its sampling rules preserve full-video coverage:

- requested count is `floor(requestedFps * fullDuration)`;
- when capped, effective rate is `maxFrames / fullDuration`, never a trimmed time window;
- the long edge is downscaled without upscaling and JPEG dimensions remain even;
- rotation metadata determines display width and height;
- fewer than two decoded frames is terminal because multi-view geometry needs multiple views.

## Build and test

From this directory:

```bash
./gradlew check
./gradlew bootJar
docker build -t greenv-frame-extractor .
```

The suite covers sampling/rotation, queue transitions and retries, URI escape protection,
checksums and generation reuse, frame timestamp probing, telemetry age/quality association,
manifest publication/source deletion and API contract equality. The integration test builds a
synthetic clip and runs real FFmpeg/ffprobe; it is skipped when either executable is unavailable.
The repository's `capture-smoke` service exercises the complete PostgreSQL/RabbitMQ/API/worker
path with real FFmpeg in containers.

## Troubleshooting

| Symptom | Check |
|---|---|
| Worker health is down | `docker compose ps` and `docker compose logs frame-worker` |
| Segment stays `queued` | Rabbit listener is enabled, queue names match the API, and RabbitMQ is healthy |
| Segment becomes `failed` | Read `errorCode`/`errorMessage` from the API and inspect worker logs |
| `ffmpeg`/`ffprobe` not found | Install both or set `GREENV_FFMPEG` and `GREENV_FFPROBE` to valid executables |
| `invalid_object_key` | Queue messages must carry relative opaque keys, never provider URIs |
| Checksum or generation conflict | API/worker do not share the same volume, or an identity was reused for different bytes |
| Database errors | Worker and API must point at the same PostgreSQL database and migration level |
| Tests skip integration | Put both FFmpeg executables on `PATH`; unit and contract tests still run |

## Production boundary

Dependency direction is explicit and uses constructor injection:

```text
RabbitMQ/HTTP/poller adapter -> inbound use-case interface -> orchestration handler
handler -> processor/state/queue interfaces -> provider adapters
segment processor -> storage/workspace/media interfaces -> local or cloud implementation
```

RabbitMQ, the internal HTTP endpoint and the legacy poller depend on `SegmentExtractionUseCase` or
`LegacyExtractionUseCase`, never concrete handlers. Retry handlers depend on processor, state and
queue ports. `SegmentExtractionService` receives `SegmentObjectStorage`, `CaptureSegmentStore`,
`ProcessingWorkspace`, `VideoProbe`, `FrameTimelineProbe` and `FrameSampler` interfaces; ffmpeg
and ffprobe are therefore replaceable media providers rather than hard-coded infrastructure.
Pure deterministic collaborators such as sampling and telemetry association remain concrete
because adding one-implementation interfaces would not create a useful substitution boundary.

The former local store with both v1 and v2 responsibilities was split into
`LocalSegmentObjectStorageAdapter` and `LocalLegacyPipelineStoreAdapter`. Each implements one
outbound port. The local legacy inbox now implements `LegacyTaskInbox` and exposes only an opaque
receipt to its poller. The shipped provider adapters are JDBC; local, S3-compatible and Azure Blob
storage; and RabbitMQ, SQS, Azure Queue Storage and Azure Service Bus queues. Cloud storage
adapters download into the same ephemeral workspace and publish the same v2 artifacts without
changing extraction code.

The v2 manifest is first stored with `sourceDeleted=false`, verified, and then rewritten with
`sourceDeleted=true` after source cleanup. A redelivery completes this transition idempotently.
Add metrics, tracing, bounded concurrency and retention cleanup in production. Keep each cloud
queue's visibility/lock duration longer than the maximum expected FFmpeg attempt.
FFmpeg is CPU work and must not run on the paid GPU service used by the depth model.

To add a provider, implement the relevant interface in `port/`, register it under a new adapter
value, and leave `service/` unchanged. `SegmentObjectStorage.download` materializes an object in the
ephemeral workspace; `putFile`/`putJson` publish durable output. The queue adapter must redeliver
unacknowledged work, and the state adapter must make duplicate delivery observable as `ready`.

Architecture tests enforce inbound interface injection, service-to-adapter isolation, one port per
local storage adapter, provider-neutral object keys and API/worker schema equality. Handler tests
cover success, retry exhaustion, acknowledgement, requeue and terminal failure.

Verification observed on 30 Aug 2026: `./gradlew.bat check --no-daemon` completed successfully,
including cloud publisher/consumer acknowledgement, poison-queue, storage checksum, codec and
architecture tests. The Compose smoke command was last attempted on 25 Aug 2026 but did not
execute because the local Docker daemon was unavailable.
