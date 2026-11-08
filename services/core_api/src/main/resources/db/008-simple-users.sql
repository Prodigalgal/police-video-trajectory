-- Account management only. No role hierarchy, groups, training or camera ACLs.
CREATE TABLE IF NOT EXISTS user_accounts (
    username text PRIMARY KEY CHECK (username ~ '^[a-z0-9_.@-]{3,80}$'),
    display_name text NOT NULL CHECK (length(display_name) BETWEEN 1 AND 100),
    password_hash text NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    is_admin boolean NOT NULL DEFAULT false,
    credential_revision bigint NOT NULL DEFAULT 1 CHECK (credential_revision>0),
    row_version bigint NOT NULL DEFAULT 1 CHECK (row_version>0),
    deleted boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
