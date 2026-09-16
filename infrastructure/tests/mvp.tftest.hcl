mock_provider "azurerm" {}
mock_provider "cloudflare" {}
mock_provider "neon" {}
# The depth endpoint's provider. It builds its API client while configuring, before any resource
# asks it for anything, so it needs mocking even in the runs that create no endpoint.
mock_provider "runpod" {}
mock_provider "random" {}
mock_provider "time" {}
mock_provider "tls" {}

override_resource {
  target          = random_string.resource_suffix
  override_during = plan
  values = {
    result = "abc123"
  }
}

override_resource {
  target          = random_password.api_bearer_token[0]
  override_during = plan
  values = {
    result = "greenv-test-only-bearer-token-000000000000"
  }
}

override_resource {
  target          = neon_project.database
  override_during = plan
  values = {
    id                   = "test-project"
    database_host        = "ep-test.us-east-2.aws.neon.tech"
    database_host_pooler = "ep-test-pooler.us-east-2.aws.neon.tech"
    database_name        = "greenv"
    database_user        = "greenv_owner"
    database_password    = "test-password"
  }
}

# The assembled managed-certificate identifier is only checkable when the environment has a known
# id, which a mocked provider does not give during plan.
override_resource {
  target          = azurerm_container_app_environment.this
  override_during = plan
  values = {
    id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-test/providers/Microsoft.App/managedEnvironments/cae-test"
    # The issuer is derived from this, so it has to be known for the token assertions below.
    default_domain = "test.brazilsouth.azurecontainerapps.io"
  }
}

override_resource {
  target          = tls_private_key.jwt_signing
  override_during = plan
  values = {
    private_key_pem_pkcs8 = "-----BEGIN PRIVATE KEY-----\ntest\n-----END PRIVATE KEY-----\n"
  }
}

override_resource {
  target          = azurerm_container_app_environment_managed_certificate.api[0]
  override_during = plan
  values = {
    id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-test/providers/Microsoft.App/managedEnvironments/cae-test/managedCertificates/mc-api-example-com"
  }
}

override_data {
  target          = data.cloudflare_r2_bucket.captures
  override_during = plan
  values = {
    name          = "greenv-mvp-captures-test"
    storage_class = "Standard"
  }
}

