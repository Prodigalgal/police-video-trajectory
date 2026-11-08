-- Application business records. Reviews never update model output or training labels.
-- Application version: 0.21.0. Additive migration; not applied to the public database.
CREATE TABLE IF NOT EXISTS clue_collections (
    collection_id uuid PRIMARY KEY,
    create_fingerprint text NOT NULL,
    title text NOT NULL CHECK(length(title) BETWEEN 1 AND 160),
    note text NOT NULL DEFAULT '' CHECK(length(note)<=2000),
    state text NOT NULL DEFAULT 'open' CHECK(state IN ('open','closed')),
    revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
    created_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS clue_collection_items (
    collection_id uuid NOT NULL REFERENCES clue_collections(collection_id),
    observation_id uuid NOT NULL REFERENCES observations(observation_id),
    added_record_version bigint NOT NULL CHECK(added_record_version>0),
    review_state text NOT NULL DEFAULT 'pending' CHECK(review_state IN ('pending','retained','excluded')),
    review_note text NOT NULL DEFAULT '' CHECK(length(review_note)<=2000),
    reviewed_record_version bigint,
    reviewed_evidence_sha256 text CHECK(reviewed_evidence_sha256 IS NULL OR reviewed_evidence_sha256 ~ '^[a-f0-9]{64}$'),
    reviewed_by text,
    reviewed_at timestamptz,
    added_by text NOT NULL,
    added_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(collection_id,observation_id)
);
CREATE TABLE IF NOT EXISTS clue_candidate_links (
    collection_id uuid NOT NULL,
    from_observation_id uuid NOT NULL,
    to_observation_id uuid NOT NULL,
    from_record_version bigint NOT NULL,
    to_record_version bigint NOT NULL,
    reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),
    review_state text NOT NULL DEFAULT 'pending' CHECK(review_state IN ('pending','accepted','rejected')),
    updated_by text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(collection_id,from_observation_id,to_observation_id),
    FOREIGN KEY(collection_id,from_observation_id) REFERENCES clue_collection_items(collection_id,observation_id),
    FOREIGN KEY(collection_id,to_observation_id) REFERENCES clue_collection_items(collection_id,observation_id),
    CHECK(from_observation_id<>to_observation_id)
);
