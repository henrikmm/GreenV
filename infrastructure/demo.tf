# The FIAP Next demo: a phone uploads a video to the API's `/v1/demo/*` routes, the API extracts
# the frames and sends them to a RunPod endpoint of their own, and a public, read-only site at
# `demo_site_hostname` shows the result.
#
# Everything here is off unless `demo_enabled` is true, and with it off the plan is exactly what
# it was before this file existed - `tests/mvp.tftest.hcl` asserts that. The site itself is a
# Cloudflare Pages project, deployed with wrangler and not declared here, for the same reason the
# dashboard is not (see `dashboard_hostname`). The RunPod endpoint `greenv-demo-depth` is made by
# hand from the recipe in `services/greenv-depth-runpod/README.md` and named here by id, because
# this provider cannot manage an endpoint (see runpod.tf).

variable "demo_enabled" {
  description = <<-EOT
    Whether the API serves the demo routes, and whether the edge lets the demo site through. Off
    by default: an accepted demo upload wakes a paid GPU, which AGENTS.md wants agreed in
    conversation rather than inherited from a default.
  EOT
  type        = bool
  default     = false

  validation {
    condition     = !var.demo_enabled || var.demo_runpod_endpoint_id != null
    error_message = "demo_enabled needs demo_runpod_endpoint_id: the demo has no depth stage without its endpoint."
  }

  validation {
    condition     = !var.demo_enabled || var.demo_pairing_code != null
    error_message = "demo_enabled needs demo_pairing_code, or the demo's write routes would be open to anyone."
  }

  validation {
    condition = !var.demo_enabled || (
      var.demo_runpod_api_key != null ||
      (var.depth_service_adapter == "runpod" && var.depth_service_token != null) ||
      var.runpod_api_key != null
    )
    error_message = "demo_enabled needs a RunPod key: demo_runpod_api_key, a RunPod depth_service_token, or runpod_api_key."
  }

  # The zone's last firewall rule blocks every path on every host, so a site this stack does not
  # skip past it answers 403. Same reasoning as the dashboard's validation.
  validation {
    condition     = !var.demo_enabled || var.cloudflare_zone_firewall_ruleset_id != null
    error_message = "cloudflare_zone_firewall_ruleset_id is required when demo_enabled is true, or the zone's blanket block would hide the demo site."
  }
}

variable "demo_pairing_code" {
  description = <<-EOT
    The Bearer code a phone sends on every demo write. Reads are public. On the Mac the code is a
    six-digit speed bump against a stranger on the same Wi-Fi; here the routes face the internet
    and each accepted upload spends GPU time, so it must be at least eight characters. Set it as
    `TF_VAR_demo_pairing_code`, never in a file.
  EOT
  type        = string
  default     = null
  nullable    = true
  sensitive   = true

  validation {
    condition     = var.demo_pairing_code == null || try(length(trimspace(var.demo_pairing_code)), 0) >= 8
    error_message = "demo_pairing_code must be at least eight characters."
  }
}

variable "demo_runpod_endpoint_id" {
  description = "The id of the RunPod endpoint `greenv-demo-depth`, made by hand; never the field pipeline's endpoint."
  type        = string
  default     = null
  nullable    = true

  validation {
    condition     = var.demo_runpod_endpoint_id == null || can(regex("^[a-z0-9]+$", var.demo_runpod_endpoint_id))
    error_message = "demo_runpod_endpoint_id must be a RunPod endpoint id (lowercase letters and digits), not a URL."
  }

  validation {
    condition     = var.demo_runpod_endpoint_id == null || var.demo_runpod_endpoint_id != var.depth_service_endpoint_id
    error_message = "demo_runpod_endpoint_id must not be the field pipeline's endpoint; the demo has its own."
  }
}

variable "demo_runpod_api_key" {
  description = <<-EOT
    RunPod key the API sends the demo's jobs with. Null falls back to `depth_service_token` when
    the depth stage runs on RunPod, then to `runpod_api_key`: one account, one key to rotate.
  EOT
  type        = string
  default     = null
  nullable    = true
  sensitive   = true

  validation {
    condition     = var.demo_runpod_api_key == null || try(length(trimspace(var.demo_runpod_api_key)), 0) > 0
    error_message = "demo_runpod_api_key cannot be empty when provided."
  }
}

