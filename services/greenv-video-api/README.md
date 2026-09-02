# GreenV Video API

The Video API is the capture control plane. The mobile `/v2/capture-sessions` contract accepts
ordered MP4 segments and telemetry, stores lifecycle state in PostgreSQL, and publishes durable
RabbitMQ extraction work. The original `/v1/jobs` contract still accepts a complete video. This
service validates and stores bytes; it does not run FFmpeg, identify vegetation, or call the depth
model.

The repository-level Compose stack is the canonical local environment. Its shared volume is a
local object-store adapter so API and worker can exchange files without a cloud account.

## Code map

| Path | Responsibility |
|---|---|
| `src/main/java/.../api/` | HTTP request validation and response models |
| `src/main/java/.../port/` | Inbound use cases and cloud-neutral outbound contracts |
| `src/main/java/.../service/` | Capture/job state machines and queue publication |
| `src/main/java/.../identifier/` | RFC 9562 UUIDv7 provider behind the identifier port |
| `src/main/java/.../storage/` | JDBC, local, S3-compatible and Azure Blob adapters |
| `src/main/java/.../task/` | RabbitMQ, SQS, Azure Queue, Service Bus and legacy queue adapters |
| `src/main/resources/db/migration/` | Flyway schema for capture sessions and segments |
| `src/main/resources/contracts/` | Versioned queue and manifest JSON Schemas |
| `openapi.yaml` | Complete v1 and v2 HTTP contract |
| `src/test/` | Unit and integration coverage |

## Requirements

- Docker Engine with Compose for the supported full stack.
- Java 21 for a native service run.
- `curl`, `jq`, FFmpeg and a SHA-256 command for the manual examples.
- PostgreSQL, RabbitMQ and the sibling `greenv-frame-extractor` for the v2 lifecycle.

