-- Application version: 0.21.1. Export artifacts and server transfer journals; no inference work.
CREATE TABLE IF NOT EXISTS evidence_exports (
 export_id uuid PRIMARY KEY,collection_id uuid NOT NULL REFERENCES clue_collections(collection_id),
 collection_revision bigint NOT NULL CHECK(collection_revision>0),part integer NOT NULL CHECK(part BETWEEN 1 AND 50),
 actor_id text NOT NULL,actor_revision bigint NOT NULL CHECK(actor_revision>0),
 manifest jsonb NOT NULL,manifest_payload text NOT NULL,manifest_sha256 text NOT NULL CHECK(manifest_sha256 ~ '^[a-f0-9]{64}$'),
 state text NOT NULL DEFAULT 'queued' CHECK(state IN ('queued','active','completed','failed','cancelled')),
 revision bigint NOT NULL DEFAULT 1,attempts integer NOT NULL DEFAULT 0,next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 lease_owner text,lease_generation bigint NOT NULL DEFAULT 0,lease_expires_at timestamptz,last_error_code text,
 artifact_generation bigint,artifact_sha256 text,artifact_bytes bigint,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK(state<>'completed' OR (artifact_generation IS NOT NULL AND artifact_sha256 IS NOT NULL AND artifact_bytes IS NOT NULL AND artifact_generation>0 AND artifact_sha256 ~ '^[a-f0-9]{64}$' AND artifact_bytes BETWEEN 1 AND 541065216))
);
CREATE INDEX IF NOT EXISTS evidence_exports_claim_idx ON evidence_exports(state,next_attempt_at,created_at,export_id);
CREATE INDEX IF NOT EXISTS evidence_exports_actor_idx ON evidence_exports(actor_id,collection_id,created_at DESC,export_id DESC);
CREATE TABLE IF NOT EXISTS evidence_export_transfers (
 transfer_id uuid PRIMARY KEY,export_id uuid NOT NULL REFERENCES evidence_exports(export_id),actor_id text NOT NULL,
 artifact_sha256 text NOT NULL,range_start bigint NOT NULL,range_end bigint NOT NULL,
 expected_bytes bigint NOT NULL,sent_bytes bigint NOT NULL DEFAULT 0,
 state text NOT NULL DEFAULT 'streaming' CHECK(state IN ('streaming','server_complete','interrupted','failed')),
 started_at timestamptz NOT NULL DEFAULT clock_timestamp(),finished_at timestamptz,
 CHECK(range_start>=0 AND range_end>=range_start AND expected_bytes=range_end-range_start+1 AND sent_bytes BETWEEN 0 AND expected_bytes)
);
CREATE INDEX IF NOT EXISTS evidence_export_transfers_export_idx ON evidence_export_transfers(export_id,started_at DESC,transfer_id DESC);
CREATE TABLE IF NOT EXISTS evidence_export_attempts (
 export_id uuid NOT NULL REFERENCES evidence_exports(export_id),generation bigint NOT NULL CHECK(generation>0),
 worker_id text NOT NULL,manifest_sha256 text NOT NULL CHECK(manifest_sha256 ~ '^[a-f0-9]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),reclaimed_at timestamptz,
 reclaim_token uuid,reclaim_owner text,reclaim_expires_at timestamptz,
 PRIMARY KEY(export_id,generation)
);
CREATE INDEX IF NOT EXISTS evidence_export_attempts_reclaim_idx ON evidence_export_attempts(created_at,export_id,generation) WHERE reclaimed_at IS NULL;