run "plans_cost_conscious_mvp_defaults" {
  command = plan

  variables {
    cloudflare_account_id    = "00000000000000000000000000000000"
    r2_bucket_name           = "greenv-mvp-captures-test"
    r2_access_key_id         = "test-access-key"
    r2_secret_access_key     = "test-secret-key"
    api_image                = "ghcr.io/example/greenv-video-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    worker_image             = "ghcr.io/example/greenv-frame-extractor@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    measurement_worker_image = "ghcr.io/example/greenv-measurement-worker@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    budget_contact_emails    = ["finops@example.com"]
  }

  assert {
    condition     = azurerm_container_app.api.template[0].min_replicas == 0
    error_message = "The API must scale to zero for the MVP."
  }

  assert {
    condition = (
      local.api_secret_environment.GREENV_API_TOKEN == "api-bearer-token" &&
      anytrue([
        for secret in azurerm_container_app.api.secret :
        secret.name == "api-bearer-token" && secret.value == "greenv-test-only-bearer-token-000000000000"
      ])
    )
    error_message = "The API Bearer token must be generated and mounted as a Container Apps secret."
  }

  assert {
    condition = (
      local.api_secret_environment.GREENV_JWT_PRIVATE_KEY == "jwt-signing-key" &&
      anytrue([
        for secret in azurerm_container_app.api.secret :
        secret.name == "jwt-signing-key" &&
        secret.value == base64encode("-----BEGIN PRIVATE KEY-----\ntest\n-----END PRIVATE KEY-----\n")
      ])
    )
    error_message = "The JWT signing key must be provisioned by Terraform and mounted as a Container Apps secret."
  }

  # A deployment must never fall back to a signing key that dies with the process: it would
  # invalidate every token on restart, and two replicas would reject each other's.
  assert {
    condition     = !contains(keys(local.api_environment), "GREENV_JWT_EPHEMERAL_KEY")
    error_message = "GREENV_JWT_EPHEMERAL_KEY must never reach a deployed revision."
  }

  # The worker has no reason to mint or verify tokens, and holding the signing key would make it
  # able to impersonate any user.
  assert {
    condition     = !contains(keys(local.worker_environment), "GREENV_JWT_PRIVATE_KEY")
    error_message = "The frame worker must not receive the JWT signing key."
  }

  # With no custom hostname the issuer falls back to the Azure origin, so it always names a host a
  # client can actually reach and fetch /.well-known/jwks.json from.
  assert {
    condition = (
      startswith(local.api_environment.GREENV_JWT_ISSUER, "https://") &&
      endswith(local.api_environment.GREENV_JWT_ISSUER, ".test.brazilsouth.azurecontainerapps.io") &&
      local.api_environment.GREENV_JWT_AUDIENCE == "greenv-video-api" &&
      local.api_environment.GREENV_ACCESS_TOKEN_TTL == "PT15M" &&
      local.api_environment.GREENV_REFRESH_TOKEN_TTL == "P7D" &&
      local.api_environment.GREENV_CLIENT_CREDENTIALS_TTL == "PT4H" &&
      local.api_environment.GREENV_COOKIE_SAME_SITE == "Lax"
    )
    error_message = "Token issuer, audience, lifetimes and cookie policy must reach the API."
  }

  assert {
    condition     = azurerm_container_app.worker.template[0].min_replicas == 0
    error_message = "The frame worker must scale to zero for the MVP."
  }

  assert {
    condition     = local.worker_environment.GREENV_QUEUE_VISIBILITY_SECONDS == "1800"
    error_message = "A failed worker attempt must become visible again after the thirty-minute lease, which is what an extraction that publishes every group needs (4 to 5 minutes end to end on 16 September 2026, and the extractor does not renew)."
  }

  assert {
    condition     = azurerm_container_app_environment.this.logs_destination == "log-analytics"
    error_message = "The Container Apps environment must explicitly send logs to its Log Analytics workspace."
  }

  assert {
    condition = (
      one([for profile in azurerm_container_app_environment.this.workload_profile : profile.workload_profile_type]) == "Consumption" &&
      azurerm_container_app.api.workload_profile_name == "Consumption" &&
      azurerm_container_app.worker.workload_profile_name == "Consumption"
    )
    error_message = "Leaving the profile undeclared makes every plan propose removing it, which cannot be applied safely."
  }

  assert {
    condition = (
      azurerm_container_app.worker.template[0].custom_scale_rule[0].custom_rule_type == "azure-queue" &&
      azurerm_container_app.worker.template[0].custom_scale_rule[0].metadata.queueLength == "1"
    )
    error_message = "Queue scaling must stay a custom azure-queue rule, which is the only shape that carries a managed identity."
  }

  assert {
    condition     = length(azurerm_container_app.worker.ingress) == 0
    error_message = "The worker must not expose public or internal ingress."
  }

  assert {
    condition = (
      azurerm_container_app.measurement.template[0].min_replicas == 0 &&
      azurerm_container_app.measurement.workload_profile_name == "Consumption"
    )
    error_message = "The measurement worker must scale to zero: it is idle between drives and its image is over a gigabyte to keep warm."
  }

  # One segment is one whole depth run. A 112-frame run fills an L4 to 99.95% of its 22.03 GiB
  # usable (measurement/docs/REGISTRY.md), so a second replica cannot get a GPU - it would only
  # wait inside the depth service's own lock while billing a second Container Apps replica.
  assert {
    condition     = azurerm_container_app.measurement.template[0].max_replicas == 1
    error_message = "The measurement worker must default to one replica; a second cannot get a GPU."
  }

  # Container Apps Consumption accepts memory only at 2 GiB per vCPU, so changing one number
  # without the other fails at apply rather than here. This is the invariant behind the sizing
  # comment in container-apps.tf, which is reasoning rather than a measured working set.
  assert {
    condition = alltrue([
      for container in [
        azurerm_container_app.api.template[0].container[0],
        azurerm_container_app.worker.template[0].container[0],
        azurerm_container_app.measurement.template[0].container[0],
      ] : tonumber(trimsuffix(container.memory, "Gi")) == container.cpu * 2
    ])
    error_message = "Container Apps Consumption only accepts 2 GiB of memory per vCPU."
  }

  assert {
    condition = (
      azurerm_container_app.measurement.template[0].container[0].cpu == 4 &&
      azurerm_container_app.measurement.template[0].container[0].memory == "8Gi"
    )
    error_message = "The measurement worker runs a second segmentation on every frame that takes 3.9 s on two ONNX threads and 1.3 s on four; four vCPU is the Consumption ceiling and brings 8 GiB with it."
  }

  # Scaling on the extraction queue would wake this worker for every segment cut, GPU and all.
  # The identities cannot be compared here - a mocked provider gives no id during plan - so the
  # separation is asserted on the names, which are literals.
  assert {
    condition = (
      azurerm_container_app.measurement.template[0].custom_scale_rule[0].custom_rule_type == "azure-queue" &&
      azurerm_container_app.measurement.template[0].custom_scale_rule[0].metadata.queueName == azurerm_storage_queue.measurement.name &&
      azurerm_container_app.measurement.template[0].custom_scale_rule[0].metadata.queueName != azurerm_storage_queue.segment.name &&
      azurerm_container_app.measurement.template[0].custom_scale_rule[0].metadata.queueLength == "1"
    )
    error_message = "The measurement worker must scale on its own queue through a managed identity, one segment at a time."
  }

  assert {
    condition = (
      length(azurerm_container_app.measurement.identity[0].identity_ids) == 1 &&
      azurerm_user_assigned_identity.measurement.name != azurerm_user_assigned_identity.worker.name
    )
    error_message = "The measurement worker must run as its own managed identity, not the frame worker's."
  }

  # POST /measurements has no authentication of its own and every accepted call wakes a paid GPU.
  assert {
    condition     = length(azurerm_container_app.measurement.ingress) == 0
    error_message = "The measurement worker's manual trigger must not be reachable; it spends GPU money on request."
  }

  # Without a reachable depth service the worker falls back to a fixture-backed mock that answers
  # with an unrelated scene's geometry. A mock run was mistaken for a real one on 2026-08-05.
  assert {
    condition     = local.measurement_environment.GREENV_MEASUREMENT_ALLOW_MOCK == "false"
    error_message = "A deployed revision must never publish a mock packet."
  }

  # A lease that expires mid-run redelivers the message and pays for the same segment's GPU twice.
  #
  # Under the names the Node worker actually reads. The Java spellings were set here until
  # 11 September 2026 and had no reader on this container at all, so the attempt limit fell back
  # to its own default of three: four segments became twelve RunPod jobs the day before, while
  # the endpoint could not start a worker.
  assert {
    condition = (
      local.measurement_environment.GREENV_MEASUREMENT_VISIBILITY_SECONDS == "1800" &&
      local.measurement_environment.GREENV_MEASUREMENT_MAX_ATTEMPTS == "2"
    )
    error_message = "A measurement lease must outlive the slowest run, and a doomed message must not be retried at GPU prices under a name the worker never reads."
  }

  # The shared map names the extraction queue, so the override is the thing that keeps this worker
  # off another worker's messages. It reads the same bucket by construction, which is the reason
  # the map is shared at all.
  assert {
    condition = (
      local.measurement_environment.GREENV_AZURE_QUEUE_NAME == azurerm_storage_queue.measurement.name &&
      local.measurement_environment.GREENV_AZURE_POISON_QUEUE_NAME == azurerm_storage_queue.measurement_poison.name &&
      local.measurement_environment.GREENV_MEASUREMENT_RESULT_ROUTING_KEY == azurerm_storage_queue.measurement_result.name &&
      local.measurement_environment.GREENV_S3_BUCKET == local.common_environment.GREENV_S3_BUCKET
    )
    error_message = "The measurement worker must address its own three queues and the same bucket the extractor wrote the frames to."
  }

  # The API defaults this name to greenv-segment-measured-poison and nothing created it, so an
  # announcement it could never read had nowhere to go: the send failed, was caught and logged,
  # and the message reappeared for ever. Message Processor cannot add, hence the second grant.
  assert {
    condition = (
      local.api_environment.GREENV_AZURE_MEASURED_POISON_QUEUE_NAME == azurerm_storage_queue.measurement_result_poison.name &&
      azurerm_role_assignment.api_measurement_result_poison_sender.role_definition_name == "Storage Queue Data Message Sender"
    )
    error_message = "The API must be able to move an unrecordable measurement announcement to a queue that exists."
  }

  # The zone's firewall is left alone unless its ruleset id is named, because adopting it means
  # owning rules that belong to other subdomains.
  assert {
    condition     = length(cloudflare_ruleset.zone_firewall) == 0
    error_message = "A deployment that has not named a ruleset id must not manage the zone's firewall."
  }

  # Without a dashboard there is no origin to allow, and an allowed origin nobody serves is a
  # standing permission for a host that does not exist.
  assert {
    condition     = local.api_environment.GREENV_ALLOWED_ORIGINS == ""
    error_message = "A deployment with no dashboard and no configured origins must leave CORS off."
  }

  # False by default. Turning it on makes every capture wake a paid GPU, which AGENTS.md wants
  # agreed in conversation rather than inherited from a default.
  assert {
    condition = (
      local.worker_environment.GREENV_MEASUREMENT_ENABLED == "false" &&
      local.worker_environment.GREENV_MEASUREMENT_QUEUE == azurerm_storage_queue.measurement.name
    )
    error_message = "Automatic measurement must stay off by default, and the announcer must name the queue its consumer reads."
  }

  assert {
    condition = (
      azurerm_role_assignment.measurement_queue_contributor.role_definition_name == "Storage Queue Data Contributor" &&
      azurerm_role_assignment.measurement_result_sender.role_definition_name == "Storage Queue Data Message Sender" &&
      azurerm_role_assignment.measurement_poison_sender.role_definition_name == "Storage Queue Data Message Sender"
    )
    error_message = "The measurement identity must drain its own queue and only publish to the result and poison queues."
  }

  assert {
    condition = length(distinct([
      azurerm_storage_queue.segment.name,
      azurerm_storage_queue.poison.name,
      azurerm_storage_queue.measurement.name,
      azurerm_storage_queue.measurement_result.name,
      azurerm_storage_queue.measurement_poison.name,
      azurerm_storage_queue.measurement_result_poison.name,
    ])) == 6
    error_message = "Every queue in the account must be distinct; sharing one would run the wrong stage on a redelivery."
  }

  # Nothing has authorised GPU spend, so no depth credential is mounted.
  assert {
    condition = (
      length([for secret in azurerm_container_app.measurement.secret : secret if secret.name == "depth-service-token"]) == 0 &&
      !contains(keys(local.measurement_secret_environment), "GREENV_INFER_TOKEN")
    )
    error_message = "The depth-service secret must exist only when a token is configured."
  }

  assert {
    condition     = azurerm_storage_account.queue.account_replication_type == "LRS"
    error_message = "The MVP queue account must use cost-conscious LRS replication."
  }

  assert {
    condition     = azurerm_storage_account.queue.shared_access_key_enabled == false
    error_message = "The Azure queue runtime must authenticate with managed identity."
  }

  assert {
    condition     = length(azurerm_storage_account.queue.name) <= 24
    error_message = "The queue storage account name must satisfy Azure's 24-character limit."
  }

  assert {
    condition     = length(azurerm_container_app.api.name) <= 32 && length(azurerm_container_app.worker.name) <= 32
    error_message = "Container App names must satisfy Azure's 32-character limit."
  }

  assert {
    condition     = data.cloudflare_r2_bucket.captures.storage_class == "Standard"
    error_message = "The pre-created application bucket must use R2 Standard storage."
  }

  assert {
    condition     = startswith(local.database_url, "jdbc:postgresql://ep-test-pooler") && startswith(local.flyway_database_url, "jdbc:postgresql://ep-test-pooler") == false
    error_message = "Runtime JDBC must use the pooler while Flyway uses Neon's direct endpoint."
  }

  assert {
    condition     = neon_project.database.default_endpoint_settings[0].autoscaling_limit_min_cu == 0.25 && neon_project.database.default_endpoint_settings[0].autoscaling_limit_max_cu == 1
    error_message = "The Neon project must retain the MVP autoscaling range while the account controls its suspension interval."
  }

  assert {
    condition     = azurerm_consumption_budget_resource_group.this[0].amount == 30
    error_message = "A contact email must enable the default monthly Azure budget."
  }

  assert {
    condition     = azurerm_storage_queue.segment.name != azurerm_storage_queue.poison.name
    error_message = "The source and poison queues must be separate."
  }

  assert {
    condition     = azurerm_role_assignment.api_queue_sender.role_definition_name == "Storage Queue Data Message Sender"
    error_message = "The API identity must only publish queue messages."
  }

  assert {
    condition     = azurerm_role_assignment.worker_queue_contributor.role_definition_name == "Storage Queue Data Contributor"
    error_message = "The worker identity must be able to receive, retry and poison messages."
  }
}

