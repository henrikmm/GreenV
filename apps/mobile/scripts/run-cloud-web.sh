#!/usr/bin/env bash
# Runs the browser capture build against a deployed API, using the workstation's own webcam.
#
# The browser needs a secure context for getUserMedia, which http://localhost provides, and the
# API needs that exact origin in GREENV_ALLOWED_ORIGINS, which is why the port is pinned.
#
#   GREENV_API_URL=https://... GREENV_API_TOKEN=... ./scripts/run-cloud-web.sh [-d edge]
set -euo pipefail

api_url="${GREENV_API_URL:?set GREENV_API_URL to the deployed API, e.g. https://greenvapi.example}"
api_token="${GREENV_API_TOKEN:?set GREENV_API_TOKEN to the API Bearer token}"
port="${GREENV_WEB_PORT:-5173}"
device="${GREENV_WEB_DEVICE:-chrome}"

exec flutter run \
  -d "${device}" \
  --web-port "${port}" \
  --web-hostname localhost \
  --dart-define=GREENV_WEB_CAPTURE=true \
  --dart-define=GREENV_API_URL="${api_url}" \
  --dart-define=GREENV_API_TOKEN="${api_token}" \
  "$@"
