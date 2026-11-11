# DEPLOYMENT_GUIDE.md

Nothing in this guide runs automatically. Every gate marked [YOU] needs an explicit approval or your account access.

## Pre-deploy checklist

1. [YOU] Approve commit and push of all phases (backend + Android integration + these docs).
2. [YOU] Create the Supabase production project; collect project URL, JWT secret, database URL.
3. [YOU] Create/confirm Render + Cloudflare accounts.
4. Secrets: generate encryption key ring and worker token (commands in OPERATIONS_RUNBOOK.md).

## Deployment steps, in order

1. Apply migrations to the clean production database (this is the clean-DB verification):
   ```
   cd backend && MINA_SUPABASE_DB_URL="postgresql://postgres:<pw>@db.<ref>.supabase.co:5432/postgres" \
     .venv/bin/python scripts/apply_migrations.py
   ```
   Expected output: APPLIED for each of the nine files, then "OK migrations up to date". Re-running is a no-op.
2. [YOU] Deploy the Render blueprint (backend/render.yaml): web service (Docker runtime, health check /v1/ready, pre-deploy migrations, rate limit pinned at 120 rpm) + Redis key-value store + worker-tick background service. Fill the sync:false secret fields once in the dashboard from .env.production.example.
3. [YOU] Paste the auto-generated MINA_WORKER_TOKEN value into the worker service too.
4. Verify the deployment before any DNS change:
   ```
   cd backend && MINA_SMOKE_URL=https://<render-url>.onrender.com .venv/bin/python scripts/smoke_deploy.py
   MINA_SMOKE_URL=... MINA_SMOKE_JWT=<test-user-jwt> .venv/bin/python scripts/smoke_deploy.py --probe-rate-limit
   ```
   All lines must read PASS.
5. [YOU] Cloudflare: CNAME api.<domain> to the Render service, proxy ON, SSL Full (strict). Then re-run the smoke script against the Cloudflare hostname.
6. [YOU] Build the signed Android release with -Pmina.apiBaseUrl=https://api.<domain> -Pmina.supabaseUrl=... -Pmina.supabaseAnonKey=...
7. [YOU] Explicit production release approval. STOP point: no release goes out without it.

## Rollback plan

- App kill switch (instant): clear the base URL in app settings or ship a build without mina.apiBaseUrl. Every user falls back to on-device BYOK with zero data loss.
- Render rollback (minutes): redeploy the previous deployment from the dashboard; the pre-deploy migration step is a no-op on older builds because applied migrations are tracked.
- Database: forward-only migrations; a data rollback means restoring the Supabase backup (PITR or last pg_dump) to just before the deploy. Never hand-write reverse SQL.
- Credentials: rotate MINA_WORKER_TOKEN and the Supabase JWT secret from their dashboards; rotating the encryption key ring re-wraps DEKs without touching plaintext keys.
- Traffic: Cloudflare can repoint or drop the proxy in one click while the API recovers.

## Monitoring and backups

Structured JSON logs to the Render log stream; /v1/health (liveness) and /v1/ready (DB, Redis, provider health) probes; observability endpoints expose metrics, alerts and budgets for any external monitor. Backups: Supabase point-in-time recovery on a paid plan, or scheduled pg_dump; test a restore before release.
