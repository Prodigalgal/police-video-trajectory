-- Application version: 1.1.0. Additive query-driven cross-camera candidate snapshots.
-- Source imagery, vectors and local recordings are not duplicated here.
CREATE TABLE IF NOT EXISTS mtmc_queries (
 query_id uuid PRIMARY KEY,actor_id text NOT NULL,actor_revision bigint NOT NULL CHECK(actor_revision>0),
 request_sha256 text NOT NULL CHECK(request_sha256 ~ '^[a-f0-9]{64}$'),
 scope_sha256 text NOT NULL CHECK(scope_sha256 ~ '^[a-f0-9]{64}$'),
 state text NOT NULL CHECK(state IN ('preparing','completed')),
 lease_token uuid,lease_expires_at timestamptz,
 summary jsonb NOT NULL DEFAULT '{}'::jsonb,runs jsonb NOT NULL DEFAULT '[]'::jsonb,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 expires_at timestamptz NOT NULL DEFAULT clock_timestamp()+interval '30 minutes',
 CHECK ((state='preparing')=(lease_token IS NOT NULL AND lease_expires_at IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS mtmc_queries_expiry_idx ON mtmc_queries(expires_at);
CREATE INDEX IF NOT EXISTS mtmc_queries_preparing_idx ON mtmc_queries(lease_expires_at) WHERE state='preparing';
