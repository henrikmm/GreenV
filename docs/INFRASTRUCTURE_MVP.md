# GreenV MVP infrastructure decision

Decision date: 30 August 2026. No cloud resource was created by this change, so it incurred no
cloud spend. Provisioning is a separate, billable action that requires explicit approval.

## Recommended topology

Use Azure Container Apps Consumption for both Java containers, Azure Queue Storage for segment
work, Azure Blob Storage for video and frame artifacts, and Neon PostgreSQL for relational state.
Keep Cloudflare authoritative for DNS and expose only `api.<domain>`.

```text
mobile -> Cloudflare DNS/TLS -> Video API Container App (HTTP, min replicas 0)
                                  |-> Neon PostgreSQL
                                  |-> Azure Blob container
                                  `-> Azure Queue

Azure Queue -> KEDA scale rule -> Frame Worker Container App (min replicas 0)
                                      |-> Neon PostgreSQL
                                      `-> Azure Blob container
```

This option is the first choice for the MVP because the existing containers run unchanged, both
compute workloads scale to zero, one inexpensive Azure Storage account provides Blob and Queue,
and Container Apps can scale the worker from Azure Queue with managed identity. The API has HTTP
ingress; the worker has no public ingress.

Set both services to:

```text
GREENV_DATABASE_ADAPTER=jdbc
GREENV_OBJECT_STORAGE_ADAPTER=azure-blob
GREENV_SEGMENT_QUEUE_ADAPTER=azure-queue
MANAGEMENT_HEALTH_RABBIT_ENABLED=false
```

Also set `GREENV_LOCAL_POLLING_ENABLED=false` on the worker so the legacy whole-video filesystem
poller is not scheduled in the cloud deployment.

Use managed identity for Blob/Queue in production rather than storage connection strings. The API
identity needs Blob Data Contributor and Queue Data Message Sender. The worker identity needs Blob
Data Contributor and Queue Data Message Processor/Sender permissions for the source and poison
queues. Database credentials remain Container Apps secrets; use Neon's pooled connection string
and require TLS.

## Cost-conscious alternatives

| Option | Compute | Queue | Objects | Database | When to choose |
|---|---|---|---|---|---|
| A — recommended | Azure Container Apps | Azure Queue | Azure Blob | Neon | Lowest operational burden and native queue scaling |
| B — lowest object egress | Azure Container Apps | Azure Queue | Cloudflare R2 through `s3` | Neon | Video volume or downloads make object egress material |
| C — richer messaging | Azure Container Apps | Azure Service Bus | Azure Blob or R2 | Neon | Native DLQ, sessions or stronger broker features justify added cost |
| D — AWS queue | Azure Container Apps | SQS | S3 or R2 | Neon | The team already operates AWS/IAM or expects a later AWS move |
| E — current topology | Azure Container Apps | hosted RabbitMQ | R2 or Azure Blob | Neon | Fastest migration from Compose, but another broker vendor remains |

