#!/usr/bin/env bash
# Walks the whole identity flow against a running API and prints what each step returned.
#
# Sibling of test-api-auth.sh, and read-only apart from the user and client it creates. Needs
# GREENV_API_TOKEN, which is the only credential allowed to create identities.
#
#   API_URL=http://localhost:8080 bash scripts/test-identity.sh
set -euo pipefail

API_URL="${API_URL:-http://localhost:8080}"
: "${GREENV_API_TOKEN:?set GREENV_API_TOKEN (the operational token) first}"

email="smoke-$(date +%s)@motiva.com.br"
password="uma-senha-bem-longa-123"
headers="$(mktemp)"
trap 'rm -f "$headers"' EXIT

status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
jar()    { grep -i '^set-cookie:' "$1" | sed 's/^[Ss]et-[Cc]ookie: //' | cut -d';' -f1 | tr '\n' ';' | sed 's/;$//'; }
csrf()   { grep -i '^set-cookie: greenv_csrf=' "$1" | sed 's/.*greenv_csrf=//' | cut -d';' -f1; }

echo "create user            $(status -X POST "$API_URL/v2/identity/users" \
  -H "Authorization: Bearer $GREENV_API_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$email\",\"password\":\"$password\",\"displayName\":\"Smoke\"}")  (expect 201)"

echo "create without token   $(status -X POST "$API_URL/v2/identity/users" \
  -H 'Content-Type: application/json' -d '{"email":"x@y.com","password":"uma-senha-bem-longa-123"}')  (expect 401)"

curl -s -D "$headers" -o /dev/null -X POST "$API_URL/v2/auth/login" \
  -H 'Content-Type: application/json' -d "{\"email\":\"$email\",\"password\":\"$password\"}"
J1="$(jar "$headers")"; C1="$(csrf "$headers")"
echo "login                  $(grep -ci '^set-cookie:' "$headers") cookies  (expect 4)"

echo "wrong password         $(status -X POST "$API_URL/v2/auth/login" \
  -H 'Content-Type: application/json' -d "{\"email\":\"$email\",\"password\":\"outra-senha-longa-9\"}")  (expect 401)"

echo "me with cookies        $(status "$API_URL/v2/auth/me" -H "Cookie: $J1")  (expect 200)"

nofgp="$(echo "$J1" | tr ';' '\n' | grep -v '__Host-greenv_fgp' | tr '\n' ';' | sed 's/;$//')"
echo "me without fingerprint $(status "$API_URL/v2/auth/me" -H "Cookie: $nofgp")  (expect 401)"

echo "write without CSRF     $(status -X POST "$API_URL/v2/capture-sessions" -H "Cookie: $J1" \
  -H 'Content-Type: application/json' -d '{"deviceId":"smoke","startedAt":"2026-01-01T00:00:00Z"}')  (expect 401)"

curl -s -D "$headers" -o /dev/null -X POST "$API_URL/v2/auth/refresh" -H "Cookie: $J1" -H "X-CSRF-Token: $C1"
J2="$(jar "$headers")"
echo "rotate                 $(status "$API_URL/v2/auth/me" -H "Cookie: $J2")  (expect 200)"

echo "replay retired token   $(status -X POST "$API_URL/v2/auth/refresh" -H "Cookie: $J1" -H "X-CSRF-Token: $C1")  (expect 401)"
echo "family revoked         $(status "$API_URL/v2/auth/me" -H "Cookie: $J2")  (expect 401)"

curl -s -o /tmp/greenv-client.json -X POST "$API_URL/v2/identity/clients" \
  -H "Authorization: Bearer $GREENV_API_TOKEN" -H 'Content-Type: application/json' \
  -d '{"displayName":"Smoke client","role":"CAPTURE_CLIENT"}'
cid="$(python3 -c "import json;print(json.load(open('/tmp/greenv-client.json'))['clientId'])")"
csec="$(python3 -c "import json;print(json.load(open('/tmp/greenv-client.json'))['clientSecret'])")"
rm -f /tmp/greenv-client.json

curl -s -o /tmp/greenv-token.json -X POST "$API_URL/v2/oauth/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "grant_type=client_credentials&client_id=$cid&client_secret=$csec"
echo "client_credentials     $(python3 -c "import json;d=json.load(open('/tmp/greenv-token.json'));print(d['expires_in'],'s, refresh_token:', d.get('refresh_token','none'))")  (expect 14400s, none)"
rm -f /tmp/greenv-token.json

echo "jwks                   $(status "$API_URL/.well-known/jwks.json")  (expect 200, public)"
echo "static token still ok  $(status "$API_URL/v2/capture-sessions/00000000-0000-0000-0000-000000000000" \
  -H "Authorization: Bearer $GREENV_API_TOKEN")  (expect 404)"