run "plans_dns_only_custom_domain" {
  command = plan

  variables {
    cloudflare_account_id    = "00000000000000000000000000000000"
    cloudflare_zone_id       = "11111111111111111111111111111111"
    api_hostname             = "api.example.com"
    r2_bucket_name           = "greenv-mvp-captures-test"
    r2_access_key_id         = "test-access-key"
    r2_secret_access_key     = "test-secret-key"
    api_image                = "ghcr.io/example/greenv-video-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    worker_image             = "ghcr.io/example/greenv-frame-extractor@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    measurement_worker_image = "ghcr.io/example/greenv-measurement-worker@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    container_registry = {
      server   = "ghcr.io"
      username = "greenv-ci"
      password = "test-registry-token"
    }
  }


  # A bound custom hostname wins: tokens must claim the host clients actually call, which is also
  # the host whose /.well-known/jwks.json a verifier will fetch.
  assert {
    condition     = local.api_environment.GREENV_JWT_ISSUER == "https://api.example.com"
    error_message = "The token issuer must follow the API's custom hostname when one is bound."
  }

  assert {
    condition     = cloudflare_dns_record.api_cname[0].proxied == false
    error_message = "Azure managed-certificate DNS must initially remain unproxied."
  }

  assert {
    condition     = cloudflare_dns_record.api_cname[0].type == "CNAME" && cloudflare_dns_record.api_verification[0].type == "TXT"
    error_message = "The custom domain needs both Azure origin and ownership records."
  }

  assert {
    condition     = time_sleep.api_dns_propagation[0].create_duration == "60s"
    error_message = "Azure domain validation must wait for the Cloudflare records to become publicly resolvable."
  }

  assert {
    condition     = azurerm_container_app.api.registry[0].server == "ghcr.io" && azurerm_container_app.worker.registry[0].server == "ghcr.io"
    error_message = "Private registry credentials must configure both workloads."
  }
}

