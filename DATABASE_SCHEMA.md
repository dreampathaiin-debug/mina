# DATABASE_SCHEMA.md

PostgreSQL (Supabase). ORM models in `mina_api` define the tables; `migrations/0001`-`0009` apply them plus row-level security. Migrations are forward-only; `scripts/apply_migrations.py` tracks applied files in `_mina_migrations`.

## Tables (from live model metadata)

- `agent_events`
- `agent_runs`
- `approvals`
- `budget_alerts`
- `composio_connections`
- `composio_executions`
- `conversations`
- `durable_tasks`
- `memories`
- `messages`
- `optimizer_events`
- `profiles`
- `provider_health`
- `provider_keys`
- `semantic_cache_entries`
- `task_events`
- `tasks`
- `usage_budgets`
- `usage_events`
- `usage_logs`
- `user_ai_settings`

Plus, from the migration chain where ORM-created: `profiles` (account state, soft delete, admin flag, preferred model/group columns), `provider_keys` (envelope-encrypted BYOK keys: key_id, ciphertext DEK, wrapped KEK, masked preview, status), `conversations`, `messages`, `memories` (kind, content, confidence, source, source_ref, status, superseded_by, user_approved, expires_at, last_accessed_at, embedding), `composio_connections` (per-user Composio account linkage and status), `composio_approvals`-style approval records.

## Key invariants

- Every user-owned table carries an owner RLS policy on `auth.uid()` (all nine migrations), making the database a second isolation layer behind the API's own scoping. This only holds if the runtime role lacks BYPASSRLS/superuser.
- `durable_tasks`: unique (user_id, idempotency_key); `task_events`: unique per-task sequence; leases carry expiry so worker restarts cannot double-execute.
- `usage_events`: one row per model call with provider, model, selection reason, tokens, estimated cost, cost basis, billing source, cache hit, latency, retries, fallbacks; `usage_budgets` + `budget_alerts` enforce per-user and per-provider caps.
- `semantic_cache_entries` and `optimizer_events` power routing cache and telemetry; cache keys are user-scoped and invalidated by tag.
- `composio_executions`: argument KEYS + fingerprint only (never values), capped result excerpts.
- `memories`: `status` lifecycle (active/superseded/expired/deleted); secrets never stored; pgvector `embedding_vec vector(384)` with ivfflat index guarded by a DO/EXCEPTION fallback.

## Migration discipline

Forward-only SQL, no destructive statements. Applied set tracked in `_mina_migrations(name, applied_at)`. A data-level rollback means restoring a Supabase backup to before the deploy, never hand-written reverse SQL.
