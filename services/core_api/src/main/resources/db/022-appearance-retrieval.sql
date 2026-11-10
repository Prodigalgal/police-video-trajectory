-- Application version: 0.34.0. Additive, separately paused appearance index.
CREATE TABLE IF NOT EXISTS appearance_profiles (
    profile text PRIMARY KEY, definition_sha256 text NOT NULL UNIQUE,
    definition jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS appearance_jobs (
    job_id uuid PRIMARY KEY, observation_id uuid NOT NULL REFERENCES observations(observation_id),
    record_version bigint NOT NULL, frame_id uuid NOT NULL REFERENCES frames(frame_id),
    camera_id text NOT NULL REFERENCES cameras(camera_id), target_type text NOT NULL CHECK(target_type IN ('person','vehicle')),
    profile_sha256 text NOT NULL REFERENCES appearance_profiles(definition_sha256),
    source_sha256 text NOT NULL, descriptor jsonb NOT NULL,
    state text NOT NULL DEFAULT 'queued' CHECK(state IN ('queued','active','completed','failed','invalid')),
    attempts integer NOT NULL DEFAULT 0, lease_owner text, lease_generation bigint NOT NULL DEFAULT 0,
    lease_expires_at timestamptz, next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),last_error_code text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(profile_sha256,source_sha256)
);
CREATE INDEX IF NOT EXISTS appearance_jobs_pending_idx ON appearance_jobs(state,next_attempt_at,lease_expires_at);
CREATE INDEX IF NOT EXISTS appearance_jobs_observation_idx ON appearance_jobs(observation_id,record_version,profile_sha256);
CREATE TABLE IF NOT EXISTS appearance_features (
    job_id uuid PRIMARY KEY REFERENCES appearance_jobs(job_id),vector real[] NOT NULL, vector_sha256 text NOT NULL,
    completed_at timestamptz NOT NULL DEFAULT clock_timestamp(),CHECK(cardinality(vector) BETWEEN 1 AND 1024)
);
CREATE TABLE IF NOT EXISTS appearance_queries (
    query_id uuid PRIMARY KEY,actor_id text NOT NULL,actor_revision bigint NOT NULL,
    request_sha256 text NOT NULL,profile_sha256 text NOT NULL,scope_sha256 text NOT NULL,
    summary jsonb NOT NULL,candidates jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL DEFAULT clock_timestamp()+interval '30 minutes'
);
CREATE INDEX IF NOT EXISTS appearance_queries_expiry_idx ON appearance_queries(expires_at);
CREATE TABLE IF NOT EXISTS appearance_scan_cursors (
    profile_sha256 text PRIMARY KEY REFERENCES appearance_profiles(definition_sha256),last_observation_id uuid,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO processing_controls(control_key,enabled) VALUES('appearance_claims',false),('appearance_production',false) ON CONFLICT DO NOTHING;
