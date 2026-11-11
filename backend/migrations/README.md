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
