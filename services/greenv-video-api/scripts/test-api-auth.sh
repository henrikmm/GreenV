#!/usr/bin/env bash
set -euo pipefail

api_url="${API_URL:-https://greenvapi.matomomitsu.com}"
api_token="${GREENV_API_TOKEN:?GREENV_API_TOKEN is required}"
probe_path="/v2/capture-sessions/00000000-0000-0000-0000-000000000000"
work_root="$(mktemp -d)"
trap 'rm -rf "${work_root}"' EXIT

request_status() {
  local name="$1"
  shift
  curl --silent --show-error \
    --output "${work_root}/${name}.json" \
    --write-out '%{http_code}' \
    "$@"
}

assert_status() {
  local name="$1"
  local expected="$2"
  local actual="$3"
  if [[ "${actual}" != "${expected}" ]]; then
    echo "${name}: expected HTTP ${expected}, received ${actual}" >&2
    cat "${work_root}/${name}.json" >&2
    exit 1
  fi
}

health_status="$(request_status health "${api_url}/actuator/health")"
missing_status="$(request_status missing "${api_url}${probe_path}")"
invalid_status="$(request_status invalid \
  --header 'Authorization: Bearer invalid-token' \
  "${api_url}${probe_path}")"
valid_status="$(request_status valid \
  --header "Authorization: Bearer ${api_token}" \
  "${api_url}${probe_path}")"

assert_status health 200 "${health_status}"
assert_status missing 401 "${missing_status}"
assert_status invalid 401 "${invalid_status}"
assert_status valid 404 "${valid_status}"

echo "API authentication passed: health=200 missing=401 invalid=401 valid=404"
