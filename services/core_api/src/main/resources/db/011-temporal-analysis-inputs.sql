-- Application version: 0.36.0. Additive; does not enqueue inference or alter existing observations.
CREATE INDEX IF NOT EXISTS capture_outbox_context_idx
    ON capture_outbox(task_id,scene_run_id,media_epoch,relative_media_ms,frame_id);
CREATE TABLE IF NOT EXISTS analysis_inputs (
    input_id uuid PRIMARY KEY,
    anchor_observation_id uuid NOT NULL REFERENCES observations(observation_id),
    anchor_record_version bigint NOT NULL CHECK(anchor_record_version>0),
    anchor_frame_id uuid NOT NULL REFERENCES frames(frame_id),
    camera_id text NOT NULL REFERENCES cameras(camera_id),
    purpose text NOT NULL CHECK(purpose IN ('single_frame','frame_sequence')),
    created_by text NOT NULL CHECK(length(created_by) BETWEEN 1 AND 100),
    manifest jsonb NOT NULL CHECK(jsonb_typeof(manifest)='object'),
    manifest_sha256 text NOT NULL CHECK(manifest_sha256 ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(created_by,manifest_sha256)
);
CREATE INDEX IF NOT EXISTS analysis_inputs_anchor_idx ON analysis_inputs(anchor_observation_id,input_id);
CREATE INDEX IF NOT EXISTS analysis_inputs_camera_idx ON analysis_inputs(camera_id,input_id);
CREATE TABLE IF NOT EXISTS analysis_input_requests (
    request_id uuid PRIMARY KEY,
    actor_id text NOT NULL CHECK(length(actor_id) BETWEEN 1 AND 100),
    request_fingerprint text NOT NULL CHECK(request_fingerprint ~ '^[a-f0-9]{64}$'),
    input_id uuid NOT NULL REFERENCES analysis_inputs(input_id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
