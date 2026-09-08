#!/usr/bin/env bash
# Runs the browser capture build against a deployed API, using the workstation's own webcam.
#
# The browser needs a secure context for getUserMedia, which http://localhost provides, and the
# API needs that exact origin in GREENV_ALLOWED_ORIGINS - which is why the port is pinned. Let
# Flutter pick a port and the preflight comes back 403.
#
# Real capture is the default now, so no flag turns it on. Sign in on the app's own login screen;
# GREENV_API_TOKEN is no longer needed and is only worth passing to reach an API whose identity
# provider is switched off.
#
#   GREENV_API_URL=https://... ./scripts/run-cloud-web.sh [-d edge]
set -euo pipefail

api_url="${GREENV_API_URL:?set GREENV_API_URL to the deployed API, e.g. https://greenvapi.example}"
api_token="${GREENV_API_TOKEN:-}"
port="${GREENV_WEB_PORT:-5173}"
device="${GREENV_WEB_DEVICE:-chrome}"

exec flutter run \
  -d "${device}" \
  --web-port "${port}" \
  --web-hostname localhost \
  --dart-define=GREENV_API_URL="${api_url}" \
  --dart-define=GREENV_API_TOKEN="${api_token}" \
  "$@"
