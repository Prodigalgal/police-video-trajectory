-- Application version: 0.28.0. Additive: preserve all existing results and leases.
CREATE TABLE IF NOT EXISTS analysis_result_sources (
    source_id uuid PRIMARY KEY,
    frame_id uuid NOT NULL UNIQUE REFERENCES capture_outbox(frame_id),
    model_id text NOT NULL,
    prompt_version text NOT NULL,
    source_payload jsonb NOT NULL CHECK(jsonb_typeof(source_payload)='object'),
    source_sha256 text NOT NULL CHECK(source_sha256 ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(source_id,frame_id)
);
CREATE TABLE IF NOT EXISTS analysis_module_jobs (
    job_id uuid PRIMARY KEY,
    frame_id uuid NOT NULL,
    module_key text NOT NULL CHECK(module_key IN ('person_attributes','vehicle_attributes','scene_context')),
    source_id uuid NOT NULL,
    state text NOT NULL CHECK(state IN ('queued','active','completed','failed')),
    attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
    lease_owner text,
    lease_generation bigint NOT NULL DEFAULT 0 CHECK(lease_generation>=0),
    lease_expires_at timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_error_code text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(frame_id,module_key),
    FOREIGN KEY(frame_id,module_key) REFERENCES analysis_modules(frame_id,module_key),
    FOREIGN KEY(source_id,frame_id) REFERENCES analysis_result_sources(source_id,frame_id),
    CHECK(state<>'active' OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL AND lease_generation>0))
);
CREATE INDEX IF NOT EXISTS analysis_module_jobs_pending_idx
    ON analysis_module_jobs(state,next_attempt_at,created_at,job_id) WHERE state IN ('queued','active');
CREATE INDEX IF NOT EXISTS analysis_module_jobs_quota_idx
    ON analysis_module_jobs(module_key,lease_expires_at) WHERE state='active';
CREATE TABLE IF NOT EXISTS analysis_module_recoveries (
    request_id uuid PRIMARY KEY,
    job_id uuid NOT NULL REFERENCES analysis_module_jobs(job_id),
    expected_revision bigint NOT NULL,
    actor_id text NOT NULL,
    reason text NOT NULL,
    receipt jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
