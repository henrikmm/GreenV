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

The capture chain is built and wired end to end, and it has run: a segment becomes a grass
measurement without anyone touching it, and two segments went through the whole pipeline against a
live GPU on 8 September 2026. [`docs/AUTOMATIC-HEIGHT.md`](docs/AUTOMATIC-HEIGHT.md) describes that
chain and what it does not establish.

**Two gaps sit between that and an operations decision.** The dashboard has never displayed a
measurement — `apps/web` has no API client and renders Motiva's KMZ polygons with mock levels. And
a measurement carries no `km`, so it cannot be placed on the highway; the packet raises
`road-metadata-missing` rather than guessing. Separately, no automatic reading has ever been
compared with a tape, so every packet reports `operationalStatus: "not-ready"`.

**[`docs/STATE-OF-THE-SYSTEM.md`](docs/STATE-OF-THE-SYSTEM.md) is the honest inventory** — what
runs, what is written but has never executed, and the gaps ranked by what they cost. Read it before
trusting any other document in this repository about a part it does not own.

The depth model used by `measurement/` is licensed for personal and research use only.