variable "demo_site_hostname" {
  description = "Where the public, read-only demo site is served. Its origin is the only one the demo's CORS rules allow."
  type        = string
  default     = "greenv-demo.matomomitsu.com"

  validation {
    condition     = can(regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$", var.demo_site_hostname))
    error_message = "demo_site_hostname must be a lowercase fully-qualified domain name."
  }
}

variable "demo_max_frames" {
  description = <<-EOT
    Frames the API samples from one demo video and sends in one job. 112 is Verge Studio's graded
    setting and the ceiling the demo endpoint's template sets as GREENV_DEPTH_MAX_FRAMES; raise
    both together or the handler refuses the job.
  EOT
  type        = number
  default     = 112

  validation {
    condition     = var.demo_max_frames >= 2 && var.demo_max_frames <= 112
    error_message = "demo_max_frames must be between 2 and 112, the demo endpoint's own ceiling."
  }
}

variable "demo_sample_on" {
  description = <<-EOT
    Where a demo video is cut into frames. "api" (default): this API runs ffmpeg on its one vCPU
    and uploads the frames to the bucket for the GPU to read. "gpu": the demo endpoint's handler
    downloads the video and samples it there with the same rule, which on 21 Oct 2026 took 0.8-1.4 s
    on a 20-core machine against 6-11 s of ffmpeg plus 11-14 s of frame upload on the API. Needs the
    handler image that understands video jobs (services/greenv-depth-runpod, commit with
    `sample_video`); "api" is the way back without a redeploy of the endpoint.
  EOT
  type        = string
  default     = "api"

  validation {
    condition     = contains(["api", "gpu"], var.demo_sample_on)
    error_message = "demo_sample_on must be api or gpu."
  }
}

variable "demo_login_enabled" {
  description = <<-EOT
    Whether the API serves `POST /v2/auth/demo`, which signs anyone into the dashboard as the
    read-only DEMO account without a password. Independent of `demo_enabled`: that one is the phone
    demo, this one is the dashboard's "Entrar como demonstração" button. Off by default because it
    opens every reading the API holds to the public internet; the role cannot write, so it cannot
    open a capture or an order, and therefore cannot wake the paid GPU.
  EOT
  type        = bool
  default     = false
}

locals {
  demo_site_origin = "https://${var.demo_site_hostname}"

  # Empty when off, so the API's environment is exactly what it was without it.
  demo_login_api_environment = !var.demo_login_enabled ? {} : {
    GREENV_DEMO_LOGIN_ENABLED = "true"
  }

  # Every single-page host the zone's blanket block must let through, in one rule (see the
  # zone_firewall comment in cloudflare.tf for why it is one). With one host the expression is
  # the dashboard's existing `eq`, so turning the demo off leaves the live rule byte-identical.
  site_hosts = compact([var.dashboard_hostname, var.demo_enabled ? var.demo_site_hostname : null])

  site_host_expression = (
    length(local.site_hosts) == 1
    ? "http.host eq \"${local.site_hosts[0]}\""
    : "http.host in {${join(" ", [for host in local.site_hosts : "\"${host}\""])}}"
  )

  # Named for the demo, not for the field pipeline's depth stage: the demo talks to its own endpoint
  # and must never be able to address greenv-mvp-depth.
  demo_runpod_api_key = !var.demo_enabled ? null : coalesce(
    var.demo_runpod_api_key,
    var.depth_service_adapter == "runpod" ? var.depth_service_token : null,
    var.runpod_api_key,
  )

  # Empty when the demo is off, so the API's environment is exactly what it was without it.
  demo_api_environment = !var.demo_enabled ? {} : {
    GREENV_DEMO_ENABLED            = "true"
    GREENV_DEMO_DEPTH_ADAPTER      = "runpod"
    GREENV_DEMO_RUNPOD_ENDPOINT_ID = var.demo_runpod_endpoint_id
    GREENV_DEMO_MAX_FRAMES         = tostring(var.demo_max_frames)
    GREENV_DEMO_SAMPLE_ON          = var.demo_sample_on
    # Not GREENV_ALLOWED_ORIGINS: that list gets credentialed CORS on every route, and the public
    # site has no business calling the authenticated API with a visitor's cookie.
    GREENV_DEMO_ALLOWED_ORIGINS = local.demo_site_origin
  }

  demo_api_secret_environment = !var.demo_enabled ? {} : {
    GREENV_DEMO_PAIRING_CODE   = "demo-pairing-code"
    GREENV_DEMO_RUNPOD_API_KEY = "demo-runpod-api-key"
  }
}

# The site reads frames, the reconstruction and its arrays straight from R2 through URLs the API
# presigns, so the bucket has to answer the browser's CORS check for that one origin. Ranges
# because the viewer may fetch a large file in parts; the exposed headers are the ones a ranged or
# cached read has to see.
#
# This resource owns the bucket's whole CORS policy, not one rule of it. Nothing else in this
# repository reads the bucket from a browser today, but list it before the first apply
# (`npx wrangler r2 bucket cors list <bucket>`) and add any rule found there to this one rather
# than letting the apply replace it. The Cloudflare token also needs R2 edit permission for it.
resource "cloudflare_r2_bucket_cors" "demo_reads" {
  count = var.demo_enabled ? 1 : 0

  account_id  = var.cloudflare_account_id
  bucket_name = data.cloudflare_r2_bucket.captures.name

  rules = [{
    id = "greenv-demo-site-reads"
    allowed = {
      origins = [local.demo_site_origin]
      methods = ["GET", "HEAD"]
      headers = ["Range"]
    }
    expose_headers  = ["Content-Range", "Accept-Ranges", "Content-Length", "ETag"]
    max_age_seconds = 3600
  }]
}
