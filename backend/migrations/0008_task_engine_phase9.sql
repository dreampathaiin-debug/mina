-- Phase 9: durable background task engine.
-- durable_tasks: worker-claimed, lease-based, checkpointed tasks.
-- task_events: append-only progress events, (task_id, seq) unique for
-- SSE reconnection via event ids.

CREATE TABLE IF NOT EXISTS durable_tasks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    kind text NOT NULL DEFAULT 'legacy',
    title text NOT NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    status text NOT NULL DEFAULT 'QUEUED',
    idempotency_key text,
    attempts integer NOT NULL DEFAULT 0,
    max_attempts integer NOT NULL DEFAULT 3,
    scheduled_at timestamptz,
    run_after timestamptz,
    lease_owner text,
    lease_expires_at timestamptz,
    checkpoint jsonb,
    progress jsonb,
    result text,
    error text,
    cancel_requested boolean NOT NULL DEFAULT false,
    pause_requested boolean NOT NULL DEFAULT false,
    requires_approval boolean NOT NULL DEFAULT false,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_durable_tasks_status_run_after
    ON durable_tasks (status, run_after);
CREATE INDEX IF NOT EXISTS ix_durable_tasks_user_status
    ON durable_tasks (user_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS uq_durable_tasks_idempotency
    ON durable_tasks (user_id, idempotency_key);

CREATE TABLE IF NOT EXISTS task_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id uuid NOT NULL REFERENCES durable_tasks(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    seq integer NOT NULL,
    type text NOT NULL,
    data jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_task_events_task_seq
    ON task_events (task_id, seq);
CREATE INDEX IF NOT EXISTS ix_task_events_user ON task_events (user_id);

-- Tenant isolation: a user sees only their own durable tasks/events.
ALTER TABLE durable_tasks ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS durable_tasks_user_isolation ON durable_tasks;
CREATE POLICY durable_tasks_user_isolation ON durable_tasks
    FOR ALL USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);

ALTER TABLE task_events ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS task_events_user_isolation ON task_events;
CREATE POLICY task_events_user_isolation ON task_events
    FOR ALL USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
