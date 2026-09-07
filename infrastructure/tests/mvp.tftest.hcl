mock_provider "azurerm" {}
mock_provider "cloudflare" {}
mock_provider "neon" {}
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
    cloudflare_account_id = "00000000000000000000000000000000"
    r2_bucket_name        = "greenv-mvp-captures-test"
    r2_access_key_id      = "test-access-key"
    r2_secret_access_key  = "test-secret-key"
    api_image             = "ghcr.io/example/greenv-video-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    worker_image          = "ghcr.io/example/greenv-frame-extractor@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    budget_contact_emails = ["finops@example.com"]
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
    condition     = local.worker_environment.GREENV_QUEUE_VISIBILITY_SECONDS == "300"
    error_message = "A failed worker attempt must become visible again after the five-minute MVP lease."
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
    cloudflare_account_id = "00000000000000000000000000000000"
    cloudflare_zone_id    = "11111111111111111111111111111111"
    api_hostname          = "api.example.com"
    r2_bucket_name        = "greenv-mvp-captures-test"
    r2_access_key_id      = "test-access-key"
    r2_secret_access_key  = "test-secret-key"
    api_image             = "ghcr.io/example/greenv-video-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    worker_image          = "ghcr.io/example/greenv-frame-extractor@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
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

run "rolls_both_workloads_on_a_new_deployment_revision" {
  command = plan

  variables {
    cloudflare_account_id = "00000000000000000000000000000000"
    r2_bucket_name        = "greenv-mvp-captures-test"
    r2_access_key_id      = "test-access-key"
    r2_secret_access_key  = "test-secret-key"
    api_image             = "ghcr.io/example/greenv-video-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    worker_image          = "ghcr.io/example/greenv-frame-extractor@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    deployment_revision   = "keep-source"
  }

  assert {
    condition = (
      azurerm_container_app.api.template[0].revision_suffix == "keep-source" &&
      azurerm_container_app.worker.template[0].revision_suffix == "keep-source"
    )
    error_message = "One suffix must roll both workloads, so a stuck revision is recovered in a single apply."
  }

  assert {
    condition     = length("${azurerm_container_app.worker.name}--${var.deployment_revision}") <= 64
    error_message = "The revision name must satisfy Azure's 64-character limit."
  }
}

run "issues_and_binds_a_managed_certificate_for_the_custom_hostname" {
  command = plan

  variables {
    cloudflare_account_id = "00000000000000000000000000000000"
    cloudflare_zone_id    = "11111111111111111111111111111111"
    api_hostname          = "api.example.com"
    r2_bucket_name        = "greenv-mvp-captures-test"
    r2_access_key_id      = "test-access-key"
    r2_secret_access_key  = "test-secret-key"
    api_image             = "ghcr.io/example/greenv-video-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    worker_image          = "ghcr.io/example/greenv-frame-extractor@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
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
    cloudflare_account_id = "00000000000000000000000000000000"
    r2_bucket_name        = "greenv-mvp-captures-test"
    r2_access_key_id      = "test-access-key"
    r2_secret_access_key  = "test-secret-key"
    api_image             = "ghcr.io/example/greenv-video-api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    worker_image          = "ghcr.io/example/greenv-frame-extractor@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  }

  assert {
    condition = (
      length(azurerm_container_app_environment_managed_certificate.api) == 0 &&
      length(azurerm_container_app_custom_domain.api) == 0
    )
    error_message = "A deployment without a custom hostname must not request a certificate."
  }
}
