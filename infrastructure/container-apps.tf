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

    # A finished measurement is announced on a queue the API polls, and the API scales to zero
    # on HTTP traffic alone. On 14 September 2026 four results arrived a minute after KEDA had
    # deactivated the API's only replica and sat in the queue, invisible to the dashboard, until
    # a request happened to wake it. A pending result is a reason to be awake, so the queue is a
    # scale source too: same form as the workers' rules, for the same reason (see the measurement
    # app below), and the identity already holds Message Processor on this queue. queueLength = 1
    # wakes one replica per pending result; max_replicas bounds it as before.
    custom_scale_rule {
      name             = "measurement-results"
      custom_rule_type = "azure-queue"
      identity_id      = azurerm_user_assigned_identity.api.id

      metadata = {
        accountName = azurerm_storage_account.queue.name
        queueLength = "1"
        queueName   = azurerm_storage_queue.measurement_result.name
      }
    }
  }

  depends_on = [azurerm_role_assignment.api_queue_sender, azurerm_role_assignment.api_measurement_result_processor]
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

# Worker 2: a captured segment becomes a grass-height packet.
#
# It reads the segment's frames from R2, sends them to the depth service, spawns Verge Studio as a
# child process to segment and measure them, and writes the packet back beside the frames. The
# depth service is the only part of that chain that costs money and it is not created here; see
# docs/AUTOMATIC-HEIGHT.md for what the numbers do and do not mean.
resource "azurerm_container_app" "measurement" {
  name                         = "ca-${local.runtime_name_prefix}-measure"
  container_app_environment_id = azurerm_container_app_environment.this.id
  resource_group_name          = azurerm_resource_group.this.name
  revision_mode                = "Single"
  max_inactive_revisions       = 1
  workload_profile_name        = "Consumption"
  tags                         = local.default_tags

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.measurement.id]
  }

  # The same three the other two workloads mount, because this app is configured from the same
  # shared maps: one spelling of the storage credential across the stack.
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
    for_each = var.depth_service_token == null ? [] : [1]
    content {
      name  = "depth-service-token"
      value = var.depth_service_token
    }
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

  # No ingress, and that is a stronger statement here than on the frame worker.
  #
  # This worker also answers POST /measurements, the manual trigger a backfill or a re-measure uses
  # (docs/AUTOMATIC-HEIGHT.md). That endpoint has no authentication of its own and every accepted
  # call wakes a paid GPU, so it must not be reachable from anywhere. A backfill runs through
  # `az containerapp exec` against a live replica, by a person who has agreed to the spend.

  template {
    min_replicas    = 0
    max_replicas    = var.measurement_worker_max_replicas
    revision_suffix = var.deployment_revision

    polling_interval_in_seconds = 10
    cooldown_period_in_seconds  = 300

    # 600 seconds, where the API takes 30 and the frame worker 120. A measurement in flight has
    # already bought GPU time; killing it throws that away and returns the message for a second
    # paid run. It is still well short of the worker's own 30-minute assessment timeout, so a
    # genuinely stuck run is not protected forever.
    termination_grace_period_seconds = 600

    container {
      name  = "measurement-worker"
      image = var.measurement_worker_image

      # 2 vCPU / 4 GiB. This is reasoning, not measurement: nothing in the repository records this
      # worker's peak resident memory and no deployed run has been observed.
      #
      # What is known is the shape of the work. One segment at a time, held by an internal
      # single-flight gate. The largest single object it holds is the reconstruction's result.npz,
      # about 108 MB for 112 frames (services/greenv-measurement-worker/src/infer.mjs), next to
      # roughly 100 JPEGs and a child process that loads a SegFormer-B0 and decodes 576x1024
      # frames. 4 GiB is about an order of magnitude above the artifacts that are actually sized,
      # which is the margin an unmeasured working set needs. A profiled run may well show 2 GiB is
      # enough; 8 GiB is the Consumption ceiling and would only be warranted if the point cloud
      # turns out to be materialised whole. Read this number as "somewhere in 2-8 GiB, chosen high
      # because the failure mode is an OOM kill mid-run that pays for the GPU twice".
      #
      # The CPU figure answers a different question. Since 2026-09-14 a second segmentation runs
      # on every frame, and it is the one thing here that scales with cores: the ADE20K B4 takes
      # 3.9 s a frame on two ONNX threads and 1.3 s on four (measured on the bench machine, a
      # cloud vCPU being slower still), which over a hundred frames is the difference between six
      # minutes and two per segment. Four is the Consumption ceiling, and it accepts memory only at
      # 2 GiB per vCPU, so the two numbers are not chosen independently. The image also carries
      # `measurement/` and two baked models (~1.5 GB), which a scale-to-zero app decompresses on
      # every cold start, and that is CPU-bound too.
      cpu    = 4
      memory = "8Gi"

      dynamic "env" {
        for_each = local.measurement_environment
        content {
          name  = env.key
          value = env.value
        }
      }

      dynamic "env" {
        for_each = local.measurement_secret_environment
        content {
          name        = env.key
          secret_name = env.value
        }
      }

      startup_probe {
        transport               = "HTTP"
        port                    = 8090
        path                    = "/health"
        interval_seconds        = 5
        timeout                 = 3
        failure_count_threshold = 30
      }

      # More forgiving than the frame worker's three failures in 90 seconds. A restart here
      # discards a depth run that has already been paid for, so the probe has to be sure before it
      # kills. It can afford to be: the assessment runs in a child process and the worker's own
      # HTTP server answers throughout, so six failed checks over three minutes is a hung process
      # rather than a busy one.
      liveness_probe {
        transport               = "HTTP"
        port                    = 8090
        path                    = "/health"
        initial_delay           = 30
        interval_seconds        = 30
        timeout                 = 10
        failure_count_threshold = 6
      }
    }

    # Scales on the measurement queue, never the extraction one.
    #
    # Same shape as the frame worker's rule and for the same reason: azure_queue_scale_rule needs
    # an authentication block holding a storage connection string and has no identity option, so
    # custom_scale_rule with custom_rule_type = "azure-queue" is the only form that carries a
    # managed identity. Azure stores it as a native azureQueue rule and the provider reads it back
    # as azure_queue_scale_rule, so this always shows as a change; README.md says why not to
    # "fix" that diff.
    #
    # queueLength = 1 asks for one replica per pending message, and max_replicas is what actually
    # holds the constraint. One segment is one whole depth run: a 112-frame run fills an L4 to
    # 99.95% of its 22.03 GiB usable (measurement/docs/REGISTRY.md), so a second replica cannot
    # get a GPU. It would wait inside the depth service's own lock while billing a second
    # Container Apps replica and stretching the machine's idle tail.
    custom_scale_rule {
      name             = "measurement-queue"
      custom_rule_type = "azure-queue"
      identity_id      = azurerm_user_assigned_identity.measurement.id

      metadata = {
        accountName = azurerm_storage_account.queue.name
        queueLength = "1"
        queueName   = azurerm_storage_queue.measurement.name
      }
    }
  }

  depends_on = [
    azurerm_role_assignment.measurement_queue_contributor,
    azurerm_role_assignment.measurement_result_sender,
    azurerm_role_assignment.measurement_poison_sender,
  ]
}
