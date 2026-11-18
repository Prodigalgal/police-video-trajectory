-- Application version: 1.2.0. Additive MOT algorithm associations and ADR023 video platform ingress contracts.
-- Existing observations, vectors and sources remain intact; interoperability_verified and clock_state stay false/unverified by default.
DO $$
DECLARE item record;
BEGIN
    FOR item IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'detected_targets'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%association%'
    LOOP
        EXECUTE format('ALTER TABLE detected_targets DROP CONSTRAINT %I', item.conname);
    END LOOP;
END $$;

ALTER TABLE detected_targets
    ADD CONSTRAINT detected_targets_association_check
    CHECK (association IN ('local_geometry_candidate', 'bytetrack_kalman_v1', 'ocsort_momentum_v1'));

CREATE TABLE IF NOT EXISTS video_platforms (
    platform_id text PRIMARY KEY CHECK (platform_id ~ '^[a-z0-9][a-z0-9_-]{0,79}$'),
    adapter_kind text NOT NULL CHECK (adapter_kind IN ('gb28181_cascade', 'platform_stream')),
    display_name text NOT NULL CHECK (length(display_name) BETWEEN 1 AND 200),
    -- Logical endpoint or credential reference name only; never store embedded passwords or raw stream URLs.
    endpoint_ref text NOT NULL CHECK (length(endpoint_ref) BETWEEN 1 AND 200),
    media_node text NOT NULL DEFAULT '' CHECK (length(media_node) <= 200),
    clock_state text NOT NULL DEFAULT 'unverified' CHECK (clock_state IN ('unverified', 'verified')),
    clock_offset_ms bigint,
    interoperability_verified boolean NOT NULL DEFAULT false,
    enabled boolean NOT NULL DEFAULT false,
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_by text NOT NULL DEFAULT 'system',
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (clock_state = 'verified' OR clock_offset_ms IS NULL)
);

CREATE TABLE IF NOT EXISTS platform_camera_channels (
    mapping_id uuid PRIMARY KEY,
    platform_id text NOT NULL REFERENCES video_platforms(platform_id),
    device_id text NOT NULL CHECK (device_id ~ '^[A-Za-z0-9_.-]{1,64}$'),
    channel_id text NOT NULL CHECK (channel_id ~ '^[A-Za-z0-9_.-]{1,64}$'),
    camera_id text NOT NULL REFERENCES cameras(camera_id),
    channel_name text NOT NULL DEFAULT '' CHECK (length(channel_name) <= 200),
    stream_profile text NOT NULL DEFAULT 'unknown' CHECK (stream_profile IN ('main', 'sub', 'unknown')),
    clock_verified boolean NOT NULL DEFAULT false,
    clock_offset_ms bigint,
    active boolean NOT NULL DEFAULT true,
    capabilities jsonb NOT NULL DEFAULT '{}'::jsonb,
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_by text NOT NULL DEFAULT 'system',
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (platform_id, device_id, channel_id),
    CHECK (clock_verified OR clock_offset_ms IS NULL)
);
CREATE UNIQUE INDEX IF NOT EXISTS platform_camera_active_uidx ON platform_camera_channels(camera_id) WHERE active;
CREATE INDEX IF NOT EXISTS platform_camera_platform_idx ON platform_camera_channels(platform_id, camera_id);

CREATE TABLE IF NOT EXISTS platform_media_sessions (
    session_id uuid PRIMARY KEY,
    platform_id text NOT NULL,
    device_id text NOT NULL,
    channel_id text NOT NULL,
    camera_id text NOT NULL REFERENCES cameras(camera_id),
    adapter_kind text NOT NULL CHECK (adapter_kind IN ('gb28181_cascade', 'platform_stream')),
    session_kind text NOT NULL CHECK (session_kind IN ('live', 'playback')),
    stream_profile text NOT NULL CHECK (stream_profile IN ('main', 'sub', 'unknown')),
    media_epoch bigint NOT NULL DEFAULT 0 CHECK (media_epoch >= 0),
    state text NOT NULL CHECK (state IN ('active', 'released', 'expired', 'failed')),
    lease_owner text NOT NULL,
    lease_token uuid NOT NULL,
    lease_expires_at timestamptz NOT NULL,
    media_handle_ref text NOT NULL CHECK (length(media_handle_ref) BETWEEN 1 AND 200),
    source_start_time timestamptz,
    source_end_time timestamptz,
    clock_state text NOT NULL DEFAULT 'unverified' CHECK (clock_state IN ('unverified', 'verified')),
    error_code text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (platform_id, device_id, channel_id) REFERENCES platform_camera_channels(platform_id, device_id, channel_id),
    CHECK (source_end_time IS NULL OR source_start_time IS NULL OR source_end_time >= source_start_time)
);
CREATE INDEX IF NOT EXISTS platform_media_sessions_active_idx ON platform_media_sessions(lease_expires_at) WHERE state = 'active';
CREATE INDEX IF NOT EXISTS platform_media_sessions_camera_epoch_idx ON platform_media_sessions(camera_id, media_epoch DESC);
