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
| `src/main/java/.../service/` | Capture/job state machines and queue publication |
| `src/main/java/.../storage/` | PostgreSQL records and bounded local object writes |
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
| Exchange/queue/key | `GREENV_SEGMENT_EXCHANGE`, `GREENV_SEGMENT_QUEUE`, `GREENV_SEGMENT_ROUTING_KEY` | `greenv.capture`, `greenv.segment.extract.v1`, `segment.extract.v1` |

`./gradlew bootRun` uses H2 and the local directory defaults for the legacy v1 flow. Use Compose
for v2 so PostgreSQL, RabbitMQ, API and worker share one tested configuration.

The API binds only to loopback and has no authentication. Never expose this pilot adapter directly
to the public internet.

## Mobile v2 data flow

```text
phone creates UUID
  -> PUT segment video + checksum
  -> PUT matching telemetry + checksum
  -> POST segment complete
  -> API commits queued state and publishes RabbitMQ request
  -> worker validates, extracts and commits ready/failed state
  -> phone reads state and deletes its local segment only after ready
  -> phone closes the session with its last segment index
```

A session can be created with a client UUID before network access. Each segment uses the same
idempotency key, captured-at time and duration for both objects; video and telemetry each carry
their own lowercase SHA-256. A repeated upload with the same checksum is accepted. Reusing the
same session, segment or object identity with conflicting metadata/checksum returns `409`.

### Complete v2 request sequence

The generated smoke client is the shortest executable example. To call the contract manually,
first make `source.mp4` and a schema-v1 `telemetry.json` whose `sessionId`, `segmentIndex`,
`capturedAtUtc` and duration describe the same segment. Then:

```bash
API=http://127.0.0.1:8080
SESSION_ID=$(cat /proc/sys/kernel/random/uuid)
SEGMENT_INDEX=0
CAPTURED_AT=2026-08-24T12:00:00Z
DURATION_MS=10000
IDEMPOTENCY_KEY="mobile:${SESSION_ID}:${SEGMENT_INDEX}"
VIDEO=/absolute/path/to/source.mp4
TELEMETRY=/absolute/path/to/telemetry.json

curl -fsS "$API/v2/capture-sessions" \
  -H 'content-type: application/json' \
  -d "{\"sessionId\":\"$SESSION_ID\",\"deviceId\":\"manual-device\",\"startedAt\":\"$CAPTURED_AT\"}" | jq

VIDEO_SHA=$(sha256sum "$VIDEO" | cut -d ' ' -f 1)
TELEMETRY_SHA=$(sha256sum "$TELEMETRY" | cut -d ' ' -f 1)
SEGMENT="$API/v2/capture-sessions/$SESSION_ID/segments/$SEGMENT_INDEX"

curl -fsS -X PUT "$SEGMENT/video" \
  -H 'content-type: video/mp4' \
  -H "X-Idempotency-Key: $IDEMPOTENCY_KEY" \
  -H "X-Content-SHA256: $VIDEO_SHA" \
  -H "X-Captured-At: $CAPTURED_AT" \
  -H "X-Duration-Millis: $DURATION_MS" \
  --data-binary "@$VIDEO" | jq

curl -fsS -X PUT "$SEGMENT/telemetry" \
  -H 'content-type: application/json' \
  -H "X-Idempotency-Key: $IDEMPOTENCY_KEY" \
  -H "X-Content-SHA256: $TELEMETRY_SHA" \
  -H "X-Captured-At: $CAPTURED_AT" \
  -H "X-Duration-Millis: $DURATION_MS" \
  --data-binary "@$TELEMETRY" | jq

curl -fsS -X POST "$SEGMENT/complete" | jq

curl -fsS -X POST "$API/v2/capture-sessions/$SESSION_ID/complete" \
  -H 'content-type: application/json' \
  -d '{"lastSegmentIndex":0}' | jq

until [ "$(curl -fsS "$SEGMENT" | jq -r .state)" = ready ]; do sleep 1; done
curl -fsS "$SEGMENT/manifest" | jq
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

PostgreSQL tables `capture_sessions` and `capture_segments` hold identity, state, object URIs,
checksums, byte counts, final manifest URI/frame count, error details and timestamps. Binary data
does not live in PostgreSQL. The Compose volume stores:

```text
capture-sessions/<session UUID>/segments/00000000/
  source.mp4                  # deleted by the worker only after verified publication
  telemetry.json
  frame-metadata-v1.json
  sampled-frames/*.jpg
  segment-manifest-v1.json
```

Repeating completion while a segment is `queued` republishes the same durable request. This closes
the database-to-queue crash window; the worker's generation check makes duplicate delivery safe.

## Legacy whole-video v1

Start API and worker with the same `GREENV_PIPELINE_ROOT`, then:

```bash
VIDEO=/absolute/path/to/road-video.mp4
SIZE=$(stat -c%s "$VIDEO")

JOB=$(curl -fsS http://127.0.0.1:8080/v1/jobs \
  -H 'content-type: application/json' \
  -d "{\"fileName\":\"$(basename "$VIDEO")\",\"contentType\":\"video/mp4\",\"sizeBytes\":$SIZE,\"requestedFps\":10,\"maxFrames\":100,\"longEdge\":1024}")
JOB_ID=$(printf '%s' "$JOB" | jq -r .jobId)

curl -fsS -X PUT "http://127.0.0.1:8080/v1/jobs/$JOB_ID/source" \
  -H 'content-type: video/mp4' --data-binary "@$VIDEO"
curl -fsS -X POST "http://127.0.0.1:8080/v1/jobs/$JOB_ID/complete"
curl -fsS "http://127.0.0.1:8080/v1/jobs/$JOB_ID" | jq
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
| Upload returns checksum error | Hash the exact transmitted file and send 64 lowercase hexadecimal characters |
| Retry returns `409` | The same session/segment identity was reused with different metadata or object bytes |
| Manifest returns `409` | Poll segment state; only `ready` has a published manifest |
| Phone cannot connect | Android emulator uses `10.0.2.2`; USB devices need `adb reverse` or a reachable LAN URL |
| Port 8080 is occupied | Stop the other process or change both the Compose port mapping and mobile API URL |

## Production boundary

Before an internet deployment, add authenticated device identity, authorization per session,
rate limits, TLS, observability and retention cleanup. Return presigned S3-compatible upload URLs
instead of proxying large video bodies, and let the worker use ephemeral disk plus object storage.
Keep PostgreSQL for control-plane state and add PostGIS only when spatial indexing or server-side
route queries are required. The local shared volume is not redundant production storage.
