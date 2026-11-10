-- Application version: 0.35.0. Additive frozen provenance, never a confidence claim.
CREATE TABLE IF NOT EXISTS analysis_artifacts (
 artifact_sha256 text PRIMARY KEY CHECK(artifact_sha256 ~ '^[0-9a-f]{64}$'),
 content text NOT NULL,
 content_bytes integer NOT NULL CHECK(content_bytes=octet_length(content) AND content_bytes BETWEEN 1 AND 393216),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS frame_analysis_receipts (
 frame_id uuid PRIMARY KEY REFERENCES frames(frame_id),
 request_sha256 text NOT NULL REFERENCES analysis_artifacts(artifact_sha256),
 response_sha256 text NOT NULL REFERENCES analysis_artifacts(artifact_sha256),
 schema_sha256 text NOT NULL REFERENCES analysis_artifacts(artifact_sha256),
 receipt_sha256 text NOT NULL CHECK(receipt_sha256 ~ '^[0-9a-f]{64}$'),
 receipt jsonb NOT NULL CHECK(jsonb_typeof(receipt)='object'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
