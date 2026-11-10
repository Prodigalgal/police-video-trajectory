-- Application version: 0.29.0. Frozen routes and auditable selective admissions.
ALTER TABLE specialist_analysis_jobs ADD COLUMN IF NOT EXISTS routing_snapshot jsonb;
ALTER TABLE specialist_analysis_jobs ADD COLUMN IF NOT EXISTS routing_sha256 text;
ALTER TABLE specialist_analysis_jobs ADD COLUMN IF NOT EXISTS selection_priority smallint NOT NULL DEFAULT 0 CHECK(selection_priority BETWEEN 0 AND 2);
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conname='specialist_routing_snapshot_check' AND conrelid='specialist_analysis_jobs'::regclass) THEN
  ALTER TABLE specialist_analysis_jobs ADD CONSTRAINT specialist_routing_snapshot_check CHECK(
   (routing_snapshot IS NULL AND routing_sha256 IS NULL) OR
   (routing_snapshot IS NOT NULL AND jsonb_typeof(routing_snapshot)='object' AND routing_sha256 ~ '^[a-f0-9]{64}$'));
 END IF;
END $$;
CREATE TABLE IF NOT EXISTS processing_model_profiles (
 profile text PRIMARY KEY CHECK(profile ~ '^[A-Za-z0-9_.-]{1,80}$'),
 definition jsonb NOT NULL CHECK(jsonb_typeof(definition)='object'),
 definition_sha256 text NOT NULL CHECK(definition_sha256 ~ '^[a-f0-9]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS specialist_analysis_admissions (
 request_id uuid PRIMARY KEY REFERENCES specialist_analysis_requests(request_id),
 job_id uuid NOT NULL REFERENCES specialist_analysis_jobs(job_id),
 frame_id uuid NOT NULL REFERENCES frames(frame_id),camera_id text NOT NULL REFERENCES cameras(camera_id),
 action text NOT NULL CHECK(action IN ('create','retry')),
 policy_sha256 text NOT NULL CHECK(policy_sha256 ~ '^[a-f0-9]{64}$'),
 selection_sha256 text NOT NULL CHECK(selection_sha256 ~ '^[a-f0-9]{64}$'),
 decision jsonb NOT NULL CHECK(jsonb_typeof(decision)='object'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS specialist_admission_camera_idx ON specialist_analysis_admissions(camera_id,created_at);
CREATE INDEX IF NOT EXISTS specialist_admission_event_idx ON specialist_analysis_admissions((decision->>'event_id'),created_at);
CREATE TABLE IF NOT EXISTS specialist_plate_reviews (
 job_id uuid PRIMARY KEY REFERENCES specialist_analysis_jobs(job_id),event_id uuid NOT NULL,
 frame_id uuid NOT NULL REFERENCES frames(frame_id),camera_id text NOT NULL REFERENCES cameras(camera_id),
 result_sha256 text NOT NULL CHECK(result_sha256 ~ '^[a-f0-9]{64}$'),region_index integer NOT NULL CHECK(region_index>=0),
 review_state text NOT NULL CHECK(review_state IN ('confirmed','revoked')),revision bigint NOT NULL CHECK(revision>0),
 actor_id text NOT NULL,updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS specialist_plate_review_event_idx ON specialist_plate_reviews(event_id,updated_at);
CREATE TABLE IF NOT EXISTS specialist_plate_review_requests (
 request_id uuid PRIMARY KEY,job_id uuid NOT NULL REFERENCES specialist_analysis_jobs(job_id),actor_id text NOT NULL,
 request_sha256 text NOT NULL CHECK(request_sha256 ~ '^[a-f0-9]{64}$'),response jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
