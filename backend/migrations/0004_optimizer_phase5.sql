-- Phase 5: context and token optimizer.
-- Optimizer metrics events + persistent semantic response cache.
-- Supabase/PostgreSQL authoritative schema; SQLAlchemy models mirror it for tests.

-- Best-effort pgvector: enables index-backed KNN when the extension is
-- available; the cache falls back to scope-partitioned cosine in Python.
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS vector;
EXCEPTION
    WHEN OTHERS THEN
        RAISE NOTICE 'pgvector unavailable; semantic cache falls back to JSON embeddings';
END
$$;

CREATE TABLE IF NOT EXISTS optimizer_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    model_id VARCHAR(120) NOT NULL,
    task_type VARCHAR(60) NOT NULL,
    tokens_before INTEGER NOT NULL,
    tokens_after INTEGER NOT NULL,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    cache_hit VARCHAR(16),
    cache_saved_tokens INTEGER NOT NULL DEFAULT 0,
    est_savings_usd INTEGER NOT NULL DEFAULT 0,
    latency_ms INTEGER NOT NULL DEFAULT 0,
    quality_failure BOOLEAN NOT NULL DEFAULT FALSE,
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_optimizer_events_user ON optimizer_events(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS semantic_cache_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope_hash VARCHAR(200) NOT NULL,
    embedding JSONB NOT NULL,
    response JSONB NOT NULL,
    prompt_preview TEXT NOT NULL,
    input_tokens INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_semantic_cache_scope ON semantic_cache_entries(scope_hash, created_at DESC);

ALTER TABLE optimizer_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE semantic_cache_entries ENABLE ROW LEVEL SECURITY;

CREATE POLICY optimizer_events_owner ON optimizer_events
    FOR ALL USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);

CREATE POLICY semantic_cache_owner ON semantic_cache_entries
    FOR ALL USING (scope_hash LIKE auth.uid()::text || '|%')
    WITH CHECK (scope_hash LIKE auth.uid()::text || '|%');