run "plans_dns_only_edge_defaults" {
  command = plan

  variables {
    cloudflare_account_id             = "00000000000000000000000000000000"
    cloudflare_zone_id                = "11111111111111111111111111111111"
    api_hostname                      = "api.example.com"
    r2_bucket_name                    = "greenv-mvp-captures-test"
    r2_access_key_id                  = "test-access-key"
    r2_secret_access_key              = "test-secret-key"
    api_image                         = "ghcr.io/example/greenv-video-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    worker_image                      = "ghcr.io/example/greenv-frame-extractor@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    measurement_worker_image          = "ghcr.io/example/greenv-measurement-worker@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    cloudflare_proxy_enabled          = false
    restrict_api_origin_to_cloudflare = false
  }

  assert {
    condition     = cloudflare_dns_record.api_cname[0].proxied == false
    error_message = "Phase one must stay DNS-only so Azure can issue the managed certificate."
  }

  assert {
    condition     = cloudflare_dns_record.api_verification[0].proxied == false
    error_message = "The asuid ownership TXT record must never be proxied."
  }

  assert {
    condition     = length(azurerm_container_app.api.ingress[0].ip_security_restriction) == 0
    error_message = "The origin must accept direct traffic while the certificate is still being issued."
  }

  assert {
    condition     = length(cloudflare_ruleset.api_waf) == 0 && length(cloudflare_ruleset.api_rate_limit) == 0
    error_message = "WAF and rate limiting must stay off until they are explicitly enabled."
  }

  assert {
    condition     = length(cloudflare_ruleset.api_cache_bypass) == 0
    error_message = "Phase one must need only DNS permissions on the Cloudflare token."
  }

  assert {
    condition     = local.api_environment.GREENV_ALLOWED_ORIGINS == ""
    error_message = "CORS must stay disabled unless an origin is configured."
  }
}

