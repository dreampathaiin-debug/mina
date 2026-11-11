# API_DOCUMENTATION.md

Base URL: `https://<production-host>` (Cloudflare-fronted Render service). All paths below are prefixed `/v1`.
Auth: every user route requires `Authorization: Bearer <supabase-jwt>`. Public routes: `/health`, `/ready`, `/version`. Admin routes additionally require the admin role.
Errors: typed JSON envelope `{"detail": ...}` with correct status codes (401 unauthenticated, 403 forbidden/approval-required, 404 not-found-or-not-yours, 409 conflict/reauthorization, 422 validation, 429 rate limited with `Retry-After`, 503 dependency disabled by design).

## Account (`/me`, `/account`, `/settings`, `/catalog`)
| Method | Path | Purpose |
|---|---|---|
| GET | /me | Current user profile |
| DELETE | /account | Delete account and all owned data (204) |
| GET/PATCH | /settings/ai-policy | Model preferences (preferred model, group: fast/cheap/balanced/powerful) |
| GET | /catalog | Model catalog |

## Provider keys (`/providers/keys`) - BYOK, encrypted at rest
GET list (masked previews only), POST add, PATCH update, POST `/{key_id}/rotate`, POST `/{key_id}/test`, DELETE `/{key_id}` (204). Plaintext keys are never returned after creation.

## Routing (`/routing`)
GET /catalog, POST /preview (dry-run routing plan; leaks no key material; candidate fields limited to model id/provider/source/reason/priority/context window/timeout/estimated cost), GET /health, GET /usage (auth required).

## Connections + tools (`/connections`, `/tools`) - Composio, server-managed
GET /connections, POST /connections/{app}/connect (201, redirect URL), GET /connections/{id}, DELETE /connections/{id} (revokes upstream), GET /tools/search (schemas only with include_schemas=true), POST /tools/execute (schema-validated args; dangerous slugs require a single-use approval), GET /tools/executions/{id}. Without MINA_COMPOSIO_API_KEY these answer 503 by design.

## Memory (`/memory`)
GET list (kind/limit), POST create (secret-shaped content 422; sensitive categories need approval + reason), PATCH /{id} (correction supersedes with audit trail), DELETE /{id}?purge= (soft by default), POST /search {query, k, kinds, min_confidence}.

## Conversations (`/conversations`)
GET list, POST create (201), GET /{id}, GET /{id}/messages, POST /{id}/messages (201), DELETE /{id} (204).

## Agent runs (`/agent`)
POST /runs (201), GET /runs/{id}, GET /runs/{id}/events, POST /runs/{id}/approve - wait, approvals live under tasks too; agent approvals: POST /runs/{id}/approvals/{approval_id}, POST /runs/{id}/cancel, POST /runs/{id}/pause, POST /runs/{id}/resume, POST /runs/{id}/device-results.

## Durable tasks (`/tasks`)
GET /tasks, POST /tasks (201, idempotency key honoured), GET /tasks/{id}, GET /tasks/{id}/events, GET /tasks/{id}/events/stream (SSE), POST /{task_id}/approve|cancel|execute|pause|resume, POST /worker/tick (requires X-Worker-Token; 403 without it).

## Observability (`/observability`, `/budgets`)
GET /usage, /dashboard, /metrics, /events, /alerts, /executions/{id}; budget endpoints under /budgets (per-user daily/monthly, per-provider caps, hard stops).

## Ops
GET /health (liveness), GET /ready (readiness: DB, Redis, provider health), GET /version.

## Android client mapping
The app's `com.meenai.mina.server.MinaApi` implements exactly these surfaces with typed failure handling (401 refresh-and-replay, 429 Retry-After, offline/unreachable distinction). See ANDROID_INTEGRATION.md.

Note: request/response field names are defined by the Pydantic models referenced in each router (`response_model=...`); this document intentionally lists surfaces and semantics rather than duplicating every schema.
