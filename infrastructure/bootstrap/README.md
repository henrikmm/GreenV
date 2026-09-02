# R2 state bootstrap

This root module creates two private R2 buckets: one for Terraform state and one for GreenV capture
objects. It exists separately because a Terraform backend must already exist when the main root is
initialized, and an R2 S3 credential can be bucket-scoped only after its bucket exists.

Run `terraform init`, `terraform plan` and `terraform apply` from this directory after copying
`terraform.tfvars.example` to the ignored `terraform.tfvars`. The Cloudflare provider reads
`CLOUDFLARE_API_TOKEN`. See the parent [README](../README.md) for the complete sequence, credential
separation and recovery notes.

Both buckets use `prevent_destroy`; no test or validation command creates them. After the initial
local apply, copy the backend examples and migrate this root's own state into the state bucket. The
native test in `tests/` uses a mocked Cloudflare provider.
