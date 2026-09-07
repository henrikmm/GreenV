resource "neon_project" "database" {
  name                      = "${local.name_prefix}-database"
  region_id                 = var.neon_region_id
  pg_version                = 17
  history_retention_seconds = 21600

  branch {
    name          = "production"
    database_name = "greenv"
    role_name     = "greenv_owner"
  }

  default_endpoint_settings {
    autoscaling_limit_min_cu = 0.25
    autoscaling_limit_max_cu = 1
  }
}
