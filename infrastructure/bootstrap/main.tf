resource "cloudflare_r2_bucket" "terraform_state" {
  account_id    = var.cloudflare_account_id
  name          = var.state_bucket_name
  location      = var.r2_location_hint
  storage_class = "Standard"

  lifecycle {
    prevent_destroy = true
  }
}

resource "cloudflare_r2_bucket" "application" {
  account_id    = var.cloudflare_account_id
  name          = var.application_bucket_name
  location      = var.r2_location_hint
  storage_class = "Standard"

  lifecycle {
    prevent_destroy = true
  }
}

output "state_bucket_name" {
  description = "Bucket to place in backend.r2.hcl."
  value       = cloudflare_r2_bucket.terraform_state.name
}

output "state_s3_endpoint" {
  description = "R2 S3 endpoint to place in backend.r2.hcl."
  value       = "https://${var.cloudflare_account_id}.r2.cloudflarestorage.com"
}

output "application_bucket_name" {
  description = "Bucket to set as r2_bucket_name in the main root."
  value       = cloudflare_r2_bucket.application.name
}
