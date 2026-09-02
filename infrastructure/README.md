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

Set `api_hostname = null` and `cloudflare_zone_id = null` to deploy without a custom domain.

## The custom hostname takes two applies

Azure issues the managed certificate by reaching the Container Apps origin directly, and an
intermediate proxy can block that. So the DNS record starts unproxied, the certificate is issued,
and only then does the edge protection go on. Trying to do both in one apply is what leaves the
hostname resolving to an origin that resets the TLS handshake.

**Phase one — DNS-only, so the certificate can be issued.** Keep these at their defaults:

```hcl
cloudflare_proxy_enabled          = false
restrict_api_origin_to_cloudflare = false
cloudflare_api_waf_enabled        = false
cloudflare_api_rate_limit_enabled = false
```

```bash
terraform plan -out=bootstrap.tfplan
terraform show bootstrap.tfplan
terraform apply bootstrap.tfplan
```

That creates the DNS-only CNAME, the `asuid` ownership TXT record, the Azure custom domain, the
managed certificate and its binding, in that order — Azure refuses to issue a certificate for a
hostname that is not registered yet. Nothing else, so the Cloudflare token needs only
**Zone → DNS → Edit** for this phase.

**This apply needs the Azure CLI signed in on the machine running Terraform**, because the binding
is a `local-exec`. The provider cannot do it: `container_app_environment_certificate_id` parses
only an environment certificate id (`.../certificates/<name>`) and rejects a managed certificate id
(`.../managedCertificates/<name>`) at plan time. Both certificate fields are therefore in
`ignore_changes`, as the provider documents, and `terraform_data.api_certificate_binding` runs the
one command that completes the hostname:

```bash
az containerapp hostname bind --resource-group rg-greenv-mvp --name ca-greenv-mvp-api \
  --hostname greenvapi.matomomitsu.com --environment cae-greenv-mvp-eqvs07 \
  --certificate mc-greenvapi-matomomitsu-com
```

Binding is create-or-update, so it is safe to repeat; Terraform re-runs it only when the
certificate or the container app is replaced. If Terraform runs somewhere without the CLI — CI
using `ARM_*` service-principal variables, for instance — run that command by hand once instead.

Confirm the binding before going further:

```bash
az containerapp hostname list \
  --resource-group rg-greenv-mvp \
  --name ca-greenv-mvp-api \
  --output table

curl -I https://greenvapi.matomomitsu.com/actuator/health
```

`bindingType` must read `SniEnabled`. A TLS handshake that resets means the certificate is not
bound: Azure's ingress rejects the SNI for a hostname it has registered but has no certificate for,
which looks like a network fault rather than a configuration one. Wait and repeat; do not move on.

### How the certificate is issued

`azurerm_container_app_environment_managed_certificate.api` requests it and
`azurerm_container_app_custom_domain.api` binds it. Azure issues and renews it for free, validating
ownership from public DNS: the `asuid.<hostname>` TXT record proves ownership and the CNAME must
resolve to the Container App.

Two consequences follow, and both are easy to get wrong:

- **The record must be DNS-only while the certificate is issued.** A proxied record answers with
  Cloudflare's addresses, and CNAME validation has nothing to match. This is the whole reason the
  rollout is split in two phases.
- **Renewal validates again.** The constraint therefore applies for the certificate's whole life,
  not only its first issue. Verify renewal behaviour before leaving the proxy on permanently; if
  renewal fails behind the proxy, switch `cloudflare_proxy_enabled` back to `false` long enough for
  Azure to renew.

The provider documents putting `certificate_binding_type` and `container_app_environment_certificate_id`
in `ignore_changes` when a managed certificate is used, because Azure sets them asynchronously.
This stack sets them explicitly instead. Following that advice is what left the hostname registered
but unbound for weeks: with both ignored and no certificate resource, nothing ever bound anything.
If Azure does churn those fields between applies, add the `ignore_changes` block back **after** the
first successful bind, not before it.

**Phase two — proxy the record and close the origin.** Only after the certificate is bound:

```hcl
cloudflare_proxy_enabled          = true
restrict_api_origin_to_cloudflare = true
cloudflare_api_waf_enabled        = true
cloudflare_api_rate_limit_enabled = true
```

```bash
terraform plan -out=edge-security.tfplan
terraform show edge-security.tfplan
terraform apply edge-security.tfplan
```

This phase creates Cloudflare rulesets, so the token needs more than DNS. Add **Zone → Cache Rules
→ Edit** for the cache-bypass rule and **Zone → Zone WAF → Edit** for the firewall and rate-limit
rules. A token without them fails the apply with `403 Forbidden` and Cloudflare error code 10000,
which reads as "Authentication error" even though the token itself is valid — the DNS records in
phase one will have applied first.

