-- Application version: 0.37.0. Export installation does not enable production.
INSERT INTO pipeline_features(feature_key,enabled,plugin_key,parameters)
VALUES('evidence_export',false,'bound_video_package_v1','{}'::jsonb) ON CONFLICT DO NOTHING;
ALTER TABLE evidence_exports DROP CONSTRAINT IF EXISTS evidence_exports_part_check;
ALTER TABLE evidence_exports ADD CONSTRAINT evidence_exports_part_check CHECK(part BETWEEN 1 AND 100);
ALTER TABLE evidence_exports ADD COLUMN IF NOT EXISTS artifact_expires_at timestamptz;
CREATE TABLE IF NOT EXISTS evidence_export_consumers (
    worker_id text PRIMARY KEY CHECK(worker_id ~ '^[A-Za-z0-9_.-]{1,100}$'),
    contracts jsonb NOT NULL CHECK(jsonb_typeof(contracts)='array' AND octet_length(contracts::text)<=512),
    quota_bytes bigint NOT NULL CHECK(quota_bytes BETWEEN 541065216 AND 17179869184),
    version text NOT NULL CHECK(length(version)<=40),
    seen_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS evidence_export_consumers_seen_idx ON evidence_export_consumers(seen_at);
