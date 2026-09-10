#!/usr/bin/env bash
# Phase 11 — the module's finishing gate: runs the test suite and confirms the OpenAPI snapshot
# is still in sync with the live app. Scope note: TASK.md's original text also names `ruff` and a
# format check; neither was added here, since no lint/format tool was installed or requested by
# this phase's actual instruction, and adding one now would be new, unrequested tooling.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== pytest =="
python -m pytest

echo
echo "== OpenAPI snapshot in sync =="
python -c "
import sys, yaml
sys.path.insert(0, 'src')
from greenv_vegpred.api.app import app
live = app.openapi()
committed = yaml.safe_load(open('api/openapi.v1.yaml', encoding='utf-8'))
if live != committed:
    print('api/openapi.v1.yaml is OUT OF SYNC with the live app -- regenerate it (see reports/api.md).')
    sys.exit(1)
print('api/openapi.v1.yaml matches the live app schema.')
"

echo
echo "verify.sh: all checks passed."
