#!/usr/bin/env bash
# Prints an export line for a JWT signing key that survives restarts, for local development.
#
# The compose stack defaults to GREENV_JWT_EPHEMERAL_KEY=true, which generates a key at boot and
# throws it away on shutdown - convenient, but it signs everyone out on every restart. Use this when
# you want a stable local session.
#
#   eval "$(./scripts/generate-dev-jwt-key.sh)" && docker compose up
#
# genpkey already emits PKCS#8 ("-----BEGIN PRIVATE KEY-----"), which is the only form the API's
# key loader accepts. Base64 on one line, because a multi-line PEM does not survive an
# environment variable reliably.
#
# Never commit the output, and never use it for a deployment - Terraform provisions that key.
set -euo pipefail

pem="$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null)"

if ! printf '%s' "$pem" | head -1 | grep -q -- "-----BEGIN PRIVATE KEY-----"; then
  echo "openssl did not produce a PKCS#8 key; the API would reject it" >&2
  exit 1
fi

key="$(printf '%s' "$pem" | base64 -w0)"

echo "export GREENV_JWT_PRIVATE_KEY='${key}'"
echo "export GREENV_JWT_EPHEMERAL_KEY=false"
