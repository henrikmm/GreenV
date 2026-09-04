resource "azurerm_container_app" "api" {
  name                         = "ca-${local.runtime_name_prefix}-api"
  container_app_environment_id = azurerm_container_app_environment.this.id
  resource_group_name          = azurerm_resource_group.this.name
  revision_mode                = "Single"
  max_inactive_revisions       = 1
  workload_profile_name        = "Consumption"
  tags                         = local.default_tags

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.api.id]
  }

  secret {
    name  = "database-password"
    value = neon_project.database.database_password
  }

  secret {
    name  = "r2-access-key"
    value = var.r2_access_key_id
  }

  secret {
    name  = "r2-secret-key"
    value = var.r2_secret_access_key
  }

  secret {
    name  = "api-bearer-token"
    value = local.api_bearer_token
  }

  secret {
    name = "jwt-signing-key"
    # Base64 rather than the raw PEM. A Container Apps secret can hold newlines, but the value then
    # travels through the ARM API, a revision template and the container environment, and any one of
    # those normalising a line ending would break the PKCS#8 parse. One line has no newline to lose.
    #
    # private_key_pem_pkcs8, not private_key_pem: the latter is PKCS#1, which the API's key loader
    # rejects.
    value = base64encode(tls_private_key.jwt_signing.private_key_pem_pkcs8)
  }

  dynamic "secret" {
    for_each = local.registry_enabled ? [1] : []
    content {
      name  = "registry-password"
      value = var.container_registry.password
    }
  }

  dynamic "registry" {
    for_each = local.registry_enabled ? [1] : []
    content {
      server               = var.container_registry.server
      username             = var.container_registry.username
      password_secret_name = "registry-password"
    }
  }

  ingress {
    external_enabled           = true
    allow_insecure_connections = false
    target_port                = 8080
    transport                  = "http"

    # `proxied = true` on its own hides nothing: the Azure origin FQDN stays publicly resolvable
    # and reachable. Only these restrictions make Cloudflare the sole path to the API. Container
    # Apps denies every address outside the list once any Allow rule exists.
    dynamic "ip_security_restriction" {
      for_each = var.restrict_api_origin_to_cloudflare ? local.cloudflare_ipv4_cidrs : {}

      content {
        name             = ip_security_restriction.key
        ip_address_range = ip_security_restriction.value
        action           = "Allow"
        description      = "Allow requests from Cloudflare"
      }
    }

    traffic_weight {
      latest_revision = true
      percentage      = 100
    }
  }

  template {
    min_replicas = 0
    max_replicas = var.api_max_replicas

    # Container Apps does not retry a revision it has already marked ActivationFailed, so a bad
    # registry credential leaves one stuck even after the credential is fixed. Changing this rolls
    # a fresh revision, which is the declarative way out of that state.
    revision_suffix = var.deployment_revision

    cooldown_period_in_seconds       = 300
    termination_grace_period_seconds = 30

    container {
      name   = "video-api"
      image  = var.api_image
      cpu    = 1
      memory = "2Gi"

      dynamic "env" {
        for_each = local.api_environment
        content {
          name  = env.key
          value = env.value
        }
      }

      dynamic "env" {
        for_each = local.api_secret_environment
        content {
          name        = env.key
          secret_name = env.value
        }
      }

      startup_probe {
        transport               = "HTTP"
        port                    = 8080
        path                    = "/actuator/health"
        interval_seconds        = 5
        timeout                 = 3
        failure_count_threshold = 30
      }

      liveness_probe {
        transport               = "HTTP"
        port                    = 8080
        path                    = "/actuator/health"
        initial_delay           = 30
        interval_seconds        = 30
        timeout                 = 5
        failure_count_threshold = 3
      }

      readiness_probe {
        transport               = "HTTP"
        port                    = 8080
        path                    = "/actuator/health"
        initial_delay           = 10
        interval_seconds        = 10
        timeout                 = 5
        failure_count_threshold = 6
        success_count_threshold = 1
      }
    }

    http_scale_rule {
      name                = "http-concurrency"
      concurrent_requests = "10"
    }
  }

  depends_on = [azurerm_role_assignment.api_queue_sender]
}

resource "azurerm_container_app" "worker" {
  name                         = "ca-${local.runtime_name_prefix}-worker"
  container_app_environment_id = azurerm_container_app_environment.this.id
  resource_group_name          = azurerm_resource_group.this.name
  revision_mode                = "Single"
  max_inactive_revisions       = 1
  workload_profile_name        = "Consumption"
  tags                         = local.default_tags

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.worker.id]
  }

  secret {
    name  = "database-password"
    value = neon_project.database.database_password
  }

  secret {
    name  = "r2-access-key"
    value = var.r2_access_key_id
  }

  secret {
    name  = "r2-secret-key"
    value = var.r2_secret_access_key
  }

  dynamic "secret" {
    for_each = local.registry_enabled ? [1] : []
    content {
      name  = "registry-password"
      value = var.container_registry.password
    }
  }

  dynamic "registry" {
    for_each = local.registry_enabled ? [1] : []
    content {
      server               = var.container_registry.server
      username             = var.container_registry.username
      password_secret_name = "registry-password"
    }
  }

  template {
    min_replicas    = 0
    max_replicas    = var.worker_max_replicas
    revision_suffix = var.deployment_revision

    polling_interval_in_seconds      = 10
    cooldown_period_in_seconds       = 300
    termination_grace_period_seconds = 120

    container {
      name   = "frame-extractor"
      image  = var.worker_image
      cpu    = 2
      memory = "4Gi"

      dynamic "env" {
        for_each = local.worker_environment
        content {
          name  = env.key
          value = env.value
        }
      }

      dynamic "env" {
        for_each = local.shared_secret_environment
        content {
          name        = env.key
          secret_name = env.value
        }
      }

      startup_probe {
        transport               = "HTTP"
        port                    = 8081
        path                    = "/actuator/health"
        interval_seconds        = 5
        timeout                 = 3
        failure_count_threshold = 30
      }

      liveness_probe {
        transport               = "HTTP"
        port                    = 8081
        path                    = "/actuator/health"
        initial_delay           = 30
        interval_seconds        = 30
        timeout                 = 5
        failure_count_threshold = 3
      }
    }

    custom_scale_rule {
      name             = "segment-queue"
      custom_rule_type = "azure-queue"
      identity_id      = azurerm_user_assigned_identity.worker.id

      metadata = {
        accountName = azurerm_storage_account.queue.name
        queueLength = "1"
        queueName   = azurerm_storage_queue.segment.name
      }
    }
  }

  depends_on = [azurerm_role_assignment.worker_queue_contributor]
}
