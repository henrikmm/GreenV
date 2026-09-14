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

variable "dashboard_hostname" {
  description = <<-EOT
    Where the production dashboard is served, for example greenv.example.com. Naming it does two
    things and creates nothing: the zone's firewall gets a rule letting this host through, and
    the API's allowed origins get it so a browser may read a response.

    The site itself is not created here. It is a static build on Cloudflare Pages, which this
    provider version cannot express, and Pages creates the DNS record when the custom domain is
    attached — so a record here would fight it. See infrastructure/README.md.

    It must sit under the same registrable domain as the API. The session cookies are `__Host-`
    with SameSite=Lax, and a dashboard on another domain would be refused a cookie it never sees.
  EOT
  type        = string
  default     = null
  nullable    = true

  validation {
    condition     = var.dashboard_hostname == null || can(regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$", var.dashboard_hostname))
    error_message = "dashboard_hostname must be a lowercase fully-qualified domain name."
  }

  validation {
    condition     = var.dashboard_hostname == null || var.cloudflare_zone_firewall_ruleset_id != null
    error_message = "cloudflare_zone_firewall_ruleset_id is required when dashboard_hostname is set, or the zone's blanket block would hide the dashboard."
  }
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

variable "measurement_worker_image" {
  description = <<-EOT
    Measurement worker container image, preferably pinned by sha256 digest. Built from the
    repository root rather than from the service directory, because the image carries
    `measurement/` and a baked model cache - the worker spawns Verge Studio as a process.
  EOT
  type        = string

  validation {
    condition     = length(trimspace(var.measurement_worker_image)) > 0
    error_message = "measurement_worker_image cannot be empty."
  }
}

variable "measurement_enabled" {
  description = <<-EOT
    Whether the frame extractor announces every finished segment for measurement. False by
    default: turning it on makes each capture wake a paid GPU with nobody in the loop, and
    AGENTS.md wants that agreed in conversation rather than inherited from a default. The
    measurement worker, its queues and its roles exist either way and cost nothing at zero
    replicas, so this is one variable and no new resources.
  EOT
  type        = bool
  default     = false

  # A `runpod` deployment needs nothing more named: Terraform creates the endpoint, and the
  # template it is built from is provisioned during the apply. Only the plain HTTP dialect has
  # nowhere to send a segment unless someone says where.
  validation {
    condition = (
      !var.measurement_enabled ||
      var.depth_service_adapter == "runpod" ||
      var.depth_service_base_url != null
    )
    error_message = "measurement_enabled with depth_service_adapter = \"http\" needs depth_service_base_url. Without it, every announced segment fails and lands in the poison queue."
  }

  # Creating the endpoint - and the template under it - is an API call, and an API call needs a
  # key. Refusing here beats failing halfway through an apply with a 401 from two different places.
  validation {
    condition = (
      !var.measurement_enabled ||
      var.depth_service_adapter != "runpod" ||
      var.depth_service_endpoint_id != null ||
      var.runpod_api_key != null
    )
    error_message = "Creating a RunPod endpoint needs runpod_api_key (export TF_VAR_runpod_api_key). Set depth_service_endpoint_id instead to use an endpoint that already exists, which Terraform then never touches."
  }

  validation {
    condition     = !var.measurement_enabled || var.depth_service_adapter != "runpod" || var.depth_service_token != null
    error_message = "A RunPod endpoint authenticates every request with an API key, so depth_service_token is required when depth_service_adapter is \"runpod\"."
  }
}

variable "depth_service_adapter" {
  description = <<-EOT
    How the measurement worker reaches the depth service: `http` for a plain endpoint named by
    depth_service_base_url, `runpod` for a RunPod serverless endpoint named by
    depth_service_endpoint_id. The service itself is never created by this configuration.
  EOT
  type        = string
  default     = "http"

  validation {
    condition     = contains(["http", "runpod"], var.depth_service_adapter)
    error_message = "depth_service_adapter must be http or runpod."
  }
}

variable "depth_service_base_url" {
  description = <<-EOT
    Base URL of the depth reconstruction service, including whatever path prefix it serves the
    contract under. Left null the worker falls back to Verge Studio's local mock, which is not in
    the image, so it fails every message instead of inventing a reading.
  EOT
  type        = string
  default     = null
  nullable    = true

  validation {
    condition     = var.depth_service_base_url == null || can(regex("^https://[a-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?$", var.depth_service_base_url))
    error_message = "depth_service_base_url must be an https URL when provided."
  }
}

variable "depth_service_endpoint_id" {
  description = "RunPod serverless endpoint identifier. Required when depth_service_adapter is runpod."
  type        = string
  default     = null
  nullable    = true

  validation {
    condition     = var.depth_service_endpoint_id == null || can(regex("^[a-z0-9]{6,40}$", var.depth_service_endpoint_id))
    error_message = "depth_service_endpoint_id must be 6 to 40 lowercase alphanumeric characters."
  }
}

variable "depth_service_token" {
  description = <<-EOT
    Bearer credential for the depth service, mounted as a Container Apps secret. One token serves
    both adapters: the HTTP client sends it as an Authorization header and RunPod authenticates
    its serverless endpoints the same way. Anyone holding it can spend GPU time.
  EOT
  type        = string
  default     = null
  nullable    = true
  sensitive   = true

  validation {
    condition     = var.depth_service_token == null || try(length(trimspace(var.depth_service_token)), 0) > 0
    error_message = "depth_service_token cannot be empty when provided."
  }
}

variable "measurement_worker_max_replicas" {
  description = <<-EOT
    Maximum number of measurement worker replicas. One, because one segment is one whole depth
    run: a 112-frame run fills an L4 to 99.95% of its 22.03 GiB usable
    (measurement/docs/REGISTRY.md), so a second replica cannot get a GPU and would only wait
    inside the depth service's own lock while billing a second Container Apps replica. Raise it
    only against a depth service that can genuinely serve more than one run at a time.
  EOT
  type        = number
  default     = 1

  validation {
    condition     = var.measurement_worker_max_replicas >= 1 && var.measurement_worker_max_replicas <= 4
    error_message = "measurement_worker_max_replicas must be between 1 and 4."
  }
}

variable "measurement_queue_visibility_timeout_seconds" {
  description = <<-EOT
    Azure Queue visibility lease for a measurement message. Thirty minutes, matching the worker's
    own assessment timeout, because a measurement is a depth run plus a CPU pass - minutes, not
    seconds. A lease that expires mid-run redelivers the message and pays for the same segment's
    GPU time twice, which is why this is much longer than the extraction lease beside it.
  EOT
  type        = number
  default     = 1800

  validation {
    condition     = var.measurement_queue_visibility_timeout_seconds >= 300 && var.measurement_queue_visibility_timeout_seconds <= 604800
    error_message = "measurement_queue_visibility_timeout_seconds must be between 300 seconds and 7 days."
  }
}

variable "measurement_classes" {
  description = <<-EOT
    Cityscapes labels counted as the area of interest, comma separated. `terrain` alone is Verge
    Studio's own default and reads 0.000 m on a plant taped at 0.980 m, because Cityscapes files
    vertically growing plants under `vegetation` and only horizontally spreading growth under
    `terrain` (measurement/docs/evidence/2026-09-05-class-fit.md). Roçada is about the vertical
    kind. No class policy here is validated; the union is the one whose failure mode is visible
    rather than silent, and it is recorded on every frame of every packet.
  EOT
  type        = string
  default     = "terrain,vegetation"

  validation {
    condition     = can(regex("^[a-z]+(,[a-z]+)*$", var.measurement_classes))
    error_message = "measurement_classes must be comma-separated lowercase Cityscapes label names with no spaces."
  }
}

variable "measurement_offset_side" {
  description = <<-EOT
    Which side of the camera track the measured band goes to. `auto` reads it off the vegetation
    masks of each run; `given` keeps the sign of the worker's fixed offset, which is Verge Studio's
    own behaviour. Every driven segment of 2026-09-13 had the verge on the side `given` never
    reached, and twelve of them measured nothing (measurement/scripts/grass-anchor.mjs).
  EOT
  type        = string
  default     = "auto"

  validation {
    condition     = contains(["given", "auto"], var.measurement_offset_side)
    error_message = "measurement_offset_side must be \"given\" or \"auto\"."
  }
}

variable "measurement_min_track_m" {
  description = <<-EOT
    A run whose camera track on the road plane is shorter than this, in metres, is not measured:
    the reconstruction did not see the vehicle move, and whatever lies in the band is a door
    handle or a tree rather than a verge. Four such segments on 2026-09-13 were reported as 1.1
    to 3.9 m of vegetation. 0 disables the gate.
  EOT
  type        = number
  default     = 3

  validation {
    condition     = var.measurement_min_track_m >= 0
    error_message = "measurement_min_track_m must be zero or a positive number of metres."
  }
}

variable "measurement_scale_anchor" {
  description = <<-EOT
    Where the worker takes the reconstruction's metric scale from. `telemetry` hands Verge Studio
    the GPS path length of the sampled frames, which the frame extractor writes into every
    manifest, and each run is stretched or shrunk until its camera track is that long. DA3 fixes
    its scale once per clip and it ran from 0.78x to 1.86x against that length on neighbouring
    segments of one drive (2026-09-13). `none` leaves the model's scale alone. A configured
    camera height wins over either.
  EOT
  type        = string
  default     = "telemetry"

  validation {
    condition     = contains(["telemetry", "none"], var.measurement_scale_anchor)
    error_message = "measurement_scale_anchor must be \"telemetry\" or \"none\"."
  }
}

variable "measurement_max_height_m" {
  description = <<-EOT
    A back-projected point higher than this above the road plane, in metres, is a tree crown, a
    wall top or a cut face and never enters a measurement cell. The `vegetation` class is the
    only one that captures the tall grass and brush a mowing decision is about, and it captures
    trees with them; their height is what tells them apart. Null keeps every point.
  EOT
  type        = number
  default     = 3

  validation {
    condition     = var.measurement_max_height_m == null || var.measurement_max_height_m > 0
    error_message = "measurement_max_height_m must be a positive number of metres, or null."
  }
}

variable "measurement_canopy_extent_m" {
  description = <<-EOT
    A cell whose vegetation extent above its own ground exceeds this, in metres, is reported as
    `canopy` — a trunk with low branches, a hedge line — with every number it computed, and is
    counted in no aggregate. Null reports every measured cell as measured.
  EOT
  type        = number
  default     = 2

  validation {
    condition     = var.measurement_canopy_extent_m == null || var.measurement_canopy_extent_m > 0
    error_message = "measurement_canopy_extent_m must be a positive number of metres, or null."
  }
}

variable "measurement_ground_fallback" {
  description = <<-EOT
    When the strict ground-plane fit finds no floor, allow one coarser attempt (twice the inlier
    distance, half the support floor), kept only if the camera stands a plausible height above
    the result and named in the packet as `ground-fit-relaxed`. A wet road reflects the sky and
    the depth model reads the reflection as depth scattered below the surface, so the true
    ground is a thin layer: one segment of 2026-09-13 measured nothing without this and 601
    cells with it.
  EOT
  type        = bool
  default     = true
}

variable "measurement_camera_height_m" {
  description = <<-EOT
    The lens's height above the road for the mount in use, in metres, measured with a tape. DA3
    fixes its metric scale once per clip and it varied more than two to one between neighbouring
    segments of one drive; with this set, each run is rescaled so the camera sits where it
    physically was, and the packet records the factor. Null leaves the model's own scale alone.
  EOT
  type        = number
  default     = null

  validation {
    condition     = var.measurement_camera_height_m == null || (var.measurement_camera_height_m > 0 && var.measurement_camera_height_m < 10)
    error_message = "measurement_camera_height_m must be a height in metres, or null."
  }
}

variable "depth_max_frames" {
  description = <<-EOT
    Frames sent to the depth service in one run. 112 is Verge Studio's best graded setting and the
    cap the frame extractor already samples to; an L4 runs out of memory above 144 and a recorded
    112-frame run peaked at 99.95% of the card (measurement/docs/REGISTRY.md). Lower it before
    running long segments through this automatically.
  EOT
  type        = number
  default     = 112

  validation {
    condition     = var.depth_max_frames >= 2 && var.depth_max_frames <= 144
    error_message = "depth_max_frames must be between 2 and 144; an L4 runs out of memory above 144 at 504 px."
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

variable "api_allowed_origins" {
  description = "Browser origins allowed to call the API cross-origin. Empty keeps CORS disabled."
  type        = list(string)
  default     = []

  validation {
    condition = alltrue([
      for origin in var.api_allowed_origins :
      can(regex("^https?://[a-z0-9.-]+(:[0-9]{1,5})?$", origin))
    ])
    error_message = "Each api_allowed_origins entry must be scheme://host[:port] with no trailing path."
  }
}

variable "cloudflare_proxy_enabled" {
  description = "Whether Cloudflare proxies the API DNS record."
  type        = bool
  default     = false

  validation {
    condition     = !var.cloudflare_proxy_enabled || var.api_hostname != null
    error_message = "cloudflare_proxy_enabled requires api_hostname."
  }
}

variable "restrict_api_origin_to_cloudflare" {
  description = "Whether the API origin only accepts Cloudflare source IP ranges."
  type        = bool
  default     = false

  validation {
    condition     = !var.restrict_api_origin_to_cloudflare || var.cloudflare_proxy_enabled
    error_message = "Origin restriction requires cloudflare_proxy_enabled to be true."
  }
}

variable "cloudflare_api_cache_bypass_enabled" {
  description = "Whether Cloudflare bypasses cache for the API hostname. Takes effect with the proxy."
  type        = bool
  default     = true
}

variable "cloudflare_zone_firewall_ruleset_id" {
  description = <<-EOT
    The zone's existing entry-point ruleset in `http_request_firewall_custom`, when this stack
    should manage it. Cloudflare allows exactly one per zone phase, so a second one cannot be
    created: to add a rule for the API, this configuration has to own the ruleset that is already
    there, including the rules that belong to other subdomains.

    Naming it here opts in, and the ruleset must be imported before the first apply:

      terraform import 'cloudflare_ruleset.zone_firewall[0]' zones/<zone id>/<ruleset id>

    Leave null and the zone's firewall stays entirely outside Terraform. List what a zone has with
    GET /zones/<zone id>/rulesets.
  EOT
  type        = string
  default     = null
}

variable "cloudflare_api_waf_enabled" {
  description = "Whether hostname-specific Cloudflare WAF rules are enabled."
  type        = bool
  default     = false

  validation {
    condition     = !var.cloudflare_api_waf_enabled || var.cloudflare_proxy_enabled
    error_message = "cloudflare_api_waf_enabled requires cloudflare_proxy_enabled to be true."
  }
}

variable "cloudflare_api_rate_limit_enabled" {
  description = "Whether rate limiting is enabled for the API hostname."
  type        = bool
  default     = false

  validation {
    condition     = !var.cloudflare_api_rate_limit_enabled || var.cloudflare_proxy_enabled
    error_message = "cloudflare_api_rate_limit_enabled requires cloudflare_proxy_enabled to be true."
  }
}

variable "cloudflare_api_rate_limit_requests" {
  description = "Maximum requests allowed during the rate-limit period."
  type        = number
  default     = 120

  validation {
    condition     = var.cloudflare_api_rate_limit_requests > 0
    error_message = "cloudflare_api_rate_limit_requests must be greater than zero."
  }
}

variable "cloudflare_api_rate_limit_period_seconds" {
  description = "Rate-limit evaluation period in seconds."
  type        = number
  default     = 60

  validation {
    # Cloudflare accepts only these counting periods for a rate-limiting rule.
    condition     = contains([10, 60, 600, 3600], var.cloudflare_api_rate_limit_period_seconds)
    error_message = "cloudflare_api_rate_limit_period_seconds must be 10, 60, 600 or 3600."
  }
}

variable "manage_cloudflare_zone_security_settings" {
  description = "Whether Terraform manages zone-wide Cloudflare TLS settings."
  type        = bool
  default     = false

  validation {
    condition     = !var.manage_cloudflare_zone_security_settings || var.cloudflare_zone_id != null
    error_message = "manage_cloudflare_zone_security_settings requires cloudflare_zone_id."
  }
}

variable "deployment_revision" {
  description = "Suffix appended to the Container Apps revision names. Change it to roll a fresh revision of all three workloads without changing anything else, which is how a revision left stuck by a failed image pull is recovered."
  type        = string
  default     = null
  nullable    = true

  validation {
    condition     = var.deployment_revision == null || can(regex("^[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?$", var.deployment_revision))
    error_message = "deployment_revision must be 1 to 32 lowercase alphanumeric characters or hyphens, starting and ending alphanumeric."
  }
}

variable "jwt_issuer" {
  description = <<-EOT
    Issuer stamped into every access token and validated on every request. Defaults to the API's
    own https URL, which is what a client can verify against /.well-known/jwks.json.
  EOT
  type        = string
  default     = null
  nullable    = true

  validation {
    condition     = var.jwt_issuer == null || can(regex("^https://[a-z0-9.-]+$", var.jwt_issuer))
    error_message = "jwt_issuer must be an https URL with no path when provided."
  }
}

variable "jwt_audience" {
  description = "Audience claim every access token carries and every request is checked against."
  type        = string
  default     = "greenv-video-api"

  validation {
    condition     = length(trimspace(var.jwt_audience)) > 0
    error_message = "jwt_audience must not be empty."
  }
}

variable "access_token_ttl_minutes" {
  description = <<-EOT
    Lifetime of a human access token. Short on purpose: an access token is signed and cannot be
    unsigned, so its lifetime bounds how long a leaked one is useful.
  EOT
  type        = number
  default     = 15

  validation {
    condition     = var.access_token_ttl_minutes >= 1 && var.access_token_ttl_minutes <= 60
    error_message = "access_token_ttl_minutes must be between 1 and 60."
  }
}

variable "refresh_token_ttl_days" {
  description = "Absolute lifetime of a login, counted from the login itself rather than the last rotation."
  type        = number
  default     = 7

  validation {
    condition     = var.refresh_token_ttl_days >= 1 && var.refresh_token_ttl_days <= 30
    error_message = "refresh_token_ttl_days must be between 1 and 30."
  }
}

variable "client_credentials_ttl_hours" {
  description = <<-EOT
    Lifetime of a machine token. There is no refresh chain and no revocation before expiry, so this
    number is the whole control over a leaked machine credential.
  EOT
  type        = number
  default     = 4

  validation {
    condition     = var.client_credentials_ttl_hours >= 1 && var.client_credentials_ttl_hours <= 24
    error_message = "client_credentials_ttl_hours must be between 1 and 24."
  }
}

variable "cookie_same_site" {
  description = <<-EOT
    SameSite attribute on the session cookies. Lax is correct while the dashboard and the API share
    a registrable domain. None makes the session a third-party cookie, which Safari and Firefox
    block outright - a property only so that choosing it has to be deliberate.
  EOT
  type        = string
  default     = "Lax"

  validation {
    condition     = contains(["Lax", "Strict", "None"], var.cookie_same_site)
    error_message = "cookie_same_site must be Lax, Strict or None."
  }
}

variable "cookie_csrf_domain" {
  description = <<-EOT
    Registrable domain for the CSRF cookie, and for that one alone. The dashboard has to read it
    back into X-CSRF-Token on every write; served from a different subdomain than the API, a
    host-only cookie is invisible to its script and every write answers 401. Leave empty when the
    dashboard and the API share a host. The session cookies keep the __Host- prefix, which forbids
    a domain outright, and are never widened by this.
  EOT
  type        = string
  default     = ""
}

variable "runpod_api_key" {
  description = <<-EOT
    RunPod account key, used to create and read the depth endpoint. Set it as
    `TF_VAR_runpod_api_key` in the shell, never in a file: `terraform.tfvars` is gitignored today
    and one `git add -f` away from not being.

    Unused unless `measurement_enabled` reaches the depth stage through RunPod.
  EOT
  type        = string
  sensitive   = true
  default     = null
}

variable "depth_template_id" {
  description = <<-EOT
    RunPod template the depth endpoint is built from.

    `services/greenv-depth-runpod/provision.mjs` creates the template and writes this id into
    `infrastructure/runpod.auto.tfvars`, which Terraform loads on its own - run the script, then
    apply. The script owns the template because this provider has a data source for templates and
    no resource: the image, the container disk and the bucket credentials cannot be expressed here.

    Null leaves the endpoint uncreated, which is what a deployment naming an endpoint someone else
    made in `depth_service_endpoint_id` wants.
  EOT
  type        = string
  default     = null
}

variable "depth_gpu_type_ids" {
  description = <<-EOT
    GPU types the depth endpoint may schedule on, in RunPod's own spelling.

    Every memory ceiling on record was measured on an L4. The handler refuses a smaller card at
    startup rather than being killed mid-run, so a wider list here is a promise this repository
    cannot keep - widen it only alongside a measurement.
  EOT
  type        = list(string)
  default     = ["NVIDIA L4"]

  validation {
    condition     = length(var.depth_gpu_type_ids) > 0
    error_message = "An endpoint with no GPU type can never schedule a worker."
  }
}

variable "depth_idle_timeout_seconds" {
  description = <<-EOT
    How long a depth worker stays warm after finishing, in seconds.

    This is where the bill lives. Segments arriving back to back from one drive ride a single warm
    worker and save a model load each; a lone segment pays the whole tail. Short by default, to be
    raised deliberately once a real drive shows how segments actually arrive.
  EOT
  type        = number
  default     = 60

  validation {
    condition     = var.depth_idle_timeout_seconds >= 1 && var.depth_idle_timeout_seconds <= 3600
    error_message = "RunPod accepts an idle timeout between 1 and 3600 seconds."
  }
}

variable "depth_image" {
  description = <<-EOT
    The RunPod handler image, preferably pinned by sha256 digest like every other image here.

    It reaches RunPod through the template `provision.mjs` creates during the apply, not through a
    container app, which is why it is not spelled `*_image` beside the other three: nothing in
    Azure ever pulls it.

    Built from `services/greenv-depth-runpod/`, FROM the DA3 service image, and about 15.3 GB
    unpacked - see that directory's README.
  EOT
  type        = string
  default     = "ghcr.io/matomomitsu/greenv-depth-runpod@sha256:98de81166e77fa96ba21e2374862db97d080d08f3e5816bce6c21d8a413ce4fa"
}