On Ubuntu or WSL Ubuntu:

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk curl jq ffmpeg
java -version
```

No machine-wide Gradle installation is needed. The checked-in wrapper downloads the pinned
Gradle version on its first run.

## Run the complete local stack

From the repository root:

```bash
docker compose up -d --build postgres rabbitmq video-api frame-worker
docker compose ps
curl -fsS http://127.0.0.1:8080/actuator/health
```

Follow this service or the worker when diagnosing a capture:

```bash
docker compose logs -f video-api
docker compose logs -f frame-worker
```

Stop containers while preserving PostgreSQL, RabbitMQ and capture files:

```bash
docker compose down
```

Reset all local capture state, including database rows, queued messages and uploaded artifacts:

```bash
docker compose down -v
```

`down -v` is destructive for this local stack. Do not use it when a queued phone capture is the
only copy you intend to keep.

The API listens on `127.0.0.1:8080`. RabbitMQ management is available at
<http://127.0.0.1:15672>; the credentials in `compose.yaml` are deliberately local-only.

## Configuration

Spring reads these environment variables in Compose and native runs:

| Setting | Environment variable | Native default |
|---|---|---|
| Database adapter | `GREENV_DATABASE_ADAPTER` | `jdbc` |
| Object-storage adapter | `GREENV_OBJECT_STORAGE_ADAPTER` | `local` |
| Segment-queue adapter | `GREENV_SEGMENT_QUEUE_ADAPTER` | `rabbitmq` |
| API Bearer token | `GREENV_API_TOKEN` | Required, at least 32 characters |
| Bind address/port | `SERVER_ADDRESS`, `PORT` | `127.0.0.1:8080` |
| Pipeline root | `GREENV_PIPELINE_ROOT` | OS temp directory under `greenv-pipeline` |
| Saved v1 runs | `GREENV_SAVED_ROOT` | `~/verge-runs` |
| Maximum v1 upload | `GREENV_MAX_FILE_SIZE_BYTES` | 1 GiB |
| Transient retention | `GREENV_TRANSIENT_DAYS` | 3 days |
| Database URL | `GREENV_DATABASE_URL` | local H2 file |
| Database user/password | `GREENV_DATABASE_USER`, `GREENV_DATABASE_PASSWORD` | `sa`, empty |
| RabbitMQ address | `GREENV_RABBITMQ_HOST`, `GREENV_RABBITMQ_PORT` | `127.0.0.1:5672` |
| RabbitMQ credentials | `GREENV_RABBITMQ_USER`, `GREENV_RABBITMQ_PASSWORD` | `guest`, `guest` |
| Declare queue topology | `GREENV_RABBITMQ_DYNAMIC` | `false` |
| Segment target | `GREENV_SEGMENT_SECONDS` | 10 seconds |
| Maximum segment | `GREENV_MAX_SEGMENT_BYTES` | 64 MiB |
| Maximum telemetry | `GREENV_MAX_TELEMETRY_BYTES` | 4 MiB |
| Exchange/queue/key | `GREENV_SEGMENT_EXCHANGE`, `GREENV_SEGMENT_QUEUE`, `GREENV_SEGMENT_ROUTING_KEY` | `greenv.capture`, `greenv.segment.extract.v2`, `segment.extract.v2` |

### Cloud adapter selection

The same application artifact supports every combination below. API and worker must select the
same object store and queue and must point at the same bucket/container and queue.

| Port | `GREENV_*_ADAPTER` value | Required configuration |
|---|---|---|
| Object storage | `local` | `GREENV_PIPELINE_ROOT` |
| Object storage | `s3` | `GREENV_S3_BUCKET`; optional `GREENV_S3_ENDPOINT` for R2/MinIO/LocalStack |
| Object storage | `azure-blob` | `GREENV_AZURE_BLOB_CONTAINER` and either a connection string or endpoint |
| Segment queue | `rabbitmq` | Existing RabbitMQ settings |
| Segment queue | `sqs` | `GREENV_SQS_QUEUE_URL`; optional endpoint for LocalStack |
| Segment queue | `azure-queue` | `GREENV_AZURE_QUEUE_NAME` and either a connection string or endpoint |
| Segment queue | `azure-service-bus` | Queue name and either a connection string or fully-qualified namespace |

S3/SQS use `GREENV_AWS_REGION` and the AWS default credential chain when
`GREENV_AWS_ACCESS_KEY`/`GREENV_AWS_SECRET_KEY` are empty. Explicit keys are intended for R2 and
local emulators. Set `GREENV_S3_PATH_STYLE_ACCESS=true` only for an endpoint that requires it.

Azure Blob and Queue share `GREENV_AZURE_STORAGE_CONNECTION_STRING` for local development. In
Azure, omit it and set `GREENV_AZURE_BLOB_ENDPOINT` and/or `GREENV_AZURE_QUEUE_ENDPOINT`; the SDK
then uses `DefaultAzureCredential`, including a Container Apps managed identity. Service Bus uses
`GREENV_AZURE_SERVICE_BUS_QUEUE` plus either `GREENV_AZURE_SERVICE_BUS_CONNECTION_STRING` or
`GREENV_AZURE_SERVICE_BUS_NAMESPACE` with managed identity. Resource auto-creation is off by
default; `GREENV_AZURE_BLOB_CREATE_CONTAINER` and `GREENV_AZURE_QUEUE_CREATE` exist only for local
or disposable environments.

For Cloudflare R2, select `s3`, use the R2 S3 endpoint, keep the region at `auto` if required by the
account, and provide an R2 access-key pair. Persisted records and queue messages still contain
opaque object keys, never provider URLs.

The MVP Terraform selects R2 together with Azure Queue and managed identity. A configuration
context test starts both clients together so their bean names cannot silently collide again. See
[`../../infrastructure/README.md`](../../infrastructure/README.md) for deployment variables.

When the selected queue is not RabbitMQ, set `MANAGEMENT_HEALTH_RABBIT_ENABLED=false` so the
Actuator readiness result does not probe an intentionally unused RabbitMQ connection.

`./gradlew bootRun` uses H2 and the local directory defaults for the legacy v1 flow. Set
`GREENV_API_TOKEN` before starting it. Use Compose for v2 so PostgreSQL, RabbitMQ, API and worker
share one tested configuration.

## API authentication

Every `/v1/**`, `/v2/**` and non-health Actuator request requires
`Authorization: Bearer <token>`. `/actuator/health` is deliberately public because Azure Container
Apps uses it for startup, readiness and liveness probes. The service is stateless: it creates no
HTTP session or cookie, disables form/basic authentication and compares the opaque token in
constant time. Startup fails when `GREENV_API_TOKEN` is missing or shorter than 32 characters.

Compose uses `greenv-local-only-bearer-token-000000000000` unless the host exports a different
`GREENV_API_TOKEN`. Terraform generates 48 random alphanumeric characters when
`api_bearer_token` is omitted, persists the value in protected remote state and mounts it as a
Container Apps secret. Retrieve the deployed value only when needed:

```bash
cd ../../infrastructure
export GREENV_API_TOKEN="$(terraform output -raw api_bearer_token)"
cd ../services/greenv-video-api
API_URL=https://greenvapi.matomomitsu.com bash scripts/test-api-auth.sh
unset GREENV_API_TOKEN
```

The check is read-only: it expects health `200`, missing/invalid credentials `401`, and a valid
credential to reach a deliberately absent capture and return `404`. Never commit, echo or pass the
token as a command-line argument. This shared MVP credential does not identify an individual
device; replace it with short-lived, per-user/device JWTs before distributing the app outside the
pilot group.

## Mobile v2 data flow

```text
phone creates UUIDv7
  -> PUT segment video + checksum
  -> PUT matching telemetry + checksum
  -> POST segment complete
  -> API commits queued state and publishes RabbitMQ request
  -> worker validates, extracts and commits ready/failed state
  -> phone reads state and deletes its local segment only after ready
  -> phone closes the session with its last segment index
```

A session can be created with a client UUIDv7 before network access. Each segment uses the same
idempotency key, captured-at time and duration for both objects; video and telemetry each carry
their own lowercase SHA-256. A repeated upload with the same checksum is accepted. Reusing the
same session, segment or object identity with conflicting metadata/checksum returns `409`.

### Identifier ordering and compatibility

The API generates RFC 9562 UUIDv7 values for new server-assigned capture sessions and legacy v1
jobs. Its generator uses the 48-bit Unix-millisecond field plus a monotonic counter so identifiers
from one process remain strictly ordered when several are created in the same millisecond or its
clock moves backwards. The mobile client also assigns UUIDv7 before going online; that timestamp
therefore represents capture creation on the phone clock, not database insertion time.

Existing UUID database columns need no migration. The API deliberately continues to accept a
client-assigned UUIDv4 so captures queued by an older mobile version can still be uploaded. Sort
mixed historical data by the persisted `created_at` column. Exact insertion order across multiple
processes requires a database sequencing field; UUIDv7 supplies time locality, not a global total
order or a replacement for the authoritative event timestamp.

### Complete v2 request sequence

The generated smoke client is the shortest executable example. To call the contract manually,
first make `source.mp4` and a schema-v1 `telemetry.json` whose `sessionId`, `segmentIndex`,
`capturedAtUtc` and duration describe the same segment. Then:

```bash
API=http://127.0.0.1:8080
GREENV_API_TOKEN=greenv-local-only-bearer-token-000000000000
AUTH=(-H "Authorization: Bearer $GREENV_API_TOKEN")
SEGMENT_INDEX=0
CAPTURED_AT=2026-08-24T12:00:00Z
DURATION_MS=10000

SESSION_ID=$(curl -fsS "$API/v2/capture-sessions" \
  "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d "{\"deviceId\":\"manual-device\",\"startedAt\":\"$CAPTURED_AT\"}" | jq -r .sessionId)
IDEMPOTENCY_KEY="mobile:${SESSION_ID}:${SEGMENT_INDEX}"
VIDEO=/absolute/path/to/source.mp4
TELEMETRY=/absolute/path/to/telemetry.json

VIDEO_SHA=$(sha256sum "$VIDEO" | cut -d ' ' -f 1)
TELEMETRY_SHA=$(sha256sum "$TELEMETRY" | cut -d ' ' -f 1)
SEGMENT="$API/v2/capture-sessions/$SESSION_ID/segments/$SEGMENT_INDEX"

curl -fsS -X PUT "$SEGMENT/video" \
  "${AUTH[@]}" \
  -H 'content-type: video/mp4' \
  -H "X-Idempotency-Key: $IDEMPOTENCY_KEY" \
  -H "X-Content-SHA256: $VIDEO_SHA" \
  -H "X-Captured-At: $CAPTURED_AT" \
  -H "X-Duration-Millis: $DURATION_MS" \
  --data-binary "@$VIDEO" | jq

curl -fsS -X PUT "$SEGMENT/telemetry" \
  "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -H "X-Idempotency-Key: $IDEMPOTENCY_KEY" \
  -H "X-Content-SHA256: $TELEMETRY_SHA" \
  -H "X-Captured-At: $CAPTURED_AT" \
  -H "X-Duration-Millis: $DURATION_MS" \
  --data-binary "@$TELEMETRY" | jq

curl -fsS -X POST "${AUTH[@]}" "$SEGMENT/complete" | jq

curl -fsS -X POST "$API/v2/capture-sessions/$SESSION_ID/complete" \
  "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d '{"lastSegmentIndex":0}' | jq

until [ "$(curl -fsS "${AUTH[@]}" "$SEGMENT" | jq -r .state)" = ready ]; do sleep 1; done
curl -fsS "${AUTH[@]}" "$SEGMENT/manifest" | jq
```

The manifest endpoint returns `409` until the segment reaches `ready`. A session start more than
five minutes in the future, a negative segment index, an empty object, a duration outside 1–30000
ms, an idempotency key over 200 characters, or a malformed checksum is rejected before queueing.

## State and persisted outputs

The mobile state transitions are:

```text
recording -> uploading -> queued -> validating -> ready
                                |                 |
                                +-> failed        +-> verified manifest
```

The JDBC adapter's tables `capture_sessions` and `capture_segments` hold identity, state, opaque
object keys, checksums, byte counts, final manifest key/frame count, error details and timestamps. Binary data
does not live in PostgreSQL. The Compose volume stores:

```text
capture-sessions/<session UUID>/segments/00000000/
  source.mp4                  # deleted by the worker only after verified publication
  telemetry.json
  frame-metadata-v2.json
  sampled-frames/*.jpg
  segment-manifest-v2.json
```

Repeating completion while a segment is `queued` republishes the same durable request. This closes
the database-to-queue crash window; the worker's generation check makes duplicate delivery safe.

## Legacy whole-video v1

Start API and worker with the same `GREENV_PIPELINE_ROOT`, then:

```bash
VIDEO=/absolute/path/to/road-video.mp4
SIZE=$(stat -c%s "$VIDEO")
GREENV_API_TOKEN=greenv-local-only-bearer-token-000000000000
AUTH=(-H "Authorization: Bearer $GREENV_API_TOKEN")

JOB=$(curl -fsS http://127.0.0.1:8080/v1/jobs \
  "${AUTH[@]}" \
  -H 'content-type: application/json' \
  -d "{\"fileName\":\"$(basename "$VIDEO")\",\"contentType\":\"video/mp4\",\"sizeBytes\":$SIZE,\"requestedFps\":10,\"maxFrames\":100,\"longEdge\":1024}")
JOB_ID=$(printf '%s' "$JOB" | jq -r .jobId)

curl -fsS -X PUT "http://127.0.0.1:8080/v1/jobs/$JOB_ID/source" \
  "${AUTH[@]}" -H 'content-type: video/mp4' --data-binary "@$VIDEO"
curl -fsS -X POST "${AUTH[@]}" "http://127.0.0.1:8080/v1/jobs/$JOB_ID/complete"
curl -fsS "${AUTH[@]}" "http://127.0.0.1:8080/v1/jobs/$JOB_ID" | jq
```

When state is `frames_ready`, read `/v1/jobs/{jobId}/manifest`. Results remain transient unless
`POST /v1/jobs/{jobId}/save` is called. `DELETE /v1/jobs/{jobId}` removes transient and saved
copies. The full v1 lifecycle is defined in `openapi.yaml`.

## Build and test

From this directory:

```bash
./gradlew check
./gradlew bootJar
docker build -t greenv-video-api .
```

The test suite covers request validation, v1 and v2 state transitions, checksums, idempotent
retries, database persistence, queue publication/republication, schema equality with the worker,
and HTTP error responses. The full API-worker seam is covered by `services/capture-smoke`.

## Troubleshooting

| Symptom | Check |
|---|---|
| `/actuator/health` is down | `docker compose ps` and `docker compose logs video-api` |
| Database connection refused | PostgreSQL is healthy and `GREENV_DATABASE_URL` uses host `postgres` inside Compose |
| Segment remains `queued` | Worker and RabbitMQ are healthy; inspect `docker compose logs frame-worker rabbitmq` |
| API returns `401` | Send `Authorization: Bearer $GREENV_API_TOKEN`; retrieve the cloud token from the sensitive Terraform output |
| Upload returns checksum error | Hash the exact transmitted file and send 64 lowercase hexadecimal characters |
| Retry returns `409` | The same session/segment identity was reused with different metadata or object bytes |
| Manifest returns `409` | Poll segment state; only `ready` has a published manifest |
| Phone cannot connect | Android emulator uses `10.0.2.2`; USB devices need `adb reverse` or a reachable LAN URL |
| Port 8080 is occupied | Stop the other process or change both the Compose port mapping and mobile API URL |

## Production boundary

Dependency direction is explicit and uses constructor injection:

```text
HTTP controller -> inbound use-case interface -> application service
application service -> outbound interface -> selected database/queue/object-storage adapter
application failure -> HTTP exception mapper -> HTTP status
```

`CaptureSessionController` and `JobController` depend on `CaptureSessionUseCase` and
`LegacyJobUseCase`, never on service implementations. The use-case interfaces expose domain
values rather than HTTP DTOs. `CaptureSessionService`, `JobService` and cleanup orchestration
depend on the narrow outbound ports `CaptureSessionStore`, `CaptureObjectStorage`,
`SegmentWorkQueue`, `LegacyJobStore`, `FrameWorkQueue` and `IdentifierGenerator`; no service
imports JDBC, RabbitMQ, filesystem adapters, concrete identifier generators or Spring HTTP types.
Provider implementations end in `Adapter` so a concrete dependency is visible during review.

The shipped adapters are JDBC; local, S3-compatible and Azure Blob storage; and RabbitMQ, SQS,
Azure Queue Storage and Azure Service Bus queues. They are selected with the three
`GREENV_*_ADAPTER` settings. Queue contract v2 contains
opaque object keys and never a `file:`, `s3:` or provider URL. Do not put signed URLs in the queue
because they can expire while a message is waiting or retrying.

Before a broader internet pilot, replace the shared Bearer token with authenticated device/user
identity, authorization per session and short-lived credentials; add rate limits, observability
and retention cleanup. TLS is already mandatory in the Terraform deployment. A production object adapter should issue
presigned upload URLs where appropriate while retaining opaque keys in persisted state. Ephemeral
worker disk is an FFmpeg workspace, not durable storage.

To add a provider, implement the relevant outbound interface in `port/`, register that implementation under
a new adapter value, and leave `service/` unchanged. API and worker object adapters must address
the same bucket/container and interpret an object key identically. A queue adapter must provide
durable at-least-once delivery; a database adapter must preserve the session/segment identity and
atomic state transitions currently implemented by JDBC.

Architecture tests also enforce the dependency direction, the absence of HTTP DTOs in inbound
ports, provider-neutral object keys and adapter-to-port assignments.

Verification observed on 30 Aug 2026: `./gradlew.bat check --no-daemon` completed successfully
with 35 tests, including Bearer authentication, the cloud adapter, codec, checksum,
conditional-write and architecture tests. The
Compose smoke command was last attempted on 25 Aug 2026 but did not execute because the local
Docker daemon was unavailable.
