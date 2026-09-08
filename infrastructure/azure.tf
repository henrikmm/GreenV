resource "azurerm_resource_group" "this" {
  name     = local.resource_group_name
  location = var.azure_location
  tags     = local.default_tags
}

resource "azurerm_log_analytics_workspace" "this" {
  name                = "log-${local.name_prefix}-${local.suffix}"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  sku                 = "PerGB2018"
  retention_in_days   = 30
  daily_quota_gb      = var.log_daily_quota_gb
  tags                = local.default_tags
}

resource "azurerm_container_app_environment" "this" {
  name                       = "cae-${local.name_prefix}-${local.suffix}"
  location                   = azurerm_resource_group.this.location
  resource_group_name        = azurerm_resource_group.this.name
  logs_destination           = "log-analytics"
  log_analytics_workspace_id = azurerm_log_analytics_workspace.this.id
  public_network_access      = "Enabled"
  tags                       = local.default_tags

  # Azure creates this profile with the environment and keeps re-adding it. Leaving it undeclared
  # made every plan propose removing it, which is not a change anyone wants applied: an environment
  # cannot be converted back to the Consumption-only shape in place. Declaring it makes the plan
  # describe reality. Consumption bills only for what the replicas use, so this adds no cost.
  workload_profile {
    name                  = "Consumption"
    workload_profile_type = "Consumption"
    minimum_count         = 0
    maximum_count         = 0
  }
}

resource "azurerm_storage_account" "queue" {
  name                             = local.storage_account_name
  resource_group_name              = azurerm_resource_group.this.name
  location                         = azurerm_resource_group.this.location
  account_tier                     = "Standard"
  account_replication_type         = "LRS"
  account_kind                     = "StorageV2"
  min_tls_version                  = "TLS1_2"
  https_traffic_only_enabled       = true
  public_network_access_enabled    = true
  allow_nested_items_to_be_public  = false
  shared_access_key_enabled        = false
  default_to_oauth_authentication  = true
  local_user_enabled               = false
  cross_tenant_replication_enabled = false
  tags                             = local.default_tags
}

resource "azurerm_storage_queue" "segment" {
  name               = local.segment_queue_name
  storage_account_id = azurerm_storage_account.queue.id

  metadata = {
    contract = "segment-extraction-v2"
  }
}

resource "azurerm_storage_queue" "poison" {
  name               = local.poison_queue_name
  storage_account_id = azurerm_storage_account.queue.id

  metadata = {
    purpose = "failed-segment-inspection"
  }
}

resource "azurerm_user_assigned_identity" "api" {
  name                = "id-${local.name_prefix}-api"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  tags                = local.default_tags
}

resource "azurerm_user_assigned_identity" "worker" {
  name                = "id-${local.name_prefix}-worker"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  tags                = local.default_tags
}

resource "azurerm_role_assignment" "api_queue_sender" {
  scope                            = azurerm_storage_account.queue.id
  role_definition_name             = "Storage Queue Data Message Sender"
  principal_id                     = azurerm_user_assigned_identity.api.principal_id
  principal_type                   = "ServicePrincipal"
  skip_service_principal_aad_check = true
}

resource "azurerm_role_assignment" "worker_queue_contributor" {
  scope                            = azurerm_storage_account.queue.id
  role_definition_name             = "Storage Queue Data Contributor"
  principal_id                     = azurerm_user_assigned_identity.worker.principal_id
  principal_type                   = "ServicePrincipal"
  skip_service_principal_aad_check = true
}

resource "azurerm_consumption_budget_resource_group" "this" {
  count = length(var.budget_contact_emails) == 0 ? 0 : 1

  name              = "${local.name_prefix}-monthly-budget"
  resource_group_id = azurerm_resource_group.this.id
  amount            = var.budget_amount
  time_grain        = "Monthly"

  time_period {
    start_date = formatdate("YYYY-MM-01'T'00:00:00'Z'", timestamp())
  }

  notification {
    enabled        = true
    operator       = "GreaterThanOrEqualTo"
    threshold      = 50
    threshold_type = "Actual"
    contact_emails = var.budget_contact_emails
  }

  notification {
    enabled        = true
    operator       = "GreaterThanOrEqualTo"
    threshold      = 80
    threshold_type = "Forecasted"
    contact_emails = var.budget_contact_emails
  }

  notification {
    enabled        = true
    operator       = "GreaterThanOrEqualTo"
    threshold      = 100
    threshold_type = "Actual"
    contact_emails = var.budget_contact_emails
  }

  lifecycle {
    ignore_changes = [time_period]
  }
}