Option B changes only `GREENV_OBJECT_STORAGE_ADAPTER=s3` and the S3 endpoint/credentials. R2's
published Standard free tier includes 10 GB-month, one million Class A operations and ten million
Class B operations per month, with no direct R2 egress charge. Beyond that, Standard storage is
listed at USD 0.015/GB-month. See [Cloudflare R2 pricing](https://developers.cloudflare.com/r2/pricing/).

SQS has no minimum fee and includes one million requests per month, but cross-cloud credentials
and traffic make it less convenient than Azure Queue beside Container Apps. See the
[Amazon SQS FAQ](https://aws.amazon.com/sqs/faqs/). Service Bus is the capability choice, not the
cost-minimum choice; use its pricing calculator for the chosen region and tier.

Azure Container Apps Consumption charges no resource consumption while a revision is at zero.
The published monthly grant is 180,000 vCPU-seconds, 360,000 GiB-seconds and two million HTTP
requests per subscription. See [Container Apps billing](https://learn.microsoft.com/en-us/azure/container-apps/billing)
and [scaling](https://learn.microsoft.com/en-us/azure/container-apps/scale-app). Neon currently
lists a USD 0 plan with 100 CU-hours and 0.5 GB storage per project; its usage-based Launch example
is approximately USD 15/month for intermittent 1 GB use. See [Neon pricing](https://neon.com/pricing).

Actual cost is workload- and region-dependent. Before provisioning, estimate captures per day,
average MP4 segment bytes, retention days, sampled-frame bytes, extraction CPU-seconds, queue
operations and database size in the Azure and vendor calculators. Put budget alerts at USD 5,
USD 15 and USD 30 for the pilot.

## Why not deploy these Spring services as functions today

The API is an HTTP Spring Boot container and the extractor is a long-running FFmpeg container with
in-process queue consumers. Azure Container Apps is serverless container compute and accepts them
without a function-runtime rewrite. AWS Lambda would require HTTP and SQS event adapters, different
acknowledgement semantics and careful handling of its execution/ephemeral-storage limits. That can
be a later optimization, not a same-day MVP deployment.

## Same-day deployment sequence

1. Create one resource group and a Container Apps Consumption environment in the nearest supported
   region; do not attach a custom VNet for the MVP because it can add cost and complexity.
2. Create one GPv2 LRS Storage account, a private Blob container, the segment queue and the poison
   queue. Configure lifecycle deletion for source video only after the business retention decision.
3. Create the Neon project in a region close to the Container Apps region. Run Flyway by starting
   the API once against the pooled TLS JDBC URL, then verify both migration rows and API health.
4. Publish the two Docker images to GHCR or another existing registry. Pin immutable image digests;
   do not deploy `latest`.
5. Deploy the API with external ingress on port 8080, `minReplicas=0`, `maxReplicas=3`, health probes,
   1 vCPU and 2 GiB as the initial limit. Set a maximum request body above 64 MiB end-to-end.
6. Deploy the worker without ingress, `minReplicas=0`, `maxReplicas=4`, 2 vCPU and 4 GiB initially.
   Add an `azure-queue` KEDA rule with queue length 1 and managed identity. Set the queue visibility
   timeout above the measured worst FFmpeg attempt.
7. Add `api.<domain>` as the Container App custom domain. In Cloudflare create the validation TXT
   and direct CNAME required by Azure. Azure warns that an intermediate proxied CNAME can block
   managed-certificate issuance, so begin DNS-only; enable the Cloudflare proxy only after the
   certificate is issued and renewal behavior is verified. See [custom domains](https://learn.microsoft.com/en-us/azure/container-apps/custom-domains-managed-certificates).
8. Run `docker compose --profile test up --build capture-smoke` locally, then repeat the same capture
   against the public hostname. Verify database state, Blob objects, empty source queue, empty poison
   queue and worker scale-back to zero.
9. Add alerts for API 5xx, queue age/depth, poison messages, failed segments, database storage and
   cloud budget before inviting pilot users.

## Required production controls

- Authenticate each mobile device and authorize access to its capture session before exposing the
  API. Cloudflare DNS alone is not application authentication.
- Keep PostgreSQL transaction state, object keys and queue messages provider-neutral. Never persist
  signed URLs or provider resource URLs.
- Configure SQS redrive and Service Bus DLQ settings when those adapters are selected. The Azure
  Queue adapter implements its own maximum-dequeue poison move because Queue Storage has no native
  DLQ.
- Use one queue consumer per message. Duplicate delivery is expected; database state and object
  generation checks remain the idempotency boundary.
- Keep object storage private, encrypt in transit, rotate credentials, cap retention and deny public
  container/bucket access.
- Measure one real pilot batch before changing CPU/memory or concurrency. FFmpeg is CPU work; it
  must not wake the separately billed GPU measurement service.

## Adapter switch matrix

| Concern | Adapter value | API publishes/writes | Worker consumes/reads/writes |
|---|---|---|---|
| Local objects | `local` | Yes | Yes |
| S3, R2, MinIO | `s3` | Yes | Yes |
| Azure Blob | `azure-blob` | Yes | Yes |
| RabbitMQ | `rabbitmq` | Yes | Yes |
| Amazon SQS | `sqs` | Yes | Yes, including retry publication |
| Azure Queue Storage | `azure-queue` | Yes | Yes, including poison queue |
| Azure Service Bus | `azure-service-bus` | Yes | Yes, including abandon/complete |

All cloud SDK clients are constructed in configuration adapters. Application services depend only
on ports, queue payloads carry the same versioned JSON contract, and object artifacts keep their
SHA-256 in provider metadata in addition to the database/manifest record.
