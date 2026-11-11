-- Application version: 0.36.0. Optional shared admission; no business data is altered.
CREATE TABLE IF NOT EXISTS cluster_login_challenges (
 challenge_id text PRIMARY KEY CHECK(challenge_id ~ '^[A-Za-z0-9_-]{32,64}$'),
 payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object' AND octet_length(payload::text)<=2048),
 expires_at timestamptz NOT NULL DEFAULT clock_timestamp()+interval '180 seconds'
);
CREATE INDEX IF NOT EXISTS cluster_login_expiry_idx ON cluster_login_challenges(expires_at);
CREATE TABLE IF NOT EXISTS cluster_login_limits (
 kind text NOT NULL CHECK(kind IN ('issue','login')),key_sha256 text NOT NULL CHECK(key_sha256 ~ '^[a-f0-9]{64}$'),
 attempts jsonb NOT NULL CHECK(jsonb_typeof(attempts)='array' AND jsonb_array_length(attempts)<=30),
 expires_at timestamptz NOT NULL,PRIMARY KEY(kind,key_sha256)
);
CREATE INDEX IF NOT EXISTS cluster_login_limits_expiry_idx ON cluster_login_limits(expires_at);
CREATE TABLE IF NOT EXISTS cluster_model_leases (
 lease_id uuid PRIMARY KEY,consumer text NOT NULL CHECK(consumer ~ '^[A-Za-z0-9_.-]{1,100}$'),
 vision boolean NOT NULL,expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS cluster_model_leases_expiry_idx ON cluster_model_leases(expires_at);
CREATE TABLE IF NOT EXISTS cluster_model_policy (
 policy_key text PRIMARY KEY CHECK(policy_key='global'),
 total integer NOT NULL CHECK(total BETWEEN 1 AND 64),
 text_reserved integer NOT NULL CHECK(text_reserved>=0 AND text_reserved<total)
);
