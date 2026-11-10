-- Application version: 0.29.0. Independent archive media work; no inference or observations.
CREATE TABLE IF NOT EXISTS media_production_jobs (
    job_id uuid PRIMARY KEY,
    source_id uuid NOT NULL REFERENCES sources(source_id),
    camera_id text NOT NULL REFERENCES cameras(camera_id),
    source_fingerprint text NOT NULL CHECK(source_fingerprint ~ '^[a-f0-9]{64}$'),
    source_sha256 text NOT NULL CHECK(source_sha256 ~ '^[a-f0-9]{64}$'),
    source_bytes bigint NOT NULL CHECK(source_bytes>0 AND source_bytes<=8589934592),
    start_ms bigint NOT NULL CHECK(start_ms>=0),
    end_ms bigint NOT NULL CHECK(end_ms-start_ms BETWEEN 2000 AND 30000),
    profile_version text NOT NULL CHECK(profile_version='archive-h264-10fps-v1'),
    input_sha256 text NOT NULL UNIQUE CHECK(input_sha256 ~ '^[a-f0-9]{64}$'),
    state text NOT NULL DEFAULT 'queued' CHECK(state IN ('queued','active','completed','failed')),
    attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
    revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
    lease_owner text,
    lease_generation bigint NOT NULL DEFAULT 0 CHECK(lease_generation>=0),
    lease_expires_at timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_error_code text,
    manifest_sha256 text CHECK(manifest_sha256 ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK((state='completed')=(manifest_sha256 IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS media_production_jobs_claim_idx ON media_production_jobs(state,next_attempt_at,created_at,job_id);
CREATE INDEX IF NOT EXISTS media_production_jobs_camera_idx ON media_production_jobs(camera_id,job_id);
CREATE TABLE IF NOT EXISTS media_production_requests (
    request_id uuid PRIMARY KEY,
    request_sha256 text NOT NULL CHECK(request_sha256 ~ '^[a-f0-9]{64}$'),
    job_id uuid NOT NULL REFERENCES media_production_jobs(job_id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
