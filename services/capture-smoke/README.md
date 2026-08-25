# GreenV capture smoke client

This disposable Compose client verifies the complete local mobile-ingestion seam. It creates one
capture session, generates three ordered ten-second MP4 segments with matching telemetry, uploads
them through the real HTTP API, waits for RabbitMQ/worker processing, and checks the published
manifests. It is an integration smoke test, not a load test or a phone-camera simulator.

## What it proves

One successful run proves that the current containers can communicate through:

```text
synthetic MP4 + telemetry
  -> Video API HTTP v2
  -> PostgreSQL lifecycle rows
  -> RabbitMQ durable request
  -> Frame Extractor with real ffprobe/FFmpeg
  -> verified per-frame metadata and segment manifests
  -> API ready session response
```

The script deliberately uploads every video twice with the same checksum, exercising idempotent
retry. Each generated clip is 10 FPS for 10 seconds. The final assertions require three `ready`
segments, 100 encoded-frame metadata rows per manifest and a `ready` session: 30 seconds and 300
encoded frames in total.

It does not exercise Flutter permissions, phone sensors, real camera codecs, network interruption,
authentication, object storage, production TLS or downstream vegetation/depth processing.

## Files

| File | Responsibility |
|---|---|
| `Dockerfile` | Alpine test image with Bash, curl, jq and FFmpeg |
| `smoke.sh` | Data generation, v2 upload sequence, polling and assertions |
| `../../compose.yaml` | Test profile and service dependency wiring |

## Requirements

- Docker Engine with Compose.
- Free host ports listed in the root README.
- Enough local disk for the service images and named volumes.

No host Java, Flutter, FFmpeg or jq installation is required; the containers carry the test
dependencies.

## Run

From the repository root, build and start the long-running dependencies first:

```bash
docker compose up -d --build postgres rabbitmq video-api frame-worker
docker compose --profile test run --rm capture-smoke
```

On success the final line has this shape:

```text
capture smoke passed: <session UUID>, 3 ordered segments, 300 encoded frames
```

The `capture-smoke` container is removed by `--rm`; PostgreSQL rows, RabbitMQ state and generated
worker outputs remain in named volumes for inspection. A new run creates a new session.

Follow service logs in another terminal when debugging:

```bash
docker compose logs -f video-api frame-worker rabbitmq postgres
```

Stop services while preserving their volumes:

```bash
docker compose down
```

Reset all local smoke evidence and other capture data:

```bash
docker compose down -v
```

`down -v` permanently deletes the local database, queue and capture artifacts.

## Configuration

The Compose profile sets `API_URL=http://video-api:8080`, using the service name on the internal
network. Override it only when running the image against a compatible API reachable from its
container:

```bash
docker compose --profile test run --rm \
  -e API_URL=http://video-api:8080 capture-smoke
```

The script waits up to 60 seconds for API health and up to 90 seconds for all three segments to
become ready. Test times, device ID, clip shape/rate, segment count and expected frame counts are
fixed in `smoke.sh`; changing them changes the assertion, so update the test and this guide
together.

## Generated request and outputs

For each segment the client generates:

- one 320×180 MPEG-4 MP4 using FFmpeg's `testsrc2`, 10 FPS for 10 seconds;
- one schema-v1 telemetry document with a UTC/monotonic anchor, GNSS, speed, course, cumulative
  distance, identity quaternion, gravity, acceleration and rotation rate;
- lowercase SHA-256 values for both objects and one stable idempotency key.

The client's temporary directory is always removed on exit. Durable output belongs to the
Compose `capture-data` volume and has the layout documented by the worker README. The test reads
manifests only through the API; it does not depend on a host-specific Docker volume path.

## Verify the test itself

Check shell syntax on a host with Bash:

```bash
bash -n services/capture-smoke/smoke.sh
```

Validate Compose interpolation and service wiring without starting anything:

```bash
docker compose config --quiet
docker compose --profile test config --quiet
```

The meaningful test is still the full `docker compose --profile test run --rm capture-smoke`
command because it crosses every service boundary and runs real FFmpeg.

## Troubleshooting

| Failure | Check |
|---|---|
| `video API did not become healthy` | `docker compose ps` and API/PostgreSQL/RabbitMQ logs |
| A curl command exits early | API logs contain the rejected v2 request and status; rerun after fixing the dependency |
| Segments do not become ready | Inspect worker state/logs and RabbitMQ management at <http://127.0.0.1:15672> |
| Encoded count is not 100 | Confirm the current FFmpeg image still produces 10 FPS × 10 s and inspect the manifest |
| Image build cannot download packages | Restore registry/network access, then rebuild the smoke image |
| Old rows remain after a run | Expected: `--rm` removes only the test container; use `docker compose down -v` for a full reset |

## Production boundary

This image contains development tools and fixed synthetic evidence. Do not deploy it or include it
in a production stack. Production confidence also requires authenticated API tests, real-device
codec/sensor captures, failure/retry tests and object-storage integration; this smoke intentionally
stays fast, deterministic and local.
