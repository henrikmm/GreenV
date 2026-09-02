# GreenV infrastructure

This Terraform stack creates the low-idle-cost MVP environment without changing application code:

```text
mobile -> Cloudflare DNS -> Azure Container Apps API (0..3 replicas)
                              |-> Neon PostgreSQL
                              |-> private Cloudflare R2 bucket
                              `-> Azure Queue

Azure Queue -> managed-identity KEDA trigger -> frame worker (0..4 replicas)
                                                   |-> Neon PostgreSQL
                                                   `-> private Cloudflare R2 bucket
```

The API is the only public workload. The worker has no ingress. Azure Queue uses two user-assigned
managed identities: the API can send messages, while the worker can read, delete, retry and move
exhausted messages to the poison queue. R2 uses its S3 API because that is the application adapter
contract. PostgreSQL, queue payloads and object keys remain provider-neutral.

The current API does not yet authenticate mobile devices. Terraform provisions TLS and cloud
identity between services; it does not add application authentication. Do not invite external
pilot users until the API authorizes each device and capture session.

No resource is created by `fmt`, `validate` or `test`. `terraform plan` reads cloud APIs but does
not create resources. `terraform apply` creates billable resources and must be reviewed before it
is run.

## What this creates

- one Azure resource group, Consumption Container Apps environment and capped Log Analytics
  workspace;
- one public API Container App and one private queue-driven worker, both with minimum replicas 0;
- one LRS StorageV2 account used only for the segment and poison queues;
- separate Azure managed identities and least-purpose queue roles;
- a bootstrap root that creates separate deletion-protected R2 state and application buckets;
- one Neon PostgreSQL 17 project with pooled connections, autoscaling and the account's permitted
  scale-to-zero interval;
- optional Cloudflare CNAME/TXT records and an Azure managed certificate for `api_hostname`;
- optional Azure budget notifications at 50%, forecasted 80% and actual 100%.

The Terraform does not create a container registry. Public GHCR avoids an always-on ACR Basic
charge for the MVP. Private GHCR or any OCI registry is supported through `container_registry`.

## Prerequisites

- Terraform 1.15.9 or newer;
- an Azure subscription and either `az login` or the standard `ARM_*` service-principal variables;
- Azure resource providers `Microsoft.App` and `Microsoft.OperationalInsights` registered in that
  subscription;
- a Cloudflare API token with R2 bucket write and, when using a custom hostname, DNS write access;
- a Neon API key for the organization where the project will be created;
- published API and worker images, preferably pinned by `@sha256:<digest>`;
- permission to create two R2 S3 credential pairs after the bootstrap creates their buckets.

Cloudflare's Terraform provider can create R2 buckets but cannot mint the S3 access-key pair used
by the Java AWS SDK or the Terraform S3 backend. Create both pairs in the Cloudflare dashboard and
scope each one to its bucket. Never reuse the Cloudflare management token inside the containers.

## Validate locally

Run from this directory:

```powershell
terraform fmt -check -recursive
terraform init -backend=false
terraform validate
terraform test
```

The native tests use mocked providers. They do not require credentials and do not contact Azure,
Cloudflare or Neon.

## Bootstrap remote state

Terraform cannot create the bucket that it needs before backend initialization, so an intentionally
small separate root module first creates the state and application buckets with explicit names.
Creating both together lets the next step issue a different bucket-scoped credential for each use.

```powershell
cd bootstrap
Copy-Item terraform.tfvars.example terraform.tfvars
$env:CLOUDFLARE_API_TOKEN = '<management-token>'
terraform init
terraform plan -out bootstrap.tfplan
terraform apply bootstrap.tfplan
cd ..
```

The `apply` above is the first billable action. R2's current free tier may cover it, but account
usage determines the actual charge. Read the plan before approving it.

After both buckets exist, create the state-bucket and application-bucket S3 credentials. First move
the bootstrap's local state into the state bucket:

