locals {
  name_prefix         = "${var.project_name}-${var.environment}"
  runtime_name_prefix = substr(local.name_prefix, 0, 20)
  suffix              = random_string.resource_suffix.result

  resource_group_name  = "rg-${local.name_prefix}"
  storage_account_name = "${substr(lower(replace("st${var.project_name}${var.environment}", "-", "")), 0, 18)}${local.suffix}"
  segment_queue_name   = "greenv-segment-extract-v2"
  poison_queue_name    = "greenv-segment-extract-poison"
  r2_endpoint          = "https://${var.cloudflare_account_id}.r2.cloudflarestorage.com"
  api_bearer_token     = var.api_bearer_token == null ? random_password.api_bearer_token[0].result : var.api_bearer_token

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
