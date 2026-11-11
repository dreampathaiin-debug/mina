-- Phase 10: observability and usage analytics.
-- usage_events (metadata only: no prompt text, no key material),
-- usage_budgets (per-user limits), budget_alerts (pre-exhaustion alerts).

CREATE TABLE IF NOT EXISTS usage_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind VARCHAR(20) NOT NULL,
    user_id UUID REFERENCES profiles(id) ON DELETE CASCADE,
    request_id VARCHAR(64),
    conversation_id UUID,
    task_id UUID,
    provider VARCHAR(50),
    model VARCHAR(80),
    selection_reason VARCHAR(80),
    input_tokens INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    estimated_cost_usd DOUBLE PRECISION,
    cost_basis VARCHAR(20) NOT NULL DEFAULT 'estimate',
    billing_source VARCHAR(20),
    cache_hit VARCHAR(20),
    latency_ms INTEGER,
    retry_count INTEGER NOT NULL DEFAULT 0,
    fallback_count INTEGER NOT NULL DEFAULT 0,
    tool_slug VARCHAR(120),
    tool_duration_ms INTEGER,
    success BOOLEAN,
    error_category VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_usage_events_user_created ON usage_events (user_id, created_at);
CREATE INDEX IF NOT EXISTS ix_usage_events_kind_created ON usage_events (kind, created_at);
CREATE INDEX IF NOT EXISTS ix_usage_events_provider_created ON usage_events (provider, created_at);

CREATE TABLE IF NOT EXISTS usage_budgets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES profiles(id) ON DELETE CASCADE,
    daily_budget_usd DOUBLE PRECISION,
    monthly_budget_usd DOUBLE PRECISION,
    daily_token_limit INTEGER,
    monthly_token_limit INTEGER,
    concurrent_request_limit INTEGER,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_usage_budgets_user ON usage_budgets (user_id);

CREATE TABLE IF NOT EXISTS budget_alerts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    scope VARCHAR(60) NOT NULL,
    threshold_pct INTEGER NOT NULL,
    alert_day DATE NOT NULL,
    message VARCHAR(300) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_budget_alerts_dedupe UNIQUE (user_id, scope, threshold_pct, alert_day)
);

CREATE INDEX IF NOT EXISTS ix_budget_alerts_user ON budget_alerts (user_id, created_at);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_tables WHERE tablename = 'usage_events') THEN
        EXECUTE 'ALTER TABLE usage_events ENABLE ROW LEVEL SECURITY';
        DROP POLICY IF EXISTS usage_events_user_select ON usage_events;
        CREATE POLICY usage_events_user_select ON usage_events
            FOR SELECT USING (user_id = auth.uid());
        DROP POLICY IF EXISTS usage_events_user_write ON usage_events;
        CREATE POLICY usage_events_user_write ON usage_events
            FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
        DROP POLICY IF EXISTS usage_events_service ON usage_events;
        CREATE POLICY usage_events_service ON usage_events
            FOR ALL USING (auth.role() = 'service_role');
    END IF;
END $$;
