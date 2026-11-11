-- Phase 4: user-configurable model preferences.
ALTER TABLE public.user_ai_settings
    ADD COLUMN IF NOT EXISTS preferred_model text;
ALTER TABLE public.user_ai_settings
    ADD COLUMN IF NOT EXISTS preferred_group text
    CHECK (preferred_group IS NULL OR preferred_group IN ('fast','cheap','balanced','powerful'));
