# The JWT signing key.
#
# It has to be state rather than something the process makes at boot: a restart would invalidate
# every live token, and `azurerm_container_app.api` scales past one replica, so two instances
# generating their own keys would reject each other's tokens.
#
# SECURITY NOTICE: the private key is stored unencrypted in Terraform state, as the provider
# documents. The state already holds the Neon password, the R2 keys and the API bearer token, so
# this is the same exposure class - but the consequence is sharper: anyone who can read the state
# can mint a valid token for any user. The state lives in the private R2 bucket for that reason.
#
# Rotating it invalidates every token in circulation, which costs signed-in users one login:
#   terraform taint tls_private_key.jwt_signing && terraform apply
resource "tls_private_key" "jwt_signing" {
  algorithm = "RSA"
  rsa_bits  = 2048
}
