-- Apply once to existing trial databases before deploying a core build that uses these fields.
-- Old observations retain an unknown box and an empty uncertainty list.
ALTER TABLE observations ADD COLUMN IF NOT EXISTS bbox_xyxy jsonb;
ALTER TABLE observations ADD COLUMN IF NOT EXISTS uncertain_fields jsonb NOT NULL DEFAULT '[]'::jsonb;
