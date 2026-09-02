# GreenV MVP infrastructure decision

Decision date: 30 August 2026. No cloud resource was created by this change, so it incurred no
cloud spend. Provisioning is a separate, billable action that requires explicit approval.

## Recommended topology

Use Azure Container Apps Consumption for both Java containers, Azure Queue Storage for segment
work, Cloudflare R2 for private video and frame artifacts, and Neon PostgreSQL for relational
state. Keep Cloudflare authoritative for DNS and expose only `api.<domain>`. Executable Terraform
and the deployment runbook are in [`infrastructure/`](../infrastructure/README.md).

```text
mobile -> Cloudflare DNS/TLS -> Video API Container App (HTTP, min replicas 0)
                                  |-> Neon PostgreSQL
                                  |-> Cloudflare R2 bucket
                                  `-> Azure Queue

Azure Queue -> KEDA scale rule -> Frame Worker Container App (min replicas 0)
                                      |-> Neon PostgreSQL
                                      `-> Cloudflare R2 bucket
```

This option is the first choice for the MVP because the existing containers run unchanged, both
compute workloads scale to zero, R2 avoids direct object egress charges, and Container Apps can
scale the worker from Azure Queue with managed identity. The Azure Storage account contains only
queues. The API has HTTP ingress; the worker has no public ingress.

Set both services to:

```text
GREENV_DATABASE_ADAPTER=jdbc
GREENV_OBJECT_STORAGE_ADAPTER=s3
GREENV_SEGMENT_QUEUE_ADAPTER=azure-queue
MANAGEMENT_HEALTH_RABBIT_ENABLED=false
```

Set the shared R2 endpoint, bucket, region `auto`, access key and secret key through the documented
`GREENV_S3_*`/`GREENV_AWS_*` settings. R2 credentials cannot use the Azure managed identities.

Also set `GREENV_LOCAL_POLLING_ENABLED=false` on the worker so the legacy whole-video filesystem
poller is not scheduled in the cloud deployment.

Use managed identity for Azure Queue rather than storage connection strings. The API identity needs
Queue Data Message Sender. The worker identity uses Queue Data Contributor so the application and
KEDA can read queue length, receive, delete, retry and poison messages. R2 and database credentials
remain Container Apps secrets; use Neon's pooled hostname for application traffic, its direct
hostname for Flyway and require TLS for both.

## Cost-conscious alternatives

| Option | Compute | Queue | Objects | Database | When to choose |
|---|---|---|---|---|---|
| A — selected | Azure Container Apps | Azure Queue | Cloudflare R2 through `s3` | Neon | Low idle compute cost, native queue scaling and no direct R2 egress charge |
| B — single-cloud fallback | Azure Container Apps | Azure Queue | Azure Blob | Neon | Fewer providers when operational simplicity matters more than object egress |
| C — richer messaging | Azure Container Apps | Azure Service Bus | Azure Blob or R2 | Neon | Native DLQ, sessions or stronger broker features justify added cost |
| D — AWS queue | Azure Container Apps | SQS | S3 or R2 | Neon | The team already operates AWS/IAM or expects a later AWS move |
| E — current topology | Azure Container Apps | hosted RabbitMQ | R2 or Azure Blob | Neon | Fastest migration from Compose, but another broker vendor remains |

The selected option uses `GREENV_OBJECT_STORAGE_ADAPTER=s3` and R2 endpoint/credentials. R2's
published Standard free tier includes 10 GB-month, one million Class A operations and ten million
Class B operations per month, with no direct R2 egress charge. Beyond that, Standard storage is
listed at USD 0.015/GB-month. See [Cloudflare R2 pricing](https://developers.cloudflare.com/r2/pricing/).
This does not make the entire cross-cloud path egress-free: bytes sent from Azure Container Apps
to R2 can count as Azure internet outbound transfer. Measure uploaded video and generated-frame
bytes during the pilot and compare that charge with the Azure Blob fallback before scaling volume.

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

1. Choose the Azure, Neon and R2 regions together, estimate pilot volume, publish both OCI images
   and pin their immutable digests. Do not deploy `latest`.
2. Run the Terraform bootstrap. It creates one private R2 state bucket and a separate private R2
   application bucket. Issue one bucket-scoped S3 credential for each use, then migrate the
   bootstrap state into R2.
3. Configure the main root and save a Terraform plan. It covers the Azure resource group,
   Consumption Container Apps environment, capped logs, LRS queue account, managed identities,
   Neon project, R2 lookup, optional DNS and monthly budget. Review that plan before the billable
   apply.
4. Apply the reviewed plan. The API starts with external ingress on port 8080, `minReplicas=0`,
   `maxReplicas=3`, 1 vCPU and 2 GiB. The worker has no ingress, `minReplicas=0`, `maxReplicas=4`,
   2 vCPU and 4 GiB. No custom VNet is attached for this MVP.
5. Wait for API health and verify that Flyway used Neon's direct TLS hostname while normal JDBC uses
   the pooled hostname. Do not enqueue pilot work until the migration rows exist.
6. Verify the `azure-queue` KEDA rule has queue length 1 and the worker identity. Set the visibility
   timeout above the measured worst FFmpeg attempt before increasing workload volume.
7. If `api_hostname` is set, confirm the Cloudflare validation TXT and direct CNAME. Terraform keeps
   the CNAME DNS-only because an intermediate proxy can block Azure managed-certificate issuance;
   enable the proxy only after renewal behavior is verified. See [custom domains](https://learn.microsoft.com/en-us/azure/container-apps/custom-domains-managed-certificates).
8. Run `docker compose --profile test up --build capture-smoke` locally, then repeat the same capture
   against the public hostname. Verify database state, R2 objects, empty source queue, empty poison
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
- Keep both R2 buckets private, encrypt in transit, use distinct state and application credentials,
  rotate credentials and decide artifact retention before adding lifecycle deletion.
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
