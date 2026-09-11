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
services/
  greenv-video-api/          the control plane        Spring Boot, Java 21
  greenv-frame-extractor/    worker 1: frames, GNSS   Spring Boot, Java 21, ffmpeg
  greenv-measurement-worker/ worker 2: grass height   Node 22
  greenv-depth-runpod/       the depth stage on a GPU Python 3.12, Docker
measurement/      Verge Studio               a git subtree; metric height from video
infrastructure/   the cloud MVP              Terraform, Azure, R2, Neon
compose.yaml      the local stack            PostgreSQL, RabbitMQ, API, worker 1, worker 2
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

The pipeline is wired end to end and deployed: a phone uploads a segment, worker 1 samples and
georeferences its frames, worker 2 sends them to a GPU depth service and measures the vegetation,
and the API records the result against the segment. `infrastructure/README.md` documents every
hop and every setting.

**No automatic measurement has been graded against a tape.** Every packet carries
`operationalStatus: "not-ready"`, and that is accurate — see
[`docs/AUTOMATIC-HEIGHT.md`](docs/AUTOMATIC-HEIGHT.md) before using a number this system produces.
The dashboard still renders fixtures rather than live measurements.

The depth model used by `measurement/` is licensed for personal and research use only, and the GPU
service it runs on bills for the machine's whole lifetime rather than for the seconds it computes.
