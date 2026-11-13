-- Application version: 1.0.0. Operator bindings do not mutate installed model definitions.
CREATE TABLE IF NOT EXISTS managed_model_catalogs (
    catalog_key text PRIMARY KEY CHECK(catalog_key IN ('processing','appearance')),
    revision bigint NOT NULL CHECK(revision BETWEEN 1 AND 9007199254740991),
    base_sha256 text NOT NULL CHECK(base_sha256 ~ '^[a-f0-9]{64}$'),
    definition_sha256 text NOT NULL CHECK(definition_sha256 ~ '^[a-f0-9]{64}$'),
    definition jsonb NOT NULL CHECK(jsonb_typeof(definition)='object' AND octet_length(definition::text)<=196608),
    updated_by text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
