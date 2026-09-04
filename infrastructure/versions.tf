terraform {
  required_version = ">= 1.15.9, < 2.0.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 5.2"
    }
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.24"
    }
    neon = {
      source  = "kislerdm/neon"
      version = "~> 0.15"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
    time = {
      source  = "hashicorp/time"
      version = "~> 0.14"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.1"
    }
  }
}
