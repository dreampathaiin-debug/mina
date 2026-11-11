-- Phase 8: secure long-term memory.
-- Extends memories with confidence, source, status, dedup/correction
-- links, expiry, access tracking and embeddings (pgvector on Postgres).

ALTER TABLE memories
    ADD COLUMN IF NOT EXISTS confidence real NOT NULL DEFAULT 0.5,
    ADD COLUMN IF NOT EXISTS source text NOT NULL DEFAULT 'user_stated',
    ADD COLUMN IF NOT EXISTS source_ref jsonb,
    ADD COLUMN IF NOT EXISTS status text NOT NULL DEFAULT 'active',
    ADD COLUMN IF NOT EXISTS superseded_by uuid REFERENCES memories(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS user_approved boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS expires_at timestamptz,
    ADD COLUMN IF NOT EXISTS last_accessed_at timestamptz,
    ADD COLUMN IF NOT EXISTS embedding jsonb;

CREATE INDEX IF NOT EXISTS ix_memories_user_status ON memories (user_id, status);
CREATE INDEX IF NOT EXISTS ix_memories_kind ON memories (user_id, kind);

DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS vector;
    ALTER TABLE memories ADD COLUMN IF NOT EXISTS embedding_vec vector(384);
    CREATE INDEX IF NOT EXISTS ix_memories_embedding_vec
        ON memories USING ivfflat (embedding_vec vector_cosine_ops) WITH (lists = 100);
EXCEPTION
    WHEN OTHERS THEN
        RAISE NOTICE 'pgvector unavailable, JSON embedding in use: %', SQLERRM;
END $$;

-- Tenant isolation stays row-level: users see only their own memories.
DROP POLICY IF EXISTS memories_phase8_select ON memories;
CREATE POLICY memories_phase8_select ON memories
    FOR SELECT USING (auth.uid() = user_id AND status <> 'deleted');
DROP POLICY IF EXISTS memories_phase8_write ON memories;
CREATE POLICY memories_phase8_write ON memories
    FOR ALL USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
