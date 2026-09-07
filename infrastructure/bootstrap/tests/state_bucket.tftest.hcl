mock_provider "cloudflare" {}

run "plans_private_standard_state_bucket" {
  command = plan

  variables {
    cloudflare_account_id   = "00000000000000000000000000000000"
    state_bucket_name       = "greenv-terraform-state-test"
    application_bucket_name = "greenv-mvp-captures-test"
  }

  assert {
    condition     = cloudflare_r2_bucket.terraform_state.storage_class == "Standard"
    error_message = "Terraform state must use R2 Standard storage."
  }

  assert {
    condition     = cloudflare_r2_bucket.terraform_state.name == "greenv-terraform-state-test"
    error_message = "The state bucket name must remain operator-controlled."
  }

  assert {
    condition     = cloudflare_r2_bucket.application.storage_class == "Standard"
    error_message = "Application objects must use R2 Standard storage."
  }

  assert {
    condition     = cloudflare_r2_bucket.application.name != cloudflare_r2_bucket.terraform_state.name
    error_message = "Application objects and Terraform state must use separate buckets."
  }
}
