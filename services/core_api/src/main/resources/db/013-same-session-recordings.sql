-- Application version: 0.21.0. Additive recording enrichment; no inference or index work.
CREATE TABLE IF NOT EXISTS stream_recording_segments (
    segment_id uuid PRIMARY KEY,
    storage_namespace text NOT NULL CHECK(storage_namespace ~ '^[a-f0-9]{32}$'),
    task_id uuid NOT NULL REFERENCES processing_tasks(task_id),
    camera_id text NOT NULL REFERENCES cameras(camera_id),
    scene_run_id uuid NOT NULL,
    media_epoch bigint NOT NULL CHECK(media_epoch>=0),
    manifest jsonb NOT NULL CHECK(jsonb_typeof(manifest)='object'),
    manifest_sha256 text NOT NULL CHECK(manifest_sha256 ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS frame_recording_links (
    frame_id uuid PRIMARY KEY REFERENCES capture_outbox(frame_id),
    segment_id uuid NOT NULL REFERENCES stream_recording_segments(segment_id),
    payload_sha256 text NOT NULL CHECK(payload_sha256 ~ '^[a-f0-9]{64}$'),
    source_pts_us bigint NOT NULL,
    packet_sha256 text NOT NULL CHECK(packet_sha256 ~ '^[a-f0-9]{64}$'),
    seek_offset_ms bigint NOT NULL CHECK(seek_offset_ms>=0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS frame_recording_links_segment_idx ON frame_recording_links(segment_id,frame_id);
CREATE INDEX IF NOT EXISTS stream_recording_segments_session_idx ON stream_recording_segments(task_id,scene_run_id,media_epoch,segment_id);
