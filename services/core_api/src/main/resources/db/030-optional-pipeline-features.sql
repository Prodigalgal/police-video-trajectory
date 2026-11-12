-- Application version: 0.37.0. Optional storage producers are disabled until explicitly configured.
CREATE TABLE IF NOT EXISTS pipeline_features (
    feature_key text PRIMARY KEY,
    enabled boolean NOT NULL DEFAULT false,
    plugin_key text NOT NULL,
    parameters jsonb NOT NULL DEFAULT '{}'::jsonb,
    version bigint NOT NULL DEFAULT 1 CHECK(version BETWEEN 1 AND 9007199254740991),
    updated_by text,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK(jsonb_typeof(parameters)='object' AND octet_length(parameters::text)<=1024)
);
INSERT INTO pipeline_features(feature_key,enabled,plugin_key,parameters)
VALUES('frame_recording',false,'short_segment_v1','{}'::jsonb) ON CONFLICT DO NOTHING;