```powershell
cd bootstrap
Copy-Item backend.r2.tf.example backend.tf
Copy-Item backend.r2.hcl.example backend.r2.hcl
$env:AWS_ACCESS_KEY_ID = '<state-bucket-access-key>'
$env:AWS_SECRET_ACCESS_KEY = '<state-bucket-secret-key>'
terraform init -migrate-state -backend-config=backend.r2.hcl
cd ..
```

Then configure the main root with the same state credential but a different state key:

```powershell
Copy-Item backend.r2.tf.example backend.tf
Copy-Item backend.r2.hcl.example backend.r2.hcl
$env:AWS_ACCESS_KEY_ID = '<state-bucket-access-key>'
$env:AWS_SECRET_ACCESS_KEY = '<state-bucket-secret-key>'
terraform init -reconfigure -backend-config=backend.r2.hcl
```

`backend.tf`, `backend.r2.hcl`, state and plans are ignored. `.terraform.lock.hcl` is tracked.
The backend uses an R2 lock object to reject concurrent writers. Its bucket has `prevent_destroy`;
deleting it requires an explicit code change and should happen only after the state has been backed
up.

## Configure and deploy

Copy the non-secret example and export secrets. Terraform state contains the generated Neon
password, application API Bearer token and R2 keys, so the state credential must stay narrowly
scoped. Omit `api_bearer_token` to generate a stable 48-character value, or export
`TF_VAR_api_bearer_token` with at least 32 random characters to supply one explicitly.

```powershell
Copy-Item terraform.tfvars.example terraform.tfvars
$env:CLOUDFLARE_API_TOKEN = '<management-token>'
$env:NEON_API_KEY = '<neon-api-key>'
$env:TF_VAR_r2_access_key_id = '<application-bucket-access-key>'
$env:TF_VAR_r2_secret_access_key = '<application-bucket-secret-key>'

terraform plan -out mvp.tfplan
terraform show mvp.tfplan
terraform apply mvp.tfplan
```

For private GHCR packages, export the registry object instead of writing the token to
`terraform.tfvars`. Do not define `container_registry` in that file because `.tfvars` values take
precedence over `TF_VAR_*` environment variables.

```bash
read -rsp "GHCR token with read:packages: " GHCR_READ_TOKEN
echo
export TF_VAR_container_registry="{\"server\":\"ghcr.io\",\"username\":\"Matomomitsu\",\"password\":\"$GHCR_READ_TOKEN\"}"
unset GHCR_READ_TOKEN
```

Set `api_hostname = null` and `cloudflare_zone_id = null` to deploy without a custom domain. When
the hostname is enabled, Terraform deliberately creates DNS-only records because Azure managed
certificate issuance and renewal must reach the Container Apps origin directly. A one-minute
propagation guard separates DNS creation from Azure domain validation. Do not enable the Cloudflare
proxy until renewal behavior has been verified.

The examples use `eastus2`, Neon `aws-us-east-2` and the R2 bootstrap hint `enam`. This keeps the
three data-plane services relatively close. Region availability and price change; override the
three values together if the pilot needs a Brazil-first latency profile.

R2 does not charge direct egress, but Container Apps can charge Azure internet outbound for bytes
the API and worker send to R2. That includes uploaded video forwarded by the API and frames written
by the worker. Measure those bytes in the pilot; R2 removes one side of the transfer bill, not both.

The optional budget uses the Azure subscription billing currency, not necessarily US dollars.
An empty `budget_contact_emails` list skips it. This is useful for test subscriptions that do not
permit budget creation, but a production pilot should configure at least one address.

## Verify the deployment

```powershell
$api = terraform output -raw api_public_url
$apiToken = terraform output -raw api_bearer_token
Invoke-RestMethod "$api/actuator/health"
az containerapp replica list --resource-group (terraform output -raw resource_group_name) --name (terraform output -raw api_container_app_name)
az containerapp replica list --resource-group (terraform output -raw resource_group_name) --name (terraform output -raw worker_container_app_name)
```

