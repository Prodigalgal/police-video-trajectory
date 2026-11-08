-- Application version: 0.21.7. Additive media registry; no observations or inference jobs.
CREATE TABLE IF NOT EXISTS recorded_media_clips (
    clip_id uuid PRIMARY KEY,
    source_id uuid NOT NULL REFERENCES sources(source_id),
    camera_id text NOT NULL REFERENCES cameras(camera_id),
    source_fingerprint text NOT NULL CHECK(source_fingerprint ~ '^[a-f0-9]{64}$'),
    manifest jsonb NOT NULL CHECK(jsonb_typeof(manifest)='object'),
    manifest_sha256 text NOT NULL CHECK(manifest_sha256 ~ '^[a-f0-9]{64}$'),
    registered_by text NOT NULL CHECK(length(registered_by) BETWEEN 1 AND 100),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS recorded_media_clips_camera_idx ON recorded_media_clips(camera_id,clip_id);
