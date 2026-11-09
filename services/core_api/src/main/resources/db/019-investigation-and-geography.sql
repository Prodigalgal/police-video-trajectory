-- Application version: 0.24.1. Additive investigation/geography contracts.
CREATE TABLE IF NOT EXISTS camera_locations (
 camera_id text PRIMARY KEY REFERENCES cameras(camera_id),
 longitude double precision,latitude double precision,
 coordinate_system text NOT NULL DEFAULT 'GCJ-02' CHECK(coordinate_system='GCJ-02'),
 coordinate_source text NOT NULL DEFAULT 'configured' CHECK(coordinate_source IN ('configured','display_layout')),
 address text NOT NULL DEFAULT '' CHECK(length(address)<=500),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),updated_by text NOT NULL,updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK((longitude IS NULL)=(latitude IS NULL)),
 CHECK(longitude IS NULL OR longitude BETWEEN -180 AND 180),CHECK(latitude IS NULL OR latitude BETWEEN -90 AND 90)
);
ALTER TABLE camera_locations ADD COLUMN IF NOT EXISTS coordinate_source text NOT NULL DEFAULT 'configured' CHECK(coordinate_source IN ('configured','display_layout'));
CREATE INDEX IF NOT EXISTS camera_locations_bounds_idx ON camera_locations(longitude,latitude) WHERE longitude IS NOT NULL;
CREATE TABLE IF NOT EXISTS application_command_receipts (
 request_id uuid PRIMARY KEY,operation text NOT NULL,actor_id text NOT NULL,
 request_sha256 text NOT NULL CHECK(request_sha256 ~ '^[a-f0-9]{64}$'),response jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS observation_camera_visible_idx ON observations(camera_id,target_type,frame_id,observation_id) WHERE NOT deleted;
CREATE INDEX IF NOT EXISTS frame_verified_clock_idx ON frames(source_time,frame_id) WHERE source_time_origin='verified';
