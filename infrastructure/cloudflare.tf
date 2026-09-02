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
  proxied = var.cloudflare_proxy_enabled
  ttl     = 1
  comment = "GreenV API origin; DNS-only until Azure has issued the managed certificate"
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

# Azure refuses to issue a certificate for a hostname that is not registered on a container app
# ("RequireCustomHostnameInEnvironment"), so this comes first and the certificate depends on it.
resource "azurerm_container_app_custom_domain" "api" {
  count = var.api_hostname == null ? 0 : 1

  name             = var.api_hostname
  container_app_id = azurerm_container_app.api.id

  # The provider cannot express the binding for a managed certificate:
  # container_app_environment_certificate_id parses only an environment certificate id
  # (.../certificates/<name>) and rejects a managed one (.../managedCertificates/<name>) at plan
  # time. Both fields are therefore left to the bind below, and ignored here so the binding it
  # makes is not read back as drift.
  lifecycle {
    ignore_changes = [
      certificate_binding_type,
      container_app_environment_certificate_id,
    ]
  }

  depends_on = [time_sleep.api_dns_propagation]
}

# Azure issues and renews this for free, but only while it can validate ownership. CNAME validation
# reads the public DNS: the asuid TXT record proves ownership and the CNAME must resolve to the
# Container App. That is why phase one keeps the record DNS-only — a proxied record answers with
# Cloudflare's addresses instead, and validation has nothing to match. Renewal validates again, so
# the same constraint applies for the certificate's whole life, not just its first issue.
resource "azurerm_container_app_environment_managed_certificate" "api" {
  count = var.api_hostname == null ? 0 : 1

  name                         = local.api_certificate_name
  container_app_environment_id = azurerm_container_app_environment.this.id
  subject_name                 = var.api_hostname
  domain_control_validation    = "CNAME"
  tags                         = local.default_tags

  depends_on = [azurerm_container_app_custom_domain.api]
}

# The last step Azure requires, and the one the provider cannot do. Without it the hostname is
# registered but unbound, and Azure's ingress resets the TLS handshake for it rather than serving
# it — which reads as a network fault rather than a missing certificate. Binding is create-or-
# update, so re-running it is safe; it re-runs only when the certificate or the app is replaced.
#
# This needs the Azure CLI on the machine running Terraform, signed in to the same subscription.
resource "terraform_data" "api_certificate_binding" {
  count = var.api_hostname == null ? 0 : 1

  triggers_replace = [
    azurerm_container_app_environment_managed_certificate.api[0].id,
    azurerm_container_app.api.id,
  ]

  provisioner "local-exec" {
    command = local.api_certificate_bind_command
  }
}

# Edge rules. Cloudflare allows one entry-point ruleset per zone phase, so each of these owns its
# phase for the whole zone; every rule is narrowed to the API hostname because the zone serves
# other subdomains. If the zone already has an entry-point ruleset in one of these phases, import
# it (`terraform import cloudflare_ruleset.<name> zones/<zone id>/<ruleset id>`) and add the rule
# there rather than letting a second one conflict.

resource "cloudflare_ruleset" "api_cache_bypass" {
  count = var.cloudflare_proxy_enabled && var.cloudflare_api_cache_bypass_enabled ? 1 : 0

  zone_id     = var.cloudflare_zone_id
  name        = "greenv-api-cache-bypass"
  description = "Never cache API responses"
  kind        = "zone"
  phase       = "http_request_cache_settings"

  rules = [{
    # Created with the proxy rather than ahead of it, so phase one needs only DNS permissions on
    # the Cloudflare token. A DNS-only record is never cached, so there is nothing to protect
    # until the proxy is switched on in the same apply that creates this rule.
    action      = "set_cache_settings"
    expression  = local.api_host_expression
    description = "Bypass cache for the GreenV API hostname"
    enabled     = true

    action_parameters = {
      cache = false
    }
  }]
}

resource "cloudflare_ruleset" "api_waf" {
  count = var.cloudflare_proxy_enabled && var.cloudflare_api_waf_enabled ? 1 : 0

  zone_id     = var.cloudflare_zone_id
  name        = "greenv-api-firewall"
  description = "Hostname-scoped custom rules for the GreenV API"
  kind        = "zone"
  phase       = "http_request_firewall_custom"

  rules = [{
    # The capture client only ever issues GET, POST, PUT and the browser's OPTIONS preflight.
    # This is a custom rule, not a Managed Ruleset: Managed Rules need a paid entitlement, so
    # they are deliberately not declared here.
    action      = "block"
    expression  = "(${local.api_host_expression}) and not http.request.method in {\"GET\" \"POST\" \"PUT\" \"PATCH\" \"DELETE\" \"OPTIONS\"}"
    description = "Block unexpected HTTP methods on the GreenV API hostname"
    enabled     = true
  }]
}

resource "cloudflare_ruleset" "api_rate_limit" {
  count = var.cloudflare_proxy_enabled && var.cloudflare_api_rate_limit_enabled ? 1 : 0

  zone_id     = var.cloudflare_zone_id
  name        = "greenv-api-rate-limit"
  description = "Per-IP request ceiling for the GreenV API"
  kind        = "zone"
  phase       = "http_ratelimit"

  rules = [{
    action      = "block"
    expression  = local.api_host_expression
    description = "Rate limit the GreenV API per client address"
    enabled     = true

    ratelimit = {
      # A capture uploads a video, a telemetry document and a completion per ten-second segment,
      # plus verification polls, so the ceiling has to sit well above a single active device.
      characteristics     = ["ip.src", "cf.colo.id"]
      period              = var.cloudflare_api_rate_limit_period_seconds
      requests_per_period = var.cloudflare_api_rate_limit_requests
      mitigation_timeout  = var.cloudflare_api_rate_limit_period_seconds
    }
  }]
}

# Zone-wide, and therefore behind their own flag: matomomitsu.com serves subdomains this stack
# does not own, and tightening TLS for the zone tightens it for all of them.
resource "cloudflare_zone_setting" "ssl_strict" {
  count = var.manage_cloudflare_zone_security_settings ? 1 : 0

  zone_id    = var.cloudflare_zone_id
  setting_id = "ssl"
  value      = "strict"
}

resource "cloudflare_zone_setting" "minimum_tls_version" {
  count = var.manage_cloudflare_zone_security_settings ? 1 : 0

  zone_id    = var.cloudflare_zone_id
  setting_id = "min_tls_version"
  value      = "1.2"
}

resource "cloudflare_zone_setting" "tls_1_3" {
  count = var.manage_cloudflare_zone_security_settings ? 1 : 0

  zone_id    = var.cloudflare_zone_id
  setting_id = "tls_1_3"
  value      = "on"
}
