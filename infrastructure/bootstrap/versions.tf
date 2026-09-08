terraform {
  required_version = ">= 1.15.9, < 2.0.0"

  required_providers {
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.24"
    }
  }
}

provider "cloudflare" {}
