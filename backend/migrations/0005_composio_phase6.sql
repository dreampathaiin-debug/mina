-- Phase 6: production-grade Composio integration.
-- Execution log: metadata only (argument fingerprint and key names),
-- never argument values or tokens. Supabase/PostgreSQL authoritative
-- schema; SQLAlchemy models mirror it for tests.

CREATE TABLE IF NOT EXISTS composio_executions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    tool_slug VARCHAR(160) NOT NULL,
    connected_account_id VARCHAR(160),
    args_fingerprint VARCHAR(64) NOT NULL,
    argument_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(20) NOT NULL DEFAULT 'running',
    successful BOOLEAN,
    result_excerpt TEXT,
    result_truncated BOOLEAN NOT NULL DEFAULT FALSE,
    error TEXT,
    latency_ms INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_composio_executions_user_created
    ON composio_executions (user_id, created_at DESC);

-- Users may read and write only their own execution log.
ALTER TABLE composio_executions ENABLE ROW LEVEL SECURITY;

CREATE POLICY composio_executions_user_isolation ON composio_executions
    FOR ALL TO authenticated
    USING (user_id = auth.uid())
    WITH CHECK (user_id = auth.uid());