run "plans_protected_edge_configuration" {
  command = plan

  variables {
    cloudflare_account_id               = "00000000000000000000000000000000"
    cloudflare_zone_id                  = "11111111111111111111111111111111"
    api_hostname                        = "api.example.com"
    r2_bucket_name                      = "greenv-mvp-captures-test"
    r2_access_key_id                    = "test-access-key"
    r2_secret_access_key                = "test-secret-key"
    api_image                           = "ghcr.io/example/greenv-video-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    worker_image                        = "ghcr.io/example/greenv-frame-extractor@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    measurement_worker_image            = "ghcr.io/example/greenv-measurement-worker@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    api_allowed_origins                 = ["http://localhost:5173"]
    cloudflare_proxy_enabled            = true
    restrict_api_origin_to_cloudflare   = true
    cloudflare_api_cache_bypass_enabled = true
    cloudflare_api_waf_enabled          = true
    cloudflare_api_rate_limit_enabled   = true
  }

  assert {
    condition     = cloudflare_dns_record.api_cname[0].proxied == true
    error_message = "Phase two must proxy the API record through Cloudflare."
  }

  assert {
    condition     = length(azurerm_container_app.api.ingress[0].ip_security_restriction) == 15
    error_message = "The origin must allow every published Cloudflare IPv4 range and nothing else."
  }

  assert {
    condition = alltrue([
      for restriction in azurerm_container_app.api.ingress[0].ip_security_restriction :
      restriction.action == "Allow"
    ])
    error_message = "Mixing Allow and Deny rules would stop Azure denying every other address implicitly."
  }

  assert {
    condition     = cloudflare_ruleset.api_cache_bypass[0].rules[0].action_parameters.cache == false
    error_message = "The API hostname must bypass the Cloudflare cache."
  }

  assert {
    condition = (
      cloudflare_ruleset.api_cache_bypass[0].phase == "http_request_cache_settings" &&
      cloudflare_ruleset.api_cache_bypass[0].rules[0].expression == "http.host eq \"api.example.com\""
    )
    error_message = "Cache bypass must be limited to the API hostname, not applied zone-wide."
  }

  assert {
    condition     = strcontains(cloudflare_ruleset.api_waf[0].rules[0].expression, "http.host eq \"api.example.com\"")
    error_message = "The WAF rule must be scoped to the API hostname."
  }

  assert {
    condition     = cloudflare_ruleset.api_waf[0].rules[0].action == "block"
    error_message = "Unexpected HTTP methods must be blocked, not logged."
  }

  assert {
    condition = (
      cloudflare_ruleset.api_rate_limit[0].rules[0].expression == "http.host eq \"api.example.com\"" &&
      cloudflare_ruleset.api_rate_limit[0].rules[0].ratelimit.requests_per_period == 120 &&
      cloudflare_ruleset.api_rate_limit[0].rules[0].ratelimit.period == 60
    )
    error_message = "Rate limiting must apply the documented per-hostname ceiling."
  }

  assert {
    condition     = local.api_environment.GREENV_ALLOWED_ORIGINS == "http://localhost:5173"
    error_message = "Configured browser origins must reach the API container."
  }

  assert {
    condition     = length(cloudflare_zone_setting.ssl_strict) == 0
    error_message = "Zone-wide TLS settings must stay untouched without their own flag."
  }
}

run "rejects_origin_restriction_without_the_proxy" {
  command = plan

  variables {
    cloudflare_account_id             = "00000000000000000000000000000000"
    cloudflare_zone_id                = "11111111111111111111111111111111"
    api_hostname                      = "api.example.com"
    r2_bucket_name                    = "greenv-mvp-captures-test"
    r2_access_key_id                  = "test-access-key"
    r2_secret_access_key              = "test-secret-key"
    api_image                         = "ghcr.io/example/greenv-video-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    worker_image                      = "ghcr.io/example/greenv-frame-extractor@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    measurement_worker_image          = "ghcr.io/example/greenv-measurement-worker@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    cloudflare_proxy_enabled          = false
    restrict_api_origin_to_cloudflare = true
  }

  expect_failures = [var.restrict_api_origin_to_cloudflare]
}