From Git Bash, verify that health is public, missing/invalid credentials are rejected and the valid
credential reaches the application. The script does not create or modify capture data:

```bash
export API_URL="$(terraform output -raw api_public_url)"
export GREENV_API_TOKEN="$(terraform output -raw api_bearer_token)"
bash ../services/greenv-video-api/scripts/test-api-auth.sh
```

Then run one real mobile capture using the same shell values:

```bash
cd ../apps/mobile
flutter run \
  --dart-define=GREENV_API_URL="$API_URL" \
  --dart-define=GREENV_API_TOKEN="$GREENV_API_TOKEN"
unset GREENV_API_TOKEN
```

Verify the capture row and segment state in Neon, objects in the R2 application bucket, an empty
source queue, an empty poison queue and worker scale-back to zero. A poison message is retained for
inspection; moving or deleting one is an operational decision, not an automatic Terraform action.

## Operations and recovery

- `terraform plan` before every apply; apply a saved plan so the reviewed graph is the deployed
  graph.
- A failed apply does not roll back resources that were already created. Inspect `terraform state
  list`, correct the configuration, and create a new saved plan; never reuse the plan from the
  failed apply.
- The Neon project deliberately omits `suspend_timeout_seconds`. Free accounts reject attempts to
  modify that interval, so Neon applies the scale-to-zero policy allowed by the account plan.
- Rotate the R2 application key by updating the two sensitive Terraform variables. Container Apps
  creates new revisions with the changed secret.
- Rotate the shared MVP API token with `TF_VAR_api_bearer_token`, rebuild the pilot mobile app and
  apply a reviewed plan. Existing mobile builds stop uploading immediately after the new revision
  receives traffic.
- A queue message can be delivered more than once. Database generation checks and object checksums
  are the idempotency boundary.
- Keep the queue visibility timeout above the measured worst FFmpeg attempt. The MVP default is
  300 seconds (five minutes) and must be changed from evidence, not guesswork.
- R2 has no automatic artifact expiration here. The worker removes transient source objects, while
  frames and manifests are business records. Add lifecycle deletion only after retention is agreed.
- The capture and state buckets use `prevent_destroy`. To retire the environment, export the data,
  remove that guard in a reviewed change and run a fresh plan before deletion.
- The API uses Neon's pooled endpoint for normal JDBC traffic and its direct endpoint for Flyway.
  The first application start runs the migrations; do not enqueue production work until
  `/actuator/health` is healthy and the migration rows exist.

## Authentication inputs

| Provider or runtime | Recommended local input | CI input |
|---|---|---|
| Azure provider | `az login` | `ARM_CLIENT_ID`, `ARM_CLIENT_SECRET`, `ARM_TENANT_ID`, `ARM_SUBSCRIPTION_ID` |
| Cloudflare provider | `CLOUDFLARE_API_TOKEN` | masked secret with R2/DNS resource scope |
| Neon provider | `NEON_API_KEY` | masked organization API key |
| R2 Terraform backend | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | bucket-scoped masked secrets |
| R2 application adapter | `TF_VAR_r2_access_key_id`, `TF_VAR_r2_secret_access_key` | application-bucket-scoped masked secrets |
| MVP API Bearer token | generated when omitted, or `TF_VAR_api_bearer_token` | masked secret with at least 32 random characters |

Do not pass secret values with `-var` because command history can retain them.

AzureRM 5 does not register Azure resource providers by default. Registration is subscription-wide
and intentionally remains a prerequisite rather than a Terraform-managed resource, because
unregistering it during stack destruction could affect unrelated workloads. Register the Container
Apps prerequisites once with an identity allowed to perform provider registration:

```bash
az provider register --namespace Microsoft.App --wait
az provider register --namespace Microsoft.OperationalInsights --wait
az provider show --namespace Microsoft.App --query registrationState --output tsv
az provider show --namespace Microsoft.OperationalInsights --query registrationState --output tsv
```

Both status commands must print `Registered` before creating the Container Apps environment.
