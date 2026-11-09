-- Application version: 0.26.0. Candidate submission ledger; no inference or semantic writes.
CREATE TABLE IF NOT EXISTS specialist_candidates (
 candidate_id uuid PRIMARY KEY,frame_id uuid NOT NULL UNIQUE REFERENCES capture_outbox(frame_id),
 camera_id text NOT NULL REFERENCES cameras(camera_id),target_id uuid REFERENCES detected_targets(target_id),
 evidence_sha256 text NOT NULL CHECK(evidence_sha256 ~ '^[a-f0-9]{64}$'),
 selection_sha256 text NOT NULL CHECK(selection_sha256 ~ '^[a-f0-9]{64}$'),
 policy_sha256 text CHECK(policy_sha256 IS NULL OR policy_sha256 ~ '^[a-f0-9]{64}$'),
 state text NOT NULL CHECK(state IN ('ready','active','waiting_source','waiting_configuration','waiting_budget','waiting_result','waiting_review','submitted','reused','skipped','invalid','expired','failed')),
 reason text NOT NULL CHECK(reason ~ '^[a-z_]{1,80}$'),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),failures integer NOT NULL DEFAULT 0 CHECK(failures>=0),
 lease_owner text,lease_generation bigint NOT NULL DEFAULT 0 CHECK(lease_generation>=0),lease_expires_at timestamptz,
 next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),expires_at timestamptz NOT NULL,
 anchor_observation_id uuid REFERENCES observations(observation_id),anchor_record_version bigint,
 input_id uuid REFERENCES analysis_inputs(input_id),job_id uuid REFERENCES specialist_analysis_jobs(job_id),
 reference_job_id uuid REFERENCES specialist_analysis_jobs(job_id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK((state='active')=(lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)),
 CHECK((state='submitted')=(job_id IS NOT NULL)),CHECK((state='reused')=(reference_job_id IS NOT NULL)),
 CHECK((anchor_observation_id IS NULL)=(anchor_record_version IS NULL)),
 CHECK(anchor_record_version IS NULL OR anchor_record_version>0),CHECK(input_id IS NULL OR anchor_observation_id IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS specialist_candidates_claim_idx ON specialist_candidates(state,next_attempt_at,created_at,candidate_id);
CREATE INDEX IF NOT EXISTS specialist_candidates_camera_idx ON specialist_candidates(camera_id,candidate_id);
CREATE INDEX IF NOT EXISTS capture_specialist_candidates_idx ON capture_outbox(registered_at,frame_id)
 WHERE plan#>>'{selection_metrics,specialist_selection,schema_version}'='specialist-selection-v2'
 AND plan#>>'{selection_metrics,specialist_selection,state}'='complete'
 AND plan#>>'{selection_metrics,specialist_selection,mode}'='select'
 AND plan#>'{selection_metrics,specialist_selection,modules}' @> '[{"module":"license_plate","decision":"selected"}]'::jsonb;
CREATE TABLE IF NOT EXISTS specialist_candidate_recoveries (
 request_id uuid PRIMARY KEY,candidate_id uuid NOT NULL REFERENCES specialist_candidates(candidate_id),
 actor_id text NOT NULL,request_sha256 text NOT NULL CHECK(request_sha256 ~ '^[a-f0-9]{64}$'),
 response jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
