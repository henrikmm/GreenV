# GreenV

GreenV finds the roadside vegetation that needs mowing first.

A phone mounted in a vehicle records a route with GNSS and motion telemetry. The video is uploaded,
sampled into georeferenced frames, and screened for vegetation. Where the screening is uncertain or
the growth looks tall, a depth model reconstructs the scene in three dimensions and measures the
grass against a fitted ground plane. The result is a map of road stretches ordered by how urgently
a crew should be sent, with the frame each decision came from attached to it.

Built for the Motiva challenge by group 27, 2CCPW.

## Layout

```
apps/web/         the map dashboard          React, Vite, Leaflet
apps/mobile/      the capture client         Flutter
services/         the API and the workers    Spring Boot, Java 21
measurement/      Verge Studio               a git subtree; metric height from video
infrastructure/   the cloud MVP               Terraform, Azure, R2, Neon
compose.yaml      the local stack            PostgreSQL, RabbitMQ, API, worker 1
```

## Running it

The whole backend, locally:

```
docker compose up --build
```

The API answers on `127.0.0.1:8080`; RabbitMQ's management console is on `:15672`. One end-to-end
check of the stack:

```
docker compose --profile test up --build capture-smoke
```

The dashboard, against the fixtures it currently ships with:

```
cd apps/web && npm ci && npm run dev
```

Each part has its own README with the detail. `AGENTS.md` is the working agreement — read it before
changing anything.

The capture API and frame worker can switch object storage between local files, S3-compatible
storage (including Cloudflare R2) and Azure Blob, and can switch the segment queue between
RabbitMQ, Amazon SQS, Azure Queue Storage and Azure Service Bus without changing application
services. The recommended scale-to-zero MVP topology, alternatives, cost evidence and deployment
sequence are in [`docs/INFRASTRUCTURE_MVP.md`](docs/INFRASTRUCTURE_MVP.md).
The executable Terraform, R2 remote-state bootstrap and deployment runbook are in
[`infrastructure/`](infrastructure/README.md). Validation and native infrastructure tests use
mocked providers and create no cloud resources.

New capture sessions, legacy jobs and newly persisted mobile device identities use RFC 9562
UUIDv7. This provides time-local identifiers without changing PostgreSQL's `UUID` columns. Older
UUIDv4 mobile captures remain accepted during migration; the API and mobile READMEs document the
ordering limits and compatibility behavior.

## Status

The pieces above exist and are wired to each other locally. **The connection between the capture
pipeline and `measurement/` is not built yet**: Verge Studio measures a scene interactively today,
and the headless entry point a worker would call is still to come. Until then the dashboard renders
fixtures, not live measurements.

The depth model used by `measurement/` is licensed for personal and research use only.