run "manages_zone_tls_only_behind_its_flag" {
  command = plan

  variables {
    cloudflare_account_id                    = "00000000000000000000000000000000"
    cloudflare_zone_id                       = "11111111111111111111111111111111"
    api_hostname                             = "api.example.com"
    r2_bucket_name                           = "greenv-mvp-captures-test"
    r2_access_key_id                         = "test-access-key"
    r2_secret_access_key                     = "test-secret-key"
    api_image                                = "ghcr.io/example/greenv-video-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    worker_image                             = "ghcr.io/example/greenv-frame-extractor@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    measurement_worker_image                 = "ghcr.io/example/greenv-measurement-worker@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    manage_cloudflare_zone_security_settings = true
  }

  assert {
    condition = (
      cloudflare_zone_setting.ssl_strict[0].value == "strict" &&
      cloudflare_zone_setting.minimum_tls_version[0].value == "1.2" &&
      cloudflare_zone_setting.tls_1_3[0].value == "on"
    )
    error_message = "The flag must apply strict SSL, TLS 1.2 minimum and TLS 1.3."
  }

  assert {
    condition     = azurerm_container_app.api.ingress[0].allow_insecure_connections == false
    error_message = "The Azure origin must keep refusing plaintext."
  }
}

run "rolls_every_workload_on_a_new_deployment_revision" {
  command = plan

  variables {
    cloudflare_account_id    = "00000000000000000000000000000000"
    r2_bucket_name           = "greenv-mvp-captures-test"
    r2_access_key_id         = "test-access-key"
    r2_secret_access_key     = "test-secret-key"
    api_image                = "ghcr.io/example/greenv-video-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    worker_image             = "ghcr.io/example/greenv-frame-extractor@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    measurement_worker_image = "ghcr.io/example/greenv-measurement-worker@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    deployment_revision      = "keep-source"
  }

  assert {
    condition = (
      azurerm_container_app.api.template[0].revision_suffix == "keep-source" &&
      azurerm_container_app.worker.template[0].revision_suffix == "keep-source" &&
      azurerm_container_app.measurement.template[0].revision_suffix == "keep-source"
    )
    error_message = "One suffix must roll all three workloads, so a stuck revision is recovered in a single apply."
  }

  assert {
    condition = alltrue([
      for name in [azurerm_container_app.worker.name, azurerm_container_app.measurement.name] :
      length("${name}--${var.deployment_revision}") <= 64
    ])
    error_message = "The revision name must satisfy Azure's 64-character limit."
  }
}

# Turning measurement on is the deliberate act that authorises GPU spend, so the wiring it needs is
# asserted separately from the default deployment above.
# The half that has no worker of its own: worker 2 publishes a result and the API drains it. A
# queue nobody reads is the defect this whole stage exists to remove, and it is invisible in a plan
# unless something asserts it.
run "closes_the_measurement_loop_at_the_api" {
  command = plan

  variables {
    cloudflare_account_id    = "00000000000000000000000000000000"
    r2_bucket_name           = "greenv-mvp-captures-test"
    r2_access_key_id         = "test-access-key"
    r2_secret_access_key     = "test-secret-key"
    api_image                = "ghcr.io/example/greenv-video-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    worker_image             = "ghcr.io/example/greenv-frame-extractor@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    measurement_worker_image = "ghcr.io/example/greenv-measurement-worker@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
  }

  assert {
    condition     = local.api_environment.GREENV_AZURE_MEASURED_QUEUE_NAME == local.measurement_result_queue_name
    error_message = "Without the queue name the API builds no consumer, and worker 2's results pile up unread."
  }

  # Sending is what the API does with extraction work; draining a result queue is a different verb.
  assert {
    condition     = azurerm_role_assignment.api_measurement_result_processor.role_definition_name == "Storage Queue Data Message Processor"
    error_message = "The API needs to receive and delete from the result queue, which its account-scoped Sender role does not allow."
  }

  # The scope is the queue's ARM id, unknown until the storage account exists, so a plan cannot
  # compare it. What a plan can prove is that the grant is not the account-wide one: it carries a
  # principal of its own and a role that is not Sender.
  assert {
    condition     = azurerm_role_assignment.api_measurement_result_processor.principal_type == "ServicePrincipal"
    error_message = "The grant must belong to the API's managed identity."
  }
}

