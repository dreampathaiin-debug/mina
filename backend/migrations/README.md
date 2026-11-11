# Migrations

Migrations are the single source of schema truth. Never change the schema
from the Supabase dashboard only.

Apply:

    psql "$DATABASE_URL" -f migrations/0001_init.sql
    # or, with the Supabase CLI:
    supabase db push

Notes:
- 0001_init.sql creates the nine tenant tables, triggers, indexes and
  enables Row Level Security with auth.uid() policies on every table.
- profiles.id references auth.users(id) on delete cascade, so deleting a
  Supabase auth user removes all of their data.
- New migrations get ascending numbers and are applied in order.

## 0002_provider_keys_phase3.sql

Adds envelope-encryption columns (key_version, wrapped_dek), source
(byok/managed), status (active/disabled) and per-key health fields to
provider_keys; creates user_ai_settings and provider_health with RLS.
Phase 2 rows (wrapped_dek NULL) decrypt through the legacy single-key path.
New env variables: MINA_ENCRYPTION_KEYS_JSON (key ring),
MINA_EXTRA_PROVIDERS, MINA_MANAGED_PROVIDER_KEYS.
