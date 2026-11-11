# OPERATIONS_RUNBOOK.md

## Daily operations

- Health: GET /v1/health (liveness) and /v1/ready (DB, Redis, provider health). Post-deploy verification: `MINA_SMOKE_URL=https://api.<domain> .venv/bin/python backend/scripts/smoke_deploy.py`.
- Logs: structured JSON in the Render log stream (method, path, status, duration, request id; no auth headers, no key material).
- Alerts: GET /v1/alerts surfaces budget and provider-health alerts; point an external monitor at it.
- Worker: the worker-tick background service POSTs /v1/tasks/worker/tick every 30s with the shared token. If durable tasks stall, first check the worker service logs, then the token match between web and worker services.

## Rotations

- Worker token: rotate in Render (both services must be updated together).
- Supabase JWT secret: rotate in Supabase; existing JWTs invalid afterwards (users re-authenticate).
- Provider key ring: set a new KEK entry in MINA_ENCRYPTION_KEYS_JSON; rotation re-wraps DEKs without touching plaintext keys. Never delete the old key until re-wrap completes.

## Incidents

1. API down: check Render deploy + /v1/ready detail field (names the failing dependency).
2. Suspected key exposure: rotate the affected provider key at the vendor, then rotate it in-app via POST /v1/providers/keys/{id}/rotate. Audit logs: executions store fingerprints only.
3. Runaway spend: budgets hard-stop at configured caps; lower caps via the budget endpoints; optionally disable the affected provider in the routing health endpoint.
4. Rate-limit storms: raise MINA_RATE_LIMIT_REQUESTS_PER_MINUTE and redeploy; per-user concurrency caps already bound agent work.
5. Data incident: restore the Supabase backup (PITR/pg_dump) to before the incident; migrations are forward-only, never hand-write reverse SQL.

## Scaling notes

- Single replica today: rate limiter is per-process. Before adding a replica, move rate limiting to the Redis store or limits effectively double.
- SQLite is local-development only; production requires the Supabase Postgres URL.
- Langfuse observability is optional; the API runs fine without it.

## Standing commands

```
cd backend
.venv/bin/python -m pytest -q -p no:warnings tests/                                  # full suite
$HOME/.local/bin/uv tool run pip-audit -r requirements.lock --skip-editable          # dependency scan
PYTHONPATH=. .venv/bin/python scripts/load_smoke.py                                  # in-process smoke
MINA_SMOKE_URL=https://api.<domain> .venv/bin/python scripts/smoke_deploy.py         # live smoke
```