run "plans_automatic_measurement_against_a_depth_service" {
  command = plan

  variables {
    cloudflare_account_id    = "00000000000000000000000000000000"
    r2_bucket_name           = "greenv-mvp-captures-test"
    r2_access_key_id         = "test-access-key"
    r2_secret_access_key     = "test-secret-key"
    api_image                = "ghcr.io/example/greenv-video-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    worker_image             = "ghcr.io/example/greenv-frame-extractor@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    measurement_worker_image = "ghcr.io/example/greenv-measurement-worker@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    measurement_enabled      = true
    depth_service_base_url   = "https://verge-da3.example.com/api"
    depth_service_token      = "test-depth-token"
  }

  assert {
    condition = (
      local.worker_environment.GREENV_MEASUREMENT_ENABLED == "true" &&
      local.measurement_environment.GREENV_INFER_BASE_URL == "https://verge-da3.example.com/api" &&
      local.measurement_environment.GREENV_INFER_ADAPTER == "http"
    )
    error_message = "Enabling measurement must both start the announcements and name the depth service that answers them."
  }

  assert {
    condition = (
      local.measurement_secret_environment.GREENV_INFER_TOKEN == "depth-service-token" &&
      anytrue([
        for secret in azurerm_container_app.measurement.secret :
        secret.name == "depth-service-token" && secret.value == "test-depth-token"
      ])
    )
    error_message = "The depth credential must travel as a Container Apps secret, never as a plain environment value."
  }

  # 112 frames at 504 px is Verge Studio's best graded setting and already peaked at 99.95% of an
  # L4's 22.03 GiB usable; the extractor caps its own sampling there (measurement/docs/REGISTRY.md).
  assert {
    condition     = local.measurement_environment.GREENV_INFER_MAX_FRAMES == "112"
    error_message = "A run must not be sent more frames than an L4 has been observed to survive."
  }

  # The union, not `terrain` alone: Cityscapes files vertically growing plants under `vegetation`,
  # and `terrain` alone reads 0.000 m on a plant taped at 0.980 m
  # (measurement/docs/evidence/2026-09-05-class-fit.md). No policy here is validated.
  assert {
    condition     = local.measurement_environment.GREENV_MEASUREMENT_CLASSES == "terrain,vegetation"
    error_message = "The class policy must reach the worker, because roçada is about the vegetation class."
  }
}

run "plans_a_runpod_depth_endpoint" {
  command = plan

  variables {
    cloudflare_account_id     = "00000000000000000000000000000000"
    r2_bucket_name            = "greenv-mvp-captures-test"
    r2_access_key_id          = "test-access-key"
    r2_secret_access_key      = "test-secret-key"
    api_image                 = "ghcr.io/example/greenv-video-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    worker_image              = "ghcr.io/example/greenv-frame-extractor@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    measurement_worker_image  = "ghcr.io/example/greenv-measurement-worker@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    measurement_enabled       = true
    depth_service_adapter     = "runpod"
    depth_service_endpoint_id = "abc123def456"
    depth_service_token       = "test-runpod-api-key"
  }

  assert {
    condition = (
      local.measurement_environment.GREENV_INFER_ADAPTER == "runpod" &&
      local.measurement_environment.GREENV_INFER_RUNPOD_ENDPOINT_ID == "abc123def456" &&
      !contains(keys(local.measurement_environment), "GREENV_INFER_BASE_URL")
    )
    error_message = "A RunPod deployment must name its endpoint and not also carry a plain base URL to disagree with."
  }
}

# The other half of the RunPod path: Terraform creating the endpoint rather than being told one.
# The template it is built from is not Terraform's - this provider has a data source for templates
# and no resource - so it is created by services/greenv-depth-runpod/provision.mjs and found here
# by name.
run "creates_the_depth_endpoint_from_the_provisioned_template" {
  command = plan

  variables {
    cloudflare_account_id    = "00000000000000000000000000000000"
    r2_bucket_name           = "greenv-mvp-captures-test"
    r2_access_key_id         = "test-access-key"
    r2_secret_access_key     = "test-secret-key"
    api_image                = "ghcr.io/example/greenv-video-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    worker_image             = "ghcr.io/example/greenv-frame-extractor@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    measurement_worker_image = "ghcr.io/example/greenv-measurement-worker@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    measurement_enabled      = true
    depth_service_adapter    = "runpod"
    depth_service_token      = "test-runpod-api-key"
    runpod_api_key           = "test-runpod-api-key"
    depth_template_id        = "tpl-test"
  }

  assert {
    condition     = length(runpod_endpoint.depth) == 1
    error_message = "A template id is what asks Terraform to create the endpoint."
  }

  assert {
    condition     = runpod_endpoint.depth[0].template_id == "tpl-test"
    error_message = "The endpoint must be built from the template of that name, not from whichever came first."
  }

  # Zero at rest is what keeps an idle GPU from being a bill.
  assert {
    condition     = runpod_endpoint.depth[0].workers_min == 0
    error_message = "An endpoint that keeps a worker warm at rest bills for a GPU nobody is using."
  }

  # One at work: a second worker is a second cold start, not more throughput.
  assert {
    condition     = runpod_endpoint.depth[0].workers_max == 1
    error_message = "One segment is one inference; a second worker cannot get a GPU and would bill anyway."
  }

  assert {
    condition     = tolist(runpod_endpoint.depth[0].gpu_type_ids) == tolist(["NVIDIA L4"])
    error_message = "Every memory ceiling on record was measured on an L4; another card is a promise this repository cannot keep."
  }

  assert {
    condition     = runpod_endpoint.depth[0].execution_timeout_ms == 900000
    error_message = "A request that outlives the worst recorded run plus its cold start is a fault, and paying past it buys nothing."
  }
}

