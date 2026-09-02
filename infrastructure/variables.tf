variable "project_name" {
  description = "Short lowercase name used as a prefix for cloud resources."
  type        = string
  default     = "greenv"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,15}[a-z0-9]$", var.project_name))
    error_message = "project_name must contain 3 to 17 lowercase letters, numbers or hyphens."
  }
}

variable "environment" {
  description = "Deployment environment name."
  type        = string
  default     = "mvp"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{0,8}[a-z0-9]$", var.environment))
    error_message = "environment must contain 2 to 10 lowercase letters, numbers or hyphens."
  }
}

variable "azure_location" {
  description = "Azure region for Container Apps and Queue Storage."
  type        = string
  default     = "eastus2"
}

variable "neon_region_id" {
  description = "Neon region identifier placed close to the Azure runtime."
  type        = string
  default     = "aws-us-east-2"
}

variable "cloudflare_account_id" {
  description = "Cloudflare account that owns the R2 bucket."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{32}$", var.cloudflare_account_id))
    error_message = "cloudflare_account_id must be a 32-character lowercase hexadecimal ID."
  }
}

variable "cloudflare_zone_id" {
  description = "Cloudflare zone that owns api_hostname. Required when api_hostname is set."
  type        = string
  default     = null
  nullable    = true
}

variable "api_hostname" {
  description = "Optional API hostname, for example api.example.com."
  type        = string
  default     = null
  nullable    = true

  validation {
    condition     = var.api_hostname == null || can(regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$", var.api_hostname))
    error_message = "api_hostname must be a lowercase fully-qualified domain name."
  }

  validation {
    condition     = var.api_hostname == null || var.cloudflare_zone_id != null
    error_message = "cloudflare_zone_id is required when api_hostname is set."
  }
}

variable "r2_bucket_name" {
  description = "Application bucket created by the bootstrap root."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$", var.r2_bucket_name))
    error_message = "r2_bucket_name must be 3 to 63 lowercase letters, numbers or hyphens."
  }
}

variable "r2_access_key_id" {
  description = "R2 S3 API access key used only by the API and worker."
  type        = string
  sensitive   = true

  validation {
    condition     = length(trimspace(var.r2_access_key_id)) > 0
    error_message = "r2_access_key_id cannot be empty."
  }
}

variable "r2_secret_access_key" {
  description = "R2 S3 API secret key used only by the API and worker."
  type        = string
  sensitive   = true

  validation {
    condition     = length(trimspace(var.r2_secret_access_key)) > 0
    error_message = "r2_secret_access_key cannot be empty."
  }
}

variable "api_image" {
  description = "API container image, preferably pinned by sha256 digest."
  type        = string

  validation {
    condition     = length(trimspace(var.api_image)) > 0
    error_message = "api_image cannot be empty."
  }
}

variable "api_bearer_token" {
  description = "Optional shared Bearer token for the MVP API. Terraform generates one when omitted."
  type        = string
  default     = null
  nullable    = true
  sensitive   = true

  validation {
    condition     = var.api_bearer_token == null || try(length(trimspace(var.api_bearer_token)), 0) >= 32
    error_message = "api_bearer_token must contain at least 32 characters when provided."
  }
}

variable "worker_image" {
  description = "Frame worker container image, preferably pinned by sha256 digest."
  type        = string

  validation {
    condition     = length(trimspace(var.worker_image)) > 0
    error_message = "worker_image cannot be empty."
  }
}

variable "container_registry" {
  description = "Optional credentials for a private image registry. Leave every field null for public images."
  type = object({
    server   = optional(string)
    username = optional(string)
    password = optional(string)
  })
  default   = {}
  sensitive = true

  validation {
    condition = (
      (try(var.container_registry.server, null) == null &&
        try(var.container_registry.username, null) == null &&
      try(var.container_registry.password, null) == null) ||
      (try(length(trimspace(var.container_registry.server)), 0) > 0 &&
        try(length(trimspace(var.container_registry.username)), 0) > 0 &&
      try(length(trimspace(var.container_registry.password)), 0) > 0)
    )
    error_message = "container_registry.server, username and password must either all be set or all be null."
  }
}

variable "api_max_replicas" {
  description = "Maximum number of API replicas."
  type        = number
  default     = 3

  validation {
    condition     = var.api_max_replicas >= 1 && var.api_max_replicas <= 10
    error_message = "api_max_replicas must be between 1 and 10."
  }
}

variable "worker_max_replicas" {
  description = "Maximum number of FFmpeg worker replicas."
  type        = number
  default     = 4

  validation {
    condition     = var.worker_max_replicas >= 1 && var.worker_max_replicas <= 10
    error_message = "worker_max_replicas must be between 1 and 10."
  }
}

variable "queue_visibility_timeout_seconds" {
  description = "Azure Queue visibility lease; keep above the slowest measured extraction attempt."
  type        = number
  default     = 300

  validation {
    condition     = var.queue_visibility_timeout_seconds >= 30 && var.queue_visibility_timeout_seconds <= 604800
    error_message = "queue_visibility_timeout_seconds must be between 30 seconds and 7 days."
  }
}

variable "log_daily_quota_gb" {
  description = "Daily Log Analytics ingestion cap in GiB."
  type        = number
  default     = 0.1

  validation {
    condition     = var.log_daily_quota_gb >= 0.023 && var.log_daily_quota_gb <= 1
    error_message = "log_daily_quota_gb must be between 0.023 and 1 GiB for this MVP stack."
  }
}

variable "budget_amount" {
  description = "Monthly Azure budget in the subscription billing currency. It is created only when budget_contact_emails is not empty."
  type        = number
  default     = 30

  validation {
    condition     = var.budget_amount > 0
    error_message = "budget_amount must be greater than zero."
  }
}

variable "budget_contact_emails" {
  description = "Email addresses that receive Azure spend alerts."
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "Additional Azure resource tags."
  type        = map(string)
  default     = {}
}
