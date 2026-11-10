-- Application version: 0.28.0. Dedicated OCR contracts; no observations or index mutations.
CREATE TABLE IF NOT EXISTS specialist_analysis_jobs (
 job_id uuid PRIMARY KEY,input_id uuid NOT NULL REFERENCES analysis_inputs(input_id),
 input_manifest_sha256 text NOT NULL CHECK(input_manifest_sha256 ~ '^[a-f0-9]{64}$'),
 frame_id uuid NOT NULL REFERENCES frames(frame_id),camera_id text NOT NULL REFERENCES cameras(camera_id),
 operation text NOT NULL CHECK(operation IN ('text_ocr','license_plate')),
 profile text NOT NULL CHECK(profile ~ '^[A-Za-z0-9_.-]{1,80}$'),
 input_sha256 text NOT NULL UNIQUE CHECK(input_sha256 ~ '^[a-f0-9]{64}$'),input_descriptor jsonb NOT NULL CHECK(jsonb_typeof(input_descriptor)='object'),
 state text NOT NULL DEFAULT 'queued' CHECK(state IN ('queued','active','completed','failed')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
 lease_owner text,lease_generation bigint NOT NULL DEFAULT 0 CHECK(lease_generation>=0),lease_expires_at timestamptz,
 next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),last_error_code text,
 result_sha256 text CHECK(result_sha256 ~ '^[a-f0-9]{64}$'),created_by text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK((state='completed')=(result_sha256 IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS specialist_jobs_claim_idx ON specialist_analysis_jobs(state,profile,next_attempt_at,created_at,job_id);
CREATE INDEX IF NOT EXISTS specialist_jobs_input_idx ON specialist_analysis_jobs(input_id,job_id);
CREATE TABLE IF NOT EXISTS specialist_analysis_requests (
 request_id uuid PRIMARY KEY,actor_id text NOT NULL,request_sha256 text NOT NULL CHECK(request_sha256 ~ '^[a-f0-9]{64}$'),
 job_id uuid NOT NULL REFERENCES specialist_analysis_jobs(job_id),created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS specialist_analysis_results (
 job_id uuid PRIMARY KEY REFERENCES specialist_analysis_jobs(job_id),result_revision integer NOT NULL DEFAULT 1 CHECK(result_revision=1),
 result jsonb NOT NULL CHECK(jsonb_typeof(result)='object'),result_sha256 text NOT NULL CHECK(result_sha256 ~ '^[a-f0-9]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
