# FINAL_ARCHITECTURE.md

Mina backend - principal engineering review for first public production release.
Reviewed 11 November 2026 against the code in this repository. No new features were added during this review; one configuration-contract test was updated to match the intentional production blueprint change (see Security Report).

## System shape

Single FastAPI service (`mina_api`) backed by PostgreSQL (Supabase in production), Redis-optional caching, LiteLLM for provider calls, Composio for third-party tool integrations, Supabase Auth JWTs for identity. The Android app is the primary client: on-device BYOK stays fully functional with no server configured, and the server layer is strictly additive.

Layering: `api/v1` routers -> services (`agent/`, `routing/`, `memory/`, `integrations/composio.py`, `observability/`, `tasks/`) -> `core/` (config, middleware, errors) -> `security/` (JWT, envelope encryption) -> SQLAlchemy models + `migrations/` SQL.

Request lifecycle: RequestId sanitization -> Timeout -> Rate limit (sliding 60s window, client IP + credential hash) -> CORS -> JWT verification (Supabase HS256, issuer/audience/expiry/subject) -> profile check (deleted accounts rejected) -> handler -> safe error envelope.

## Review verdict by verification point

| # | Requirement | Verdict | Evidence |
|---|---|---|---|
| 1 | All API endpoints work | Pass (with note) | Full route table read this review; contract paths covered across suites; a handful of admin-only routes have thinner coverage |
| 2 | Migrations work from clean DB | **Unverified in sandbox** | 9 migration files reviewed; no Postgres available here to execute them; must run on a clean Supabase project before release |
| 3 | RLS policies work | Code-verified only | Owner policies present in all nine migrations; cannot execute without Supabase; runtime role must not hold BYPASSRLS |
| 4 | No cross-user data access | Pass | Cross-user 404s asserted across connections, tools, memories, tasks, executions, keys, approvals |
| 5 | BYOK independent per user | Pass | Per-user envelope-encrypted keys; per-request key selection scoped to the caller |
| 6 | Managed billing explicit | Pass | Policy gate byok_only / byok_first / managed_only; ledger records cost basis and billing source |
| 7 | Routing selects compatible models | Pass | Capability/context-window escalation + policy gate; invalid preference group 422 |
| 8 | Provider failover works | Pass | Health-driven candidate ordering, auth/429 cooldowns, circuit breaker |
| 9 | No infinite retry loops | Pass | max_retries=2 per request, Retry-After honoured, attempt caps on 429s client-side |
| 10 | Token tracking accurate | Pass | Sliding RPM/TPM per provider + per-request usage events; estimates are labelled estimates |
| 11 | Cache invalidation works | Pass | Tag-based invalidation (O(1) per tag), TTL, user-scoped keys; replay scoped per user is tested |
| 12 | Composio connections user-scoped | Pass | Per-user Composio namespace + ownership check on every read/execute/delete |
| 13 | Tool execution respects approvals | Pass | Dangerous-tool gate + user-scoped single-use approvals (audit fix), in-flight dedupe |
| 14 | Device commands permission-controlled | Pass | Every device instruction carries its required permission; the app validates against its own permission gates |
| 15 | Tasks survive worker restarts | Pass | Lease-based durable tasks with expiry; tick driver is now token-gated and fails closed |
| 16 | Memory respects user boundaries | Pass | Ownership on get/update/delete/search; secret-shaped strings dropped before storage |
| 17 | Budgets enforced atomically | Pass | Budget checks gate before spend; per-user daily/monthly + provider caps with hard stops |
| 18 | Logs contain no secrets | Pass | Sanitized request IDs, no auth headers logged, arguments stored as keys/fingerprints, tests assert non-leakage |
| 19 | All tests pass | Pass | **288 tests, 0 failures** (full suite, this review) |
| 20 | Deployment/rollback instructions valid | Staged | Blueprint + scripts written and contract-tested; nothing executed (deploy requires approval) |

## Readiness score: 72/100 (staging-ready, not production-ready)

| Area | Score | Basis |
|---|---|---|
| Backend correctness and tests | 9/10 | 288/288 green, dependency scan clean |
| Security posture | 8/10 | Audit fixed 3 real vulnerabilities; RLS execution pending on real Supabase |
| Deployment readiness | 6/10 | Config staged and contract-tested; Docker build + clean-DB migration unexecuted |
| Android integration | 5/10 | Written and reviewed; not compiled or device-tested here |
| Performance evidence | 4/10 | In-process smoke only (200 req, 0 errors, p50 110ms, p99 435ms); no production load test |

## Critical blockers before production release

1. Execute all nine migrations against a clean Supabase/Postgres database and verify RLS as a non-superuser role.
2. Build the production Docker image (no Docker daemon in the review sandbox; build is untested).
3. Compile the Android app and run the device test matrix (no SDK in the sandbox).
4. Run a real load test against a deployed staging URL (this review ran only an in-process smoke).
5. Commit and push all phases (currently uncommitted in the workspace by your instruction).

## Remaining known limitations

- Rate limiter is per-process; a second replica needs a shared store (Redis) or limits effectively double.
- Prompt injection is inherent to agents; blast radius is contained (approvals, schema validation, secret scrubbing) but not eliminated.
- No webhook endpoints exist; HMAC verification is required the day one is added.
- Memory embeddings are deterministic hash-based, not model-quality semantic search.
- BYOK direct-to-Composio path in the app coexists with the server-managed path by design.

## Exact commands

Local run:
```
cd /workspace/meena/backend
MINA_ENVIRONMENT=development MINA_DATABASE_URL="sqlite+aiosqlite:///./mina.db" \
  .venv/bin/python -m uvicorn mina_api.main:app --port 8000
```

Tests:
```
cd /workspace/meena/backend
.venv/bin/python -m pytest -q -p no:warnings tests/          # full suite (288)
.venv/bin/python -m pytest -q -p no:warnings tests/test_security_audit.py  # attack scenarios
$HOME/.local/bin/uv tool run pip-audit -r requirements.lock --skip-editable  # dependencies
PYTHONPATH=. .venv/bin/python scripts/load_smoke.py          # in-process concurrency smoke
```

Production deployment: see DEPLOYMENT_GUIDE.md (ordered steps, each gated on explicit approval).

## Release decision

**HOLD.** Backend code and tests are release-grade on the evidence; the release itself is blocked on the five items above. Nothing here should be called production-ready until migrations have run on a clean database, the image has built, and the Android device matrix has passed.
