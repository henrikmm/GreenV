# GreenV Frame Extractor

The Frame Extractor is the CPU worker for mobile segments and legacy whole videos. For each mobile
segment it verifies both inputs, probes every encoded presentation timestamp, associates eligible
GNSS and inertial samples with every frame, extracts representative JPEGs across the full segment,
checks every output, publishes the manifest last, and only then deletes the source MP4.

It does not identify grass, estimate height, or call the depth model. Those are downstream stages
that consume its versioned manifest.

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
  -> delete source.mp4
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
- Sampled JPEGs target 2 FPS across the full segment, at most 64 frames, with a 1280-pixel long
  edge and no upscaling.

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
