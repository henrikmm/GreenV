variable "cloudflare_account_id" {
  description = "Cloudflare account that owns the Terraform state bucket."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{32}$", var.cloudflare_account_id))
    error_message = "cloudflare_account_id must be a 32-character lowercase hexadecimal ID."
  }
}

variable "state_bucket_name" {
  description = "R2 bucket name dedicated to Terraform state and unique in the Cloudflare account."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$", var.state_bucket_name))
    error_message = "state_bucket_name must be 3 to 63 lowercase letters, numbers or hyphens."
  }
}

variable "application_bucket_name" {
  description = "R2 bucket name dedicated to GreenV objects and unique in the Cloudflare account."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$", var.application_bucket_name))
    error_message = "application_bucket_name must be 3 to 63 lowercase letters, numbers or hyphens."
  }

  validation {
    condition     = var.application_bucket_name != var.state_bucket_name
    error_message = "application_bucket_name and state_bucket_name must be different."
  }
}

variable "r2_location_hint" {
  description = "Optional R2 location hint."
  type        = string
  default     = "enam"
  nullable    = true

  validation {
    condition     = var.r2_location_hint == null || contains(["apac", "eeur", "enam", "weur", "wnam", "oc"], var.r2_location_hint)
    error_message = "r2_location_hint must be null or one of apac, eeur, enam, weur, wnam or oc."
  }
}
