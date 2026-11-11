-- Phase 3: multi-provider key manager. Apply in order after 0001_init.sql:
--   psql "$DATABASE_URL" -f migrations/0002_provider_keys_phase3.sql
-- Additive: existing Phase 2 rows keep working (legacy single-key Fernet).

-- provider_keys: envelope-encryption columns + source/status/health fields.
ALTER TABLE public.provider_keys
  ADD COLUMN IF NOT EXISTS key_version integer NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS wrapped_dek text,
  ADD COLUMN IF NOT EXISTS source text NOT NULL DEFAULT 'byok'
    CHECK (source IN ('byok', 'managed')),
  ADD COLUMN IF NOT EXISTS status text NOT NULL DEFAULT 'active'
    CHECK (status IN ('active', 'disabled')),
  ADD COLUMN IF NOT EXISTS last_tested_at timestamptz,
  ADD COLUMN IF NOT EXISTS last_test_ok boolean,
  ADD COLUMN IF NOT EXISTS last_test_error text,
  ADD COLUMN IF NOT EXISTS last_test_latency_ms integer;

-- Mina-managed keys have no owning user; only the service (RLS-exempt) reaches them.
ALTER TABLE public.provider_keys ALTER COLUMN user_id DROP NOT NULL;

-- is_active (Phase 2) folds into status.
UPDATE public.provider_keys
   SET status = CASE WHEN is_active THEN 'active' ELSE 'disabled' END
 WHERE status = 'active' AND is_active = false;
ALTER TABLE public.provider_keys DROP COLUMN IF EXISTS is_active;

CREATE TABLE IF NOT EXISTS public.user_ai_settings (
  user_id uuid PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE,
  policy text NOT NULL DEFAULT 'byok_only' CHECK (policy IN ('byok_only','byok_first','managed_only')),
  allow_managed_fallback boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_user_ai_settings_updated BEFORE UPDATE ON public.user_ai_settings
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TABLE IF NOT EXISTS public.provider_health (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES public.profiles(id) ON DELETE CASCADE,
  provider text NOT NULL,
  ok boolean NOT NULL,
  latency_ms integer,
  detail text,
  checked_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_provider_health_provider_checked
  ON public.provider_health(provider, checked_at DESC);
CREATE TRIGGER trg_provider_health_checked BEFORE UPDATE ON public.provider_health
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- RLS on the new tenant tables.
ALTER TABLE public.user_ai_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.provider_health ENABLE ROW LEVEL SECURITY;
CREATE POLICY "ai settings are the owner's" ON public.user_ai_settings
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
CREATE POLICY "health rows are the owner's" ON public.provider_health
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

-- Managed key rows (user_id IS NULL) are invisible to authenticated clients:
-- RLS leaves them out, and they are written only by the service role.
