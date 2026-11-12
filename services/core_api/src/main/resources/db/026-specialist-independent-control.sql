-- Application version: 0.37.0. Specialist consumption is independent of main analysis.
ALTER TABLE processing_controls ADD COLUMN IF NOT EXISTS revision bigint NOT NULL DEFAULT 1;
INSERT INTO processing_controls(control_key,enabled) VALUES('specialist_claims',false) ON CONFLICT DO NOTHING;
ALTER TABLE specialist_plate_reviews ALTER COLUMN event_id DROP NOT NULL;
ALTER TABLE specialist_plate_reviews ADD COLUMN IF NOT EXISTS review_scope text NOT NULL DEFAULT 'event';
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conname='specialist_review_scope_check' AND conrelid='specialist_plate_reviews'::regclass) THEN
  ALTER TABLE specialist_plate_reviews ADD CONSTRAINT specialist_review_scope_check CHECK(
   (review_scope='event' AND event_id IS NOT NULL) OR (review_scope='single_frame' AND event_id IS NULL));
 END IF;
END $$;
