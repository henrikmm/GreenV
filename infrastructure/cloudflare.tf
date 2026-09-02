data "cloudflare_r2_bucket" "captures" {
  account_id  = var.cloudflare_account_id
  bucket_name = var.r2_bucket_name
}

resource "cloudflare_dns_record" "api_cname" {
  count = var.api_hostname == null ? 0 : 1

  zone_id = var.cloudflare_zone_id
  name    = var.api_hostname
  type    = "CNAME"
  content = azurerm_container_app.api.ingress[0].fqdn
  proxied = false
  ttl     = 1
  comment = "GreenV API origin; keep DNS-only for Azure managed certificate validation"
}

resource "cloudflare_dns_record" "api_verification" {
  count = var.api_hostname == null ? 0 : 1

  zone_id = var.cloudflare_zone_id
  name    = "asuid.${var.api_hostname}"
  type    = "TXT"
  content = azurerm_container_app.api.custom_domain_verification_id
  proxied = false
  ttl     = 1
  comment = "Azure Container Apps custom-domain ownership verification"
}

resource "time_sleep" "api_dns_propagation" {
  count = var.api_hostname == null ? 0 : 1

  create_duration = "60s"

  triggers = {
    cname_target       = cloudflare_dns_record.api_cname[0].content
    verification_value = cloudflare_dns_record.api_verification[0].content
  }
}

resource "azurerm_container_app_custom_domain" "api" {
  count = var.api_hostname == null ? 0 : 1

  name             = var.api_hostname
  container_app_id = azurerm_container_app.api.id

  lifecycle {
    ignore_changes = [
      certificate_binding_type,
      container_app_environment_certificate_id,
    ]
  }

  depends_on = [time_sleep.api_dns_propagation]
}
