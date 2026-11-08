-- Additive recovery ledger. No recordings, observations or prior attempts are removed.
CREATE TABLE IF NOT EXISTS capture_faults (
    frame_id uuid PRIMARY KEY REFERENCES capture_outbox(frame_id),
    error_code text NOT NULL CHECK(error_code IN ('evidence_missing','evidence_invalid','evidence_unreadable')),
    revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
    detected_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    restored_at timestamptz
);
CREATE TABLE IF NOT EXISTS pipeline_recovery_requests (
    request_id uuid PRIMARY KEY,
    operation text NOT NULL CHECK(operation IN ('retry_analysis','restore_capture')),
    target_id uuid NOT NULL,
    expected_revision bigint NOT NULL,
    actor_id text NOT NULL,
    reason text NOT NULL,
    prior_state jsonb NOT NULL,
    receipt jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS capture_faults_open_idx ON capture_faults(detected_at,frame_id) WHERE restored_at IS NULL;
