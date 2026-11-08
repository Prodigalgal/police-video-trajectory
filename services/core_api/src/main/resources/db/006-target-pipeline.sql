-- Additive migration: old frames, leases and queued jobs remain valid.
CREATE TABLE IF NOT EXISTS processing_controls (
    control_key text PRIMARY KEY,
    enabled boolean NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO processing_controls(control_key,enabled) VALUES('analysis_claims',true) ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS capture_outbox (
    frame_id uuid PRIMARY KEY,
    receipt uuid NOT NULL UNIQUE,
    task_id uuid NOT NULL REFERENCES processing_tasks(task_id),
    worker_id text NOT NULL,
    lease_generation bigint NOT NULL CHECK(lease_generation>0),
    evidence_ref text NOT NULL,
    payload_sha256 text NOT NULL CHECK(payload_sha256 ~ '^[0-9a-f]{64}$'),
    camera_id text NOT NULL REFERENCES cameras(camera_id),
    source_type text NOT NULL CHECK(source_type IN ('rtsp','archive')),
    source_time_origin text NOT NULL CHECK(source_time_origin IN ('verified','unknown','simulated')),
    media_offset_ms bigint CHECK(media_offset_ms>=0),
    source_time timestamptz,
    scene_run_id uuid NOT NULL,
    media_epoch bigint NOT NULL CHECK(media_epoch>=0),
    relative_media_ms bigint NOT NULL CHECK(relative_media_ms>=0),
    plan jsonb NOT NULL,
    registered_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    enqueued_at timestamptz,
    CHECK(source_time_origin='verified' OR source_time IS NULL)
);
CREATE INDEX IF NOT EXISTS capture_outbox_waiting_idx ON capture_outbox(registered_at) WHERE enqueued_at IS NULL;
CREATE TABLE IF NOT EXISTS detected_targets (
    target_id uuid PRIMARY KEY,
    frame_id uuid NOT NULL REFERENCES capture_outbox(frame_id),
    target_ordinal smallint NOT NULL CHECK(target_ordinal>=0),
    object_type text NOT NULL CHECK(object_type IN ('person','vehicle')),
    class_name text NOT NULL CHECK(class_name IN ('person','bicycle','car','motorcycle','bus','truck')),
    confidence double precision NOT NULL CHECK(confidence>=0 AND confidence<=1),
    bbox_xyxy jsonb NOT NULL,
    bbox_normalized jsonb NOT NULL,
    track_id uuid NOT NULL,
    association text NOT NULL CHECK(association='local_geometry_candidate'),
    observation_ordinal smallint,
    UNIQUE(frame_id,target_ordinal),
    UNIQUE(frame_id,observation_ordinal)
);
CREATE INDEX IF NOT EXISTS detected_targets_track_idx ON detected_targets(track_id);
CREATE TABLE IF NOT EXISTS analysis_modules (
    frame_id uuid NOT NULL REFERENCES capture_outbox(frame_id),
    module_key text NOT NULL CHECK(module_key IN ('person_attributes','vehicle_attributes','scene_context')),
    target_ids jsonb NOT NULL,
    result jsonb,
    completed_at timestamptz,
    PRIMARY KEY(frame_id,module_key)
);
