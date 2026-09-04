locals {
  name_prefix         = "${var.project_name}-${var.environment}"
  runtime_name_prefix = substr(local.name_prefix, 0, 20)
  suffix              = random_string.resource_suffix.result

  resource_group_name  = "rg-${local.name_prefix}"
  storage_account_name = "${substr(lower(replace("st${var.project_name}${var.environment}", "-", "")), 0, 18)}${local.suffix}"
  segment_queue_name   = "greenv-segment-extract-v2"
  poison_queue_name    = "greenv-segment-extract-poison"
  r2_endpoint          = "https://${var.cloudflare_account_id}.r2.cloudflarestorage.com"

  # Every edge rule is scoped to this one hostname. The zone holds unrelated subdomains and a
  # zone-wide rule would reach all of them.
  api_host_expression = var.api_hostname == null ? "" : "http.host eq \"${var.api_hostname}\""

  api_certificate_name = var.api_hostname == null ? "" : "mc-${replace(var.api_hostname, ".", "-")}"

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
    AZURE_CLIENT_ID                            = azurerm_user_assigned_identity.api.client_id
    GREENV_RABBITMQ_DYNAMIC                    = "false"
    PORT                                       = "8080"
    SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = "5"
    SPRING_FLYWAY_URL                          = local.flyway_database_url
    SPRING_FLYWAY_USER                         = neon_project.database.database_user

    # Empty leaves CORS disabled, which is what a phone-only deployment wants. A browser client
    # needs its exact origin listed; this never replaces the Bearer token.
    GREENV_ALLOWED_ORIGINS = join(",", var.api_allowed_origins)

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
  })

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
