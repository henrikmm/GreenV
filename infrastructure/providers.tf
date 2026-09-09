provider "azurerm" {
  features {}
}

provider "cloudflare" {}

provider "neon" {}

# Configured even when unused: a provider block costs nothing until a resource asks it for
# something, and `runpod_endpoint` is created only when the depth stage is switched on.
provider "runpod" {
  api_key = var.runpod_api_key
}
