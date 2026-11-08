-- PostgreSQL is the source of truth. Apply through a versioned migration in
-- deployments; SQL initialization is deliberately disabled at application boot.
CREATE TABLE IF NOT EXISTS cameras (
    camera_id text PRIMARY KEY CHECK (length(camera_id) BETWEEN 1 AND 100),
    display_name text NOT NULL CHECK (length(display_name) BETWEEN 1 AND 200),
    location_key text,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS camera_aliases (
    alias text NOT NULL CHECK (length(alias) BETWEEN 1 AND 200),
    camera_id text NOT NULL REFERENCES cameras(camera_id),
    PRIMARY KEY (alias, camera_id)
);

CREATE TABLE IF NOT EXISTS sources (
    source_id uuid PRIMARY KEY,
    camera_id text NOT NULL REFERENCES cameras(camera_id),
    source_type text NOT NULL CHECK (source_type IN ('rtsp', 'archive')),
    -- This is a secret reference or storage reference, never an embedded password.
    source_ref text NOT NULL,
    source_time_origin text NOT NULL CHECK (source_time_origin IN ('verified', 'unknown', 'simulated')),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS sources_camera_ref_uidx ON sources(camera_id, source_ref);

CREATE TABLE IF NOT EXISTS processing_tasks (
    task_id uuid PRIMARY KEY,
    source_id uuid NOT NULL REFERENCES sources(source_id),
    task_type text NOT NULL CHECK (task_type IN ('rtsp', 'archive')),
    state text NOT NULL CHECK (state IN ('queued', 'active', 'completed', 'failed', 'disabled')),
    lease_owner text,
    lease_generation bigint NOT NULL DEFAULT 0 CHECK (lease_generation >= 0),
    lease_expires_at timestamptz,
    last_error_code text,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS processing_tasks_lease_idx ON processing_tasks(state, lease_expires_at);

CREATE TABLE IF NOT EXISTS frames (
    frame_id uuid PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES processing_tasks(task_id),
    lease_generation bigint NOT NULL,
    evidence_ref text NOT NULL,
    media_offset_ms bigint CHECK (media_offset_ms >= 0),
    source_time timestamptz,
    source_time_origin text NOT NULL CHECK (source_time_origin IN ('verified', 'unknown', 'simulated')),
    received_at timestamptz NOT NULL,
    analysis_state text NOT NULL CHECK (analysis_state IN ('queued', 'completed', 'failed')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (source_time_origin = 'verified' OR source_time IS NULL)
);

-- PostgreSQL-backed durable work queue for the single-node MVP. A frame is
-- acknowledged only after the file reference and job are committed together.
CREATE TABLE IF NOT EXISTS analysis_jobs (
    job_id uuid PRIMARY KEY,
    frame_id uuid NOT NULL UNIQUE REFERENCES frames(frame_id),
    state text NOT NULL CHECK (state IN ('queued', 'active', 'completed', 'failed')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    lease_owner text,
    lease_generation bigint NOT NULL DEFAULT 0 CHECK (lease_generation >= 0),
    lease_expires_at timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error_code text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS analysis_jobs_pending_idx ON analysis_jobs(state, next_attempt_at, lease_expires_at);

CREATE TABLE IF NOT EXISTS observations (
    observation_id uuid PRIMARY KEY,
    frame_id uuid NOT NULL REFERENCES frames(frame_id),
    object_ordinal smallint NOT NULL CHECK (object_ordinal >= 0),
    camera_id text NOT NULL REFERENCES cameras(camera_id),
    target_type text NOT NULL CHECK (target_type IN ('person', 'vehicle')),
    attributes jsonb NOT NULL DEFAULT '{}'::jsonb,
    bbox_xyxy jsonb,
    uncertain_fields jsonb NOT NULL DEFAULT '[]'::jsonb,
    description text,
    model_id text NOT NULL,
    prompt_version text NOT NULL,
    record_version bigint NOT NULL DEFAULT 1 CHECK (record_version > 0),
    deleted boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (frame_id, object_ordinal)
);

CREATE TABLE IF NOT EXISTS index_outbox (
    event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    observation_id uuid NOT NULL REFERENCES observations(observation_id),
    record_version bigint NOT NULL CHECK (record_version > 0),
    -- Deletions are indexed as persistent tombstone documents, not hard deletes.
    deleted boolean NOT NULL,
    document jsonb NOT NULL,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (observation_id, record_version)
);
CREATE INDEX IF NOT EXISTS index_outbox_pending_idx ON index_outbox(next_attempt_at, event_id) WHERE completed_at IS NULL;

CREATE TABLE IF NOT EXISTS audit_events (
    audit_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id text NOT NULL,
    action text NOT NULL,
    result text NOT NULL,
    request_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now()
);
