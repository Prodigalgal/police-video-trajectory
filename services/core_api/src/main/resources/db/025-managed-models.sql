-- Application version: 0.26.0. Credentials stay in mounted provider Secrets.
CREATE TABLE IF NOT EXISTS model_profiles (
    profile_id uuid NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    definition jsonb NOT NULL,
    created_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (profile_id, version)
);
CREATE TABLE IF NOT EXISTS model_bindings (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    bindings jsonb NOT NULL DEFAULT '{"text":null,"vision":null}',
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO model_bindings(singleton) VALUES(true) ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS model_changes (
    request_id uuid PRIMARY KEY,
    request_sha256 char(64) NOT NULL,
    response jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS model_consumers (
    consumer_id text PRIMARY KEY,
    revision bigint NOT NULL,
    binding_sha256 char(64) NOT NULL,
    seen_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