# Terraform creates the endpoint and provisions the template under it, and both are API calls.
# Without a key it would fail halfway through an apply, from two places, with a 401.
run "rejects_a_runpod_deployment_with_no_api_key" {
  command = plan

  variables {
    cloudflare_account_id    = "00000000000000000000000000000000"
    r2_bucket_name           = "greenv-mvp-captures-test"
    r2_access_key_id         = "test-access-key"
    r2_secret_access_key     = "test-secret-key"
    api_image                = "ghcr.io/example/greenv-video-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    worker_image             = "ghcr.io/example/greenv-frame-extractor@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    measurement_worker_image = "ghcr.io/example/greenv-measurement-worker@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    measurement_enabled      = true
    depth_service_adapter    = "runpod"
    depth_service_token      = "test-runpod-api-key"
  }

  expect_failures = [var.measurement_enabled]
}

# Announcing segments with nowhere to send them fills the poison queue, and every message in it
# would have cost GPU time to get there.
run "rejects_automatic_measurement_without_a_depth_service" {
  command = plan

  variables {
    cloudflare_account_id    = "00000000000000000000000000000000"
    r2_bucket_name           = "greenv-mvp-captures-test"
    r2_access_key_id         = "test-access-key"
    r2_secret_access_key     = "test-secret-key"
    api_image                = "ghcr.io/example/greenv-video-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    worker_image             = "ghcr.io/example/greenv-frame-extractor@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    measurement_worker_image = "ghcr.io/example/greenv-measurement-worker@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    measurement_enabled      = true
  }

  expect_failures = [var.measurement_enabled]
}

run "rejects_a_runpod_endpoint_without_an_api_key" {
  command = plan

  variables {
    cloudflare_account_id     = "00000000000000000000000000000000"
    r2_bucket_name            = "greenv-mvp-captures-test"
    r2_access_key_id          = "test-access-key"
    r2_secret_access_key      = "test-secret-key"
    api_image                 = "ghcr.io/example/greenv-video-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    worker_image              = "ghcr.io/example/greenv-frame-extractor@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    measurement_worker_image  = "ghcr.io/example/greenv-measurement-worker@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    measurement_enabled       = true
    depth_service_adapter     = "runpod"
    depth_service_endpoint_id = "abc123def456"
  }

  expect_failures = [var.measurement_enabled]
}

run "issues_and_binds_a_managed_certificate_for_the_custom_hostname" {
  command = plan

  variables {
    cloudflare_account_id    = "00000000000000000000000000000000"
    cloudflare_zone_id       = "11111111111111111111111111111111"
    api_hostname             = "api.example.com"
    r2_bucket_name           = "greenv-mvp-captures-test"
    r2_access_key_id         = "test-access-key"
    r2_secret_access_key     = "test-secret-key"
    api_image                = "ghcr.io/example/greenv-video-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    worker_image             = "ghcr.io/example/greenv-frame-extractor@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    measurement_worker_image = "ghcr.io/example/greenv-measurement-worker@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
  }

  assert {
    condition     = azurerm_container_app_environment_managed_certificate.api[0].subject_name == "api.example.com"
    error_message = "The managed certificate must cover the API hostname."
  }

  assert {
    condition     = azurerm_container_app_environment_managed_certificate.api[0].domain_control_validation == "CNAME"
    error_message = "Ownership must be proven from public DNS, which is why the record stays unproxied."
  }

  assert {
    condition     = azurerm_container_app_custom_domain.api[0].name == "api.example.com"
    error_message = "The registered hostname must be the configured one."
  }

  assert {
    condition     = azurerm_container_app_environment_managed_certificate.api[0].name == local.api_certificate_name
    error_message = "The certificate must be named after the hostname it covers."
  }

  assert {
    condition = alltrue([
      for fragment in [
        "az containerapp hostname bind",
        "--hostname api.example.com",
        "--certificate /subscriptions/",
      ] : strcontains(local.api_certificate_bind_command, fragment)
    ])
    error_message = "The provider cannot bind a managed certificate, so the CLI call is the only thing that completes the hostname."
  }
}

run "omits_the_certificate_without_a_custom_hostname" {
  command = plan

  variables {
    cloudflare_account_id    = "00000000000000000000000000000000"
    r2_bucket_name           = "greenv-mvp-captures-test"
    r2_access_key_id         = "test-access-key"
    r2_secret_access_key     = "test-secret-key"
    api_image                = "ghcr.io/example/greenv-video-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    worker_image             = "ghcr.io/example/greenv-frame-extractor@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    measurement_worker_image = "ghcr.io/example/greenv-measurement-worker@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
  }

  assert {
    condition = (
      length(azurerm_container_app_environment_managed_certificate.api) == 0 &&
      length(azurerm_container_app_custom_domain.api) == 0
    )
    error_message = "A deployment without a custom hostname must not request a certificate."
  }
}
