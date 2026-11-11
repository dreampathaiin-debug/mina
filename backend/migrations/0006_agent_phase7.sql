-- Phase 7: agent orchestrator persistence.

CREATE TABLE IF NOT EXISTS agent_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    conversation_id UUID REFERENCES conversations(id) ON DELETE SET NULL,
    mode VARCHAR(20) NOT NULL DEFAULT 'ask' CHECK (mode IN ('ask', 'autonomous')),
    status VARCHAR(30) NOT NULL DEFAULT 'running'
        CHECK (status IN ('running', 'waiting_approval', 'waiting_device', 'paused', 'cancelled', 'completed', 'failed')),
    input_text TEXT NOT NULL,
    goal TEXT,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    final_response TEXT,
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_agent_runs_user_created ON agent_runs (user_id, created_at);

CREATE TABLE IF NOT EXISTS agent_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    seq INTEGER NOT NULL,
    type VARCHAR(40) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, seq)
);

ALTER TABLE agent_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_events ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS agent_runs_user_select ON agent_runs;
CREATE POLICY agent_runs_user_select ON agent_runs
    FOR SELECT USING (auth.uid() = user_id);
DROP POLICY IF EXISTS agent_runs_user_write ON agent_runs;
CREATE POLICY agent_runs_user_write ON agent_runs
    FOR INSERT WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS agent_runs_user_update ON agent_runs;
CREATE POLICY agent_runs_user_update ON agent_runs
    FOR UPDATE USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS agent_runs_user_delete ON agent_runs;
CREATE POLICY agent_runs_user_delete ON agent_runs
    FOR DELETE USING (auth.uid() = user_id);

DROP POLICY IF EXISTS agent_events_user_select ON agent_events;
CREATE POLICY agent_events_user_select ON agent_events
    FOR SELECT USING (auth.uid() = user_id);
DROP POLICY IF EXISTS agent_events_user_write ON agent_events;
CREATE POLICY agent_events_user_write ON agent_events
    FOR INSERT WITH CHECK (auth.uid() = user_id);
