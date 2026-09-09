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

# The measurement stage's own queues, not a second consumer of the extraction queue.
#
# A segment is extracted once and measured once, and the two stages fail for different reasons at
# very different prices: a re-extraction costs CPU, a re-measurement wakes a paid GPU. Sharing one
# queue would make a redelivery meant for one stage run the other.
resource "azurerm_storage_queue" "measurement" {
  name               = local.measurement_queue_name
  storage_account_id = azurerm_storage_account.queue.id

  metadata = {
    contract = "segment-measure-v1"
  }
}

# Where a finished packet is announced. Nothing consumes it yet - the dashboard is the intended
# reader - but the worker publishes a result for every message it completes, and a publish with no
# destination is an error at the end of a run that has already been paid for.
resource "azurerm_storage_queue" "measurement_result" {
  name               = local.measurement_result_queue_name
  storage_account_id = azurerm_storage_account.queue.id

  metadata = {
    contract = "measurement-result-v1"
  }
}

resource "azurerm_storage_queue" "measurement_poison" {
  name               = local.measurement_poison_queue_name
  storage_account_id = azurerm_storage_account.queue.id

  metadata = {
    purpose = "failed-measurement-inspection"
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

resource "azurerm_user_assigned_identity" "measurement" {
  name                = "id-${local.name_prefix}-measurement"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  tags                = local.default_tags
}

# The three assignments below are scoped to one queue each, where the two above are scoped to the
# whole storage account.
#
# The account-scoped pair predates the measurement stage and is the only queue-role configuration
# this stack has actually run, so it is left alone; the frame extractor needs to publish onto the
# measurement queue anyway and that scope already allows it. This identity is new, and with two
# workers sharing one account the difference is worth having: a measurement worker accidentally
# pointed at the extraction queue would, at account scope, dequeue and delete another worker's
# messages, and one wrong queue name in an environment map is all that takes. At queue scope it
# gets a 403 and says so.
#
# Each scope is composed in locals.tf from the storage account's ARM id rather than read off
# azurerm_storage_queue, whose own id shape is a provider detail that has changed between major
# versions. Queue-level scope is what Azure documents for these roles; it has not been applied
# from here, because `terraform test` mocks every provider and creates nothing.

# Contributor rather than Reader plus Message Processor: the worker receives, extends, deletes and
# abandons messages, and the KEDA scale rule reads this queue's length through the same identity.
resource "azurerm_role_assignment" "measurement_queue_contributor" {
  scope                            = local.measurement_queue_scope
  role_definition_name             = "Storage Queue Data Contributor"
  principal_id                     = azurerm_user_assigned_identity.measurement.principal_id
  principal_type                   = "ServicePrincipal"
  skip_service_principal_aad_check = true
}

# Sender, not Contributor: the worker writes results and never reads them back. Whatever consumes
# this queue later is a different identity with a different reason.
resource "azurerm_role_assignment" "measurement_result_sender" {
  scope                            = local.measurement_result_queue_scope
  role_definition_name             = "Storage Queue Data Message Sender"
  principal_id                     = azurerm_user_assigned_identity.measurement.principal_id
  principal_type                   = "ServicePrincipal"
  skip_service_principal_aad_check = true
}

# Moving an exhausted message is an add here plus a delete on the source queue above, so this half
# only ever needs to send. Nothing drains the poison queue automatically; a person reads it.
resource "azurerm_role_assignment" "measurement_poison_sender" {
  scope                            = local.measurement_poison_queue_scope
  role_definition_name             = "Storage Queue Data Message Sender"
  principal_id                     = azurerm_user_assigned_identity.measurement.principal_id
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
