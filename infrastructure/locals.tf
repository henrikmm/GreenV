locals {
  # Terraform creates the depth endpoint only when this deployment actually measures through
  # RunPod and a template name is given. Without the name there is nothing to point an endpoint
  # at: the template carries the image and the bucket credentials, and this provider cannot
  # create one - see runpod.tf.
  creates_depth_endpoint = (
    var.measurement_enabled
    && var.depth_service_adapter == "runpod"
    # Naming an endpoint is naming one that already exists. Terraform reaches for the RunPod API
    # only when nobody has.
    && var.depth_service_endpoint_id == null
  )

  # The template is provisioned during the apply unless a specific one was named.
  provisions_depth_template = local.creates_depth_endpoint && var.depth_template_id == null

  depth_template_id = (
    var.depth_template_id != null
    ? var.depth_template_id
    : (local.provisions_depth_template ? data.external.depth_template[0].result.template_id : null)
  )

  # The endpoint this deployment talks to: the one Terraform just created, or one a person made
  # by hand and named in a variable.
  depth_endpoint_id = local.creates_depth_endpoint ? runpod_endpoint.depth[0].id : var.depth_service_endpoint_id

  name_prefix         = "${var.project_name}-${var.environment}"
  runtime_name_prefix = substr(local.name_prefix, 0, 20)
  suffix              = random_string.resource_suffix.result

  resource_group_name  = "rg-${local.name_prefix}"
  storage_account_name = "${substr(lower(replace("st${var.project_name}${var.environment}", "-", "")), 0, 18)}${local.suffix}"
  segment_queue_name   = "greenv-segment-extract-v2"
  poison_queue_name    = "greenv-segment-extract-poison"
  r2_endpoint          = "https://${var.cloudflare_account_id}.r2.cloudflarestorage.com"

  # An Azure queue name allows only lowercase letters, digits and single hyphens, so the RabbitMQ
  # names the services default to (greenv.segment.measure.v1) cannot be used verbatim. These are
  # those names with the dots replaced, which is the convention the extraction queue above set.
  measurement_queue_name        = "greenv-segment-measure-v1"
  measurement_result_queue_name = "greenv-segment-measured-v1"
  measurement_poison_queue_name = "greenv-segment-measure-poison"

  # The API's own default for GREENV_AZURE_MEASURED_POISON_QUEUE_NAME, spelled here so the queue
  # exists rather than being named at a queue nobody created.
  measurement_result_poison_queue_name = "greenv-segment-measured-poison"

  # RBAC scopes for the measurement identity's three queue roles, one queue each. See the comment
  # above those assignments in azure.tf for why they are narrower than the account-scoped pair
  # beside them, and why the scope is composed here rather than read off the queue resource.
  measurement_queue_scope               = "${azurerm_storage_account.queue.id}/queueServices/default/queues/${azurerm_storage_queue.measurement.name}"
  measurement_result_queue_scope        = "${azurerm_storage_account.queue.id}/queueServices/default/queues/${azurerm_storage_queue.measurement_result.name}"
  measurement_poison_queue_scope        = "${azurerm_storage_account.queue.id}/queueServices/default/queues/${azurerm_storage_queue.measurement_poison.name}"
  measurement_result_poison_queue_scope = "${azurerm_storage_account.queue.id}/queueServices/default/queues/${azurerm_storage_queue.measurement_result_poison.name}"

  # Every edge rule is scoped to this one hostname. The zone holds unrelated subdomains and a
  # zone-wide rule would reach all of them.
  api_host_expression = var.api_hostname == null ? "" : "http.host eq \"${var.api_hostname}\""

  api_certificate_name = var.api_hostname == null ? "" : "mc-${replace(var.api_hostname, ".", "-")}"

  # Everything under the dashboard host. Unlike the API, which exposes a known set of route
  # prefixes, a single-page app serves whatever path the router invented plus hashed asset names,
  # so the host is the whole rule.
  dashboard_host_expression = (
    var.dashboard_hostname == null ? "" : "http.host eq \"${var.dashboard_hostname}\""
  )

  # The routes that must survive the zone's blanket block. `/v1` and `/v2` are the capture and
  # control-plane APIs; the other two are here because leaving them out breaks things that are
  # easy to forget. Without `/.well-known/*` no client can fetch the JWKS and therefore no access
  # token can be verified, and without `/actuator/health` the one route that is deliberately
  # public stops answering, which is also the route every runbook checks first.
  api_public_path_expression = join(" or ", [
    "http.request.uri.path wildcard r\"/v1\"",
    "http.request.uri.path wildcard r\"/v1/*\"",
    "http.request.uri.path wildcard r\"/v2\"",
    "http.request.uri.path wildcard r\"/v2/*\"",
    "http.request.uri.path wildcard r\"/actuator/health\"",
    "http.request.uri.path wildcard r\"/.well-known/*\"",
  ])

  # The one step Azure requires that the provider cannot express. Kept here rather than inline in
  # the provisioner so it is readable and can be asserted on.
  api_certificate_bind_command = join(" ", [
    "az containerapp hostname bind",
    "--resource-group ${azurerm_resource_group.this.name}",
    "--name ${azurerm_container_app.api.name}",
    "--hostname ${var.api_hostname == null ? "" : var.api_hostname}",
    "--environment ${azurerm_container_app_environment.this.name}",
    "--certificate ${try(azurerm_container_app_environment_managed_certificate.api[0].id, "")}",
    "--output none",
  ])

  # Published at https://www.cloudflare.com/ips-v4; this copy matched it on 2 September 2026.
  # Keyed by a stable name so adding or removing a range does not renumber the others in state.
  cloudflare_ipv4_cidrs = {
    cf_01 = "173.245.48.0/20"
    cf_02 = "103.21.244.0/22"
    cf_03 = "103.22.200.0/22"
    cf_04 = "103.31.4.0/22"
    cf_05 = "141.101.64.0/18"
    cf_06 = "108.162.192.0/18"
    cf_07 = "190.93.240.0/20"
    cf_08 = "188.114.96.0/20"
    cf_09 = "197.234.240.0/22"
    cf_10 = "198.41.128.0/17"
    cf_11 = "162.158.0.0/15"
    cf_12 = "104.16.0.0/13"
    cf_13 = "104.24.0.0/14"
    cf_14 = "172.64.0.0/13"
    cf_15 = "131.0.72.0/22"
  }
  api_bearer_token = var.api_bearer_token == null ? random_password.api_bearer_token[0].result : var.api_bearer_token

  # Built from the environment's default domain rather than the container app's own FQDN: the app
  # consumes this local through api_environment, so reading it back off the app would be a cycle.
  api_default_fqdn = "ca-${local.runtime_name_prefix}-api.${azurerm_container_app_environment.this.default_domain}"

  # Falls back to the Azure origin when no custom hostname is bound, so the issuer is always a host
  # a client can actually reach and check /.well-known/jwks.json against.
  jwt_issuer = coalesce(
    var.jwt_issuer,
    var.api_hostname == null ? "https://${local.api_default_fqdn}" : "https://${var.api_hostname}",
  )

  database_url        = "jdbc:postgresql://${neon_project.database.database_host_pooler}/${neon_project.database.database_name}?sslmode=require"
  flyway_database_url = "jdbc:postgresql://${neon_project.database.database_host}/${neon_project.database.database_name}?sslmode=require"

  common_environment = {
    GREENV_AWS_REGION                = "auto"
    GREENV_AZURE_QUEUE_CREATE        = "false"
    GREENV_AZURE_QUEUE_ENDPOINT      = azurerm_storage_account.queue.primary_queue_endpoint
    GREENV_AZURE_QUEUE_NAME          = azurerm_storage_queue.segment.name
    GREENV_DATABASE_ADAPTER          = "jdbc"
    GREENV_DATABASE_URL              = local.database_url
    GREENV_DATABASE_USER             = neon_project.database.database_user
    GREENV_OBJECT_STORAGE_ADAPTER    = "s3"
    GREENV_S3_BUCKET                 = data.cloudflare_r2_bucket.captures.name
    GREENV_S3_ENDPOINT               = local.r2_endpoint
    GREENV_S3_PATH_STYLE_ACCESS      = "false"
    GREENV_SEGMENT_QUEUE_ADAPTER     = "azure-queue"
    MANAGEMENT_HEALTH_RABBIT_ENABLED = "false"
    SERVER_ADDRESS                   = "0.0.0.0"
  }

  api_environment = merge(local.common_environment, {
    AZURE_CLIENT_ID = azurerm_user_assigned_identity.api.client_id

    # Nominatim's usage policy asks every caller to identify itself and to be reachable. A
    # deployment that will not do that should set GREENV_PLACES_ENABLED to false and get
    # kilometre markers only, rather than send an anonymous request a second.
    GREENV_PLACES_USER_AGENT = "GreenV/${var.environment} (${var.api_hostname})"

    GREENV_RABBITMQ_DYNAMIC                    = "false"
    PORT                                       = "8080"
    SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = "5"
    SPRING_FLYWAY_URL                          = local.flyway_database_url
    SPRING_FLYWAY_USER                         = neon_project.database.database_user

    # Empty leaves CORS disabled, which is what a phone-only deployment wants. A browser client
    # needs its exact origin listed; this never replaces the Bearer token.
    # The other end of the measurement loop. Worker 2 publishes its result here and, without this
    # name, the API's consumer is never created: the queue fills and nothing reads it, which is
    # exactly the gap this deployment exists to close.
    #
    # Set unconditionally, because the queue exists whether or not measurement is enabled - an
    # empty queue costs a poll and a disabled one would need a second apply to switch on.
    GREENV_AZURE_MEASURED_QUEUE_NAME = local.measurement_result_queue_name

    # Named explicitly rather than left to the API's default, because the default pointed at a
    # queue this stack never created and GREENV_AZURE_QUEUE_CREATE is false.
    GREENV_AZURE_MEASURED_POISON_QUEUE_NAME = azurerm_storage_queue.measurement_result_poison.name

    # The dashboard's own origin is derived rather than listed, because forgetting it does not
    # fail anything visibly: CORS is disabled entirely when the list is empty, and a browser that
    # is refused a preflight reports a network error with no server-side trace at all.
    GREENV_ALLOWED_ORIGINS = join(",", distinct(concat(
      var.api_allowed_origins,
      var.dashboard_hostname == null ? [] : ["https://${var.dashboard_hostname}"],
    )))

    # Every token is signed for and validated against this issuer, so it is what answers "did our
    # application mint this?". It must match the host clients actually reach.
    GREENV_JWT_ISSUER   = local.jwt_issuer
    GREENV_JWT_AUDIENCE = var.jwt_audience

    GREENV_ACCESS_TOKEN_TTL       = "PT${var.access_token_ttl_minutes}M"
    GREENV_REFRESH_TOKEN_TTL      = "P${var.refresh_token_ttl_days}D"
    GREENV_CLIENT_CREDENTIALS_TTL = "PT${var.client_credentials_ttl_hours}H"

    # Lax is right while the dashboard and the API share a registrable domain. None would make the
    # session a third-party cookie, which Safari and Firefox already block outright.
    GREENV_COOKIE_SAME_SITE = var.cookie_same_site

    # GREENV_JWT_EPHEMERAL_KEY is deliberately absent and asserted absent in tests/mvp.tftest.hcl.
    # A deployment must never fall back to a key that dies with the process.
  })

  worker_environment = merge(local.common_environment, {
    AZURE_CLIENT_ID                            = azurerm_user_assigned_identity.worker.client_id
    GREENV_AZURE_POISON_QUEUE_NAME             = azurerm_storage_queue.poison.name
    GREENV_AZURE_QUEUE_MAX_DEQUEUE_COUNT       = "5"
    GREENV_AZURE_QUEUE_MAX_MESSAGES            = "1"
    GREENV_LOCAL_POLLING_ENABLED               = "false"
    GREENV_QUEUE_VISIBILITY_SECONDS            = tostring(var.queue_visibility_timeout_seconds)
    GREENV_RABBITMQ_DYNAMIC                    = "false"
    GREENV_RABBITMQ_LISTENER_ENABLED           = "false"
    PORT                                       = "8081"
    SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = "2"

    # Whether a finished segment announces itself for measurement at all. The extractor defaults
    # it to false, and so does this stack: switching it on makes every capture wake a paid GPU
    # with nobody in the loop, and AGENTS.md wants that agreed in conversation rather than
    # inherited from a default. The queue, the consumer and the roles all exist either way, so
    # turning it on is one variable and no new resources.
    GREENV_MEASUREMENT_ENABLED = tostring(var.measurement_enabled)

    # One name for one queue, read by the announcer here and by the consumer below.
    GREENV_MEASUREMENT_QUEUE = azurerm_storage_queue.measurement.name

    # The same queue again, under the name the Azure Queue adapter reads. The line above feeds
    # greenv.measurement.queue, which only the RabbitMQ announcer uses; the Azure client binds
    # greenv.queue.azure-queue.measurement-queue from this one, refuses to be built empty, and
    # took the whole container down with it. The revision ca-greenv-mvp-worker--measurement failed
    # to activate on 9 September 2026 for exactly this, Container Apps kept serving the revision
    # from 7 September, and every capture that day was extracted by the previous image.
    GREENV_AZURE_MEASUREMENT_QUEUE_NAME = azurerm_storage_queue.measurement.name
  })

  # Worker 2, the measurement stage.
  #
  # Built from the same common_environment the two Java services use, because it addresses the same
  # R2 bucket, the same queue account and the same object keys. Two services that disagree about
  # which bucket they are using fail silently, and that failure looks like an empty dashboard
  # rather than an error - the reasoning is written out in
  # services/greenv-measurement-worker/src/config.mjs, which copies these names deliberately.
  # Sharing the map is what makes them agree by construction instead of by review.
  measurement_environment = merge(local.common_environment, local.depth_service_environment, {
    AZURE_CLIENT_ID = azurerm_user_assigned_identity.measurement.client_id

    # Overridden, not inherited. common_environment names the extraction queue, and this worker
    # must never be able to address it: GREENV_AZURE_QUEUE_NAME always means "the queue this
    # service consumes", which here is the measurement one.
    GREENV_AZURE_QUEUE_NAME        = azurerm_storage_queue.measurement.name
    GREENV_AZURE_POISON_QUEUE_NAME = azurerm_storage_queue.measurement_poison.name

    # The three names this worker actually reads. The two above are the Java spelling, which the
    # Node worker does not look at: it refuses to start without these and did, from the moment the
    # revision was created until 10 September 2026, while four announcements sat unread in the
    # queue with dequeueCount 0. Two spellings for one queue is how two services end up talking
    # past each other, and this is the second time it cost a day - the extractor lost one to the
    # same divergence a revision earlier.
    GREENV_AZURE_MEASUREMENT_QUEUE_NAME        = azurerm_storage_queue.measurement.name
    GREENV_AZURE_MEASURED_QUEUE_NAME           = azurerm_storage_queue.measurement_result.name
    GREENV_AZURE_MEASUREMENT_POISON_QUEUE_NAME = azurerm_storage_queue.measurement_poison.name

    GREENV_MEASUREMENT_QUEUE_ENABLED = "true"
    GREENV_MEASUREMENT_QUEUE         = azurerm_storage_queue.measurement.name

    # The worker's own name for where it announces a finished packet, which the API reads. On
    # RabbitMQ that value is a routing key; on Azure Queue there are no exchanges, so the
    # destination is the queue itself. Same name rather than a new one: a second spelling is how
    # two services end up talking past each other.
    GREENV_MEASUREMENT_RESULT_ROUTING_KEY = azurerm_storage_queue.measurement_result.name

    # Two attempts, where extraction gets five. A retry there costs CPU; a retry here wakes the
    # GPU again for the same segment, so five attempts at a message that can never succeed is
    # five machine lifetimes billed. One retry covers a transient depth-service failure; the
    # second failure belongs in the poison queue where a person can look at it.
    #
    # GREENV_MEASUREMENT_MAX_ATTEMPTS, not GREENV_AZURE_QUEUE_MAX_DEQUEUE_COUNT: this container
    # runs the Node worker and nothing else, and the Java spelling set here before had no reader
    # at all. It defaulted to three instead, which is why four segments became twelve RunPod jobs
    # on 10 September 2026 while the endpoint could not start a worker.
    GREENV_MEASUREMENT_MAX_ATTEMPTS = "2"

    # Same correction, same reason: the worker reads this name and never read
    # GREENV_QUEUE_VISIBILITY_SECONDS, so the variable below did nothing to the deployed lease.
    # It matched only because both defaults happen to be 1800 seconds.
    GREENV_MEASUREMENT_VISIBILITY_SECONDS = tostring(var.measurement_queue_visibility_timeout_seconds)

    # Queues are infrastructure; this file declares them and the worker must not.
    GREENV_RABBITMQ_DYNAMIC = "false"

    # Never, in a deployment. Without a reachable depth service the worker falls back to Verge
    # Studio's fixture-backed mock, which answers every request with the same reconstruction of an
    # unrelated scene - a packet built that way pairs this road's frames with someone else's
    # geometry. A mock run was mistaken for a real one on 2026-08-05; this is the switch that
    # stops it happening in the cloud. It is deliberately not a variable.
    GREENV_MEASUREMENT_ALLOW_MOCK = "false"

    GREENV_MEASUREMENT_CLASSES = var.measurement_classes
    GREENV_INFER_ADAPTER       = var.depth_service_adapter
    GREENV_INFER_MAX_FRAMES    = tostring(var.depth_max_frames)

    PORT = "8090"
  })

  # Where the depth service is, and nothing about what it is.
  #
  # It is not created here and never will be: a paid GPU endpoint that bills for the machine's
  # whole lifetime rather than for the seconds it computes (docs/AUTOMATIC-HEIGHT.md). Terraform
  # wires an address and a credential; a person decides the service exists.
  #
  # Both entries are absent when unset, which leaves the worker on its built-in default of
  # http://127.0.0.1:5173/api - the local Vite mock, which is not in this image. It then fails
  # every message instead of inventing a reading, and GREENV_MEASUREMENT_ALLOW_MOCK above refuses
  # the packet even if something did answer.
  depth_service_environment = merge(
    var.depth_service_base_url == null ? {} : { GREENV_INFER_BASE_URL = var.depth_service_base_url },
    local.depth_endpoint_id == null ? {} : { GREENV_INFER_RUNPOD_ENDPOINT_ID = local.depth_endpoint_id },
  )

  shared_secret_environment = {
    GREENV_AWS_ACCESS_KEY    = "r2-access-key"
    GREENV_AWS_SECRET_KEY    = "r2-secret-key"
    GREENV_DATABASE_PASSWORD = "database-password"
  }

  api_secret_environment = merge(local.shared_secret_environment, {
    GREENV_API_TOKEN       = "api-bearer-token"
    GREENV_JWT_PRIVATE_KEY = "jwt-signing-key"
    SPRING_FLYWAY_PASSWORD = "database-password"
  })

  measurement_secret_environment = merge(
    local.shared_secret_environment,
    # One token serves both depth adapters: the HTTP client sends it as a Bearer header and RunPod
    # authenticates its serverless endpoints the same way, so a second variable would only be a
    # second thing to rotate. Absent when unset, so a deployment that has not agreed to GPU spend
    # carries no credential that could start it.
    var.depth_service_token == null ? {} : { GREENV_INFER_TOKEN = "depth-service-token" },
  )

  registry_enabled = try(length(trimspace(var.container_registry.server)), 0) > 0

  default_tags = merge(
    {
      application = var.project_name
      environment = var.environment
      managed-by  = "terraform"
    },
    var.tags,
  )
}

resource "random_string" "resource_suffix" {
  length  = 6
  lower   = true
  numeric = true
  special = false
  upper   = false
}

resource "random_password" "api_bearer_token" {
  count = var.api_bearer_token == null ? 1 : 0

  length  = 48
  special = false
}
