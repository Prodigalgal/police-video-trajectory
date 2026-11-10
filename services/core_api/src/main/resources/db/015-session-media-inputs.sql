-- Application version: 0.33.0. Session inputs preserve native provenance and existing archive v1.
ALTER TABLE media_production_jobs ADD COLUMN IF NOT EXISTS input_kind text NOT NULL DEFAULT 'archive';
ALTER TABLE media_production_jobs ADD COLUMN IF NOT EXISTS input_descriptor jsonb;
ALTER TABLE media_production_jobs DROP CONSTRAINT IF EXISTS media_production_jobs_profile_version_check;
ALTER TABLE media_production_jobs ADD CONSTRAINT media_production_jobs_profile_version_check CHECK(profile_version IN ('archive-h264-10fps-v1','session-h264-10fps-v1'));
ALTER TABLE media_production_jobs DROP CONSTRAINT IF EXISTS media_production_jobs_input_kind_check;
ALTER TABLE media_production_jobs ADD CONSTRAINT media_production_jobs_input_kind_check CHECK(
    (input_kind='archive' AND input_descriptor IS NULL AND profile_version='archive-h264-10fps-v1') OR
    (input_kind='session' AND input_descriptor IS NOT NULL AND jsonb_typeof(input_descriptor)='object' AND profile_version='session-h264-10fps-v1' AND start_ms=0)
);
CREATE TABLE IF NOT EXISTS media_job_frames (
    job_id uuid NOT NULL REFERENCES media_production_jobs(job_id),
    frame_id uuid NOT NULL REFERENCES capture_outbox(frame_id),
    recording_manifest_sha256 text NOT NULL CHECK(recording_manifest_sha256 ~ '^[a-f0-9]{64}$'),
    payload_sha256 text NOT NULL CHECK(payload_sha256 ~ '^[a-f0-9]{64}$'),
    source_pts_us bigint NOT NULL,
    packet_sha256 text NOT NULL CHECK(packet_sha256 ~ '^[a-f0-9]{64}$'),
    seek_offset_ms bigint NOT NULL CHECK(seek_offset_ms>=0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(job_id,frame_id)
);
CREATE INDEX IF NOT EXISTS media_job_frames_frame_idx ON media_job_frames(frame_id,job_id);