Then check that the public hostname works and the origin no longer answers anyone else:

```bash
curl -i https://greenvapi.matomomitsu.com/actuator/health

API_ORIGIN="$(terraform output -raw api_origin_url)"
curl -i "$API_ORIGIN/actuator/health"
```

The custom domain must answer; the direct origin must be rejected. If both still answer, the
origin restriction did not apply and the API is still reachable around Cloudflare.
`terraform output edge_security_state` names the phase the configuration is in.

### What the edge does and does not do

- **`proxied = true` alone protects nothing.** The Azure origin FQDN stays public and resolvable.
  The configuration is only complete when the record is proxied *and*
  `restrict_api_origin_to_cloudflare` has allowed only Cloudflare's ranges on the Container App.
  Container Apps denies every other address once any Allow rule exists.
- **The Cloudflare proxy is not authentication.** Every route except `/actuator/health` still
  requires the Bearer token, and that stays true whichever way a request arrives.
- **Never put a Cloudflare service token in the mobile app.** A shipped client cannot hold a
  secret; anyone who unpacks the build reads it.
- **The rules arrive with the proxy, not before it.** All three rulesets are created only when
  `cloudflare_proxy_enabled` is true, which keeps phase one to DNS permissions alone. A DNS-only
  record is never proxied and therefore never cached, so nothing is exposed by waiting.
- **Cloudflare allows one entry-point ruleset per zone phase.** If the zone already has a ruleset
  in `http_request_cache_settings`, `http_request_firewall_custom` or `http_ratelimit`, import it
  (`terraform import cloudflare_ruleset.api_cache_bypass zones/<zone id>/<ruleset id>`) and add
  the rule inside it rather than creating a second, conflicting entry point.
- **WAF and rate limiting depend on the Cloudflare plan.** Both are off by default. The custom
  rules declared here avoid Managed Rules, which need a paid entitlement; confirm plan support
  before enabling them in `terraform.tfvars`.
- **`manage_cloudflare_zone_security_settings` is zone-wide.** Strict SSL, a TLS 1.2 minimum and
  TLS 1.3 apply to every hostname in `matomomitsu.com`, not just the API. It stays off by default
  for that reason.
- **To recreate the custom domain from scratch**, set the flags back to the phase-one values,
  apply, let Azure reissue the certificate, then repeat phase two.

`api_allowed_origins` is a separate concern from all of the above. It fills `GREENV_ALLOWED_ORIGINS`
on the API container, which is what lets a browser page read an API response at all. Leave it empty
for a phone-only deployment.

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
- **The worker's queue scale rule always shows as a change, and that is expected.** Azure stores it
  as a native `azureQueue` rule, and the provider reads that back as `azure_queue_scale_rule`,
  which never matches the declared `custom_scale_rule`. The declaration cannot change:
  `azure_queue_scale_rule` requires an `authentication` block with a storage connection string
  secret and has no identity option, so `custom_scale_rule` with `custom_rule_type = "azure-queue"`
  is the only way to scale on managed identity. Applying it rewrites the same values, identity
  included. Do not "fix" the diff by switching blocks; that would replace the identity with a
  connection string.
- **A revision stuck at `ActivationFailed` needs a new revision, not a retry.** Container Apps
  never re-attempts a revision it has already failed, so a wrong registry credential leaves the
  workload dead even after the credential is corrected — and a further `terraform apply` changes
  nothing, because the declared state already matches. `az containerapp revision restart` does not
  help either; with `min_replicas = 0` the platform has no reason to try. Change
  `deployment_revision` and apply: it rolls a fresh revision of both workloads and nothing else.

  ```bash
  az containerapp revision list -n ca-greenv-mvp-worker -g rg-greenv-mvp -o table
  ```

  Verify the registry credential before rolling, or the new revision fails the same way:

  ```bash
  tok=$(printf '%s' "$TF_VAR_container_registry" | sed -E 's/.*"password" *: *"([^"]*)".*/\1/')
  curl -s -o /dev/null -w "GHCR: HTTP %{http_code}\n" -u "Matomomitsu:$tok" \
    "https://ghcr.io/token?scope=repository:matomomitsu/greenv-video-api:pull&service=ghcr.io"
  ```
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
- **Nothing deletes capture artifacts.** R2 has no lifecycle rule, `expires_at` is recorded but no
  scheduler acts on it, and the worker keeps the source segment so a later measurement stage can
  re-sample it. Source, frames and manifests all accumulate: roughly 8.5 MB per ten-second segment,
  about 3 GB per hour of driving, against R2's 10 GB free tier. Agree a retention rule and add
  lifecycle deletion before a pilot runs for more than a few hours.
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
