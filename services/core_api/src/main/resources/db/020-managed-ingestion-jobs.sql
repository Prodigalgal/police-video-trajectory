-- Application version: 0.31.0. Managed source tasks; inference remains in existing queues.
CREATE TABLE IF NOT EXISTS ingestion_batches (
 batch_id uuid PRIMARY KEY,request_id uuid NOT NULL UNIQUE,actor_id text NOT NULL,
 request_sha256 text NOT NULL CHECK(request_sha256 ~ '^[a-f0-9]{64}$'),
 title text NOT NULL CHECK(length(title) BETWEEN 1 AND 160),created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS ingestion_task_specs (
 task_id uuid PRIMARY KEY REFERENCES processing_tasks(task_id),batch_id uuid NOT NULL REFERENCES ingestion_batches(batch_id),
 input_contract text NOT NULL CHECK(input_contract='managed-ingestion-v1'),input_kind text NOT NULL CHECK(input_kind IN ('archive-v1','simulator-v1')),
 source_snapshot jsonb NOT NULL,input_sha256 text NOT NULL CHECK(input_sha256 ~ '^[a-f0-9]{64}$'),
 start_ms bigint NOT NULL DEFAULT 0 CHECK(start_ms>=0),end_ms bigint,
 safe_through_ms bigint NOT NULL DEFAULT -1 CHECK(safe_through_ms>=-1),observed_through_ms bigint NOT NULL DEFAULT -1 CHECK(observed_through_ms>=-1),
 priority smallint NOT NULL DEFAULT 0 CHECK(priority BETWEEN 0 AND 9),paused boolean NOT NULL DEFAULT false,
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),failures integer NOT NULL DEFAULT 0 CHECK(failures>=0),
 next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),reader_finished boolean NOT NULL DEFAULT false,
 last_error_code text,last_progress_at timestamptz,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK(end_ms IS NULL OR end_ms>start_ms),CHECK(safe_through_ms<=observed_through_ms)
);
CREATE INDEX IF NOT EXISTS ingestion_specs_batch_idx ON ingestion_task_specs(batch_id,task_id);
CREATE INDEX IF NOT EXISTS ingestion_specs_dispatch_idx ON ingestion_task_specs(paused,next_attempt_at,priority DESC,task_id);
CREATE TABLE IF NOT EXISTS ingestion_progress_receipts (
 checkpoint_id uuid PRIMARY KEY,task_id uuid NOT NULL REFERENCES ingestion_task_specs(task_id),
 worker_id text NOT NULL,generation bigint NOT NULL,input_sha256 text NOT NULL,
 request_sha256 text NOT NULL CHECK(request_sha256 ~ '^[a-f0-9]{64}$'),response jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS ingestion_receipts_task_idx ON ingestion_progress_receipts(task_id,generation);
CREATE INDEX IF NOT EXISTS capture_task_offset_idx ON capture_outbox(task_id,media_offset_ms,frame_id) WHERE source_type='archive';
