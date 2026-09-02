output "api_origin_url" {
  description = "Azure Container Apps origin URL."
  value       = "https://${azurerm_container_app.api.ingress[0].fqdn}"
}

output "api_public_url" {
  description = "Custom API URL when configured, otherwise the Azure origin URL."
  value       = var.api_hostname == null ? "https://${azurerm_container_app.api.ingress[0].fqdn}" : "https://${var.api_hostname}"
}

output "api_bearer_token" {
  description = "Bearer token required by every API endpoint except the health probe."
  value       = local.api_bearer_token
  sensitive   = true
}

output "api_container_app_name" {
  description = "Azure Container App running the control-plane API."
  value       = azurerm_container_app.api.name
}

output "worker_container_app_name" {
  description = "Azure Container App running the frame extractor."
  value       = azurerm_container_app.worker.name
}

output "resource_group_name" {
  description = "Azure resource group that contains the runtime and queue."
  value       = azurerm_resource_group.this.name
}

output "queue_storage_account_name" {
  description = "Azure Storage account used only for queues."
  value       = azurerm_storage_account.queue.name
}

output "segment_queue_name" {
  description = "Main segment-extraction queue."
  value       = azurerm_storage_queue.segment.name
}

output "poison_queue_name" {
  description = "Queue containing messages that exhausted delivery attempts."
  value       = azurerm_storage_queue.poison.name
}

output "r2_bucket_name" {
  description = "Private R2 bucket containing capture and extraction objects."
  value       = data.cloudflare_r2_bucket.captures.name
}

output "neon_project_id" {
  description = "Neon database project identifier."
  value       = neon_project.database.id
}
