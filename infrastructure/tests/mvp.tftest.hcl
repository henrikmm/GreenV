mock_provider "azurerm" {}
mock_provider "cloudflare" {}
mock_provider "neon" {}
mock_provider "random" {}
mock_provider "time" {}

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
