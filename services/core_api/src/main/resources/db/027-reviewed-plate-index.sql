-- Application version: 0.31.0. Independent projection versions preserve source observations.
CREATE TABLE IF NOT EXISTS reviewed_plate_index_tasks (
 observation_id uuid PRIMARY KEY REFERENCES observations(observation_id),
 requested_revision bigint NOT NULL DEFAULT 1 CHECK(requested_revision>0),
 published_revision bigint NOT NULL DEFAULT 0 CHECK(published_revision>=0),
 source_record_version bigint, evidence_sha256 text, fact_sha256 text,
 projection_outcome text NOT NULL DEFAULT 'pending',
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
 lease_owner text, lease_generation bigint NOT NULL DEFAULT 0, lease_expires_at timestamptz,
 next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),last_error_code text,
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS reviewed_plate_pending_idx ON reviewed_plate_index_tasks(next_attempt_at,observation_id)
 WHERE requested_revision>published_revision;
CREATE OR REPLACE FUNCTION dirty_reviewed_plate_index() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE anchor_id uuid;
BEGIN
 SELECT anchor_observation_id INTO anchor_id FROM analysis_inputs i JOIN specialist_analysis_jobs j ON j.input_id=i.input_id WHERE j.job_id=NEW.job_id;
 IF anchor_id IS NOT NULL THEN
  INSERT INTO reviewed_plate_index_tasks(observation_id) VALUES(anchor_id)
  ON CONFLICT(observation_id) DO UPDATE SET requested_revision=reviewed_plate_index_tasks.requested_revision+1,
   attempts=0,next_attempt_at=clock_timestamp(),last_error_code=NULL,projection_outcome='pending',updated_at=clock_timestamp();
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS reviewed_plate_changed ON specialist_plate_reviews;
CREATE TRIGGER reviewed_plate_changed AFTER INSERT OR UPDATE ON specialist_plate_reviews FOR EACH ROW EXECUTE FUNCTION dirty_reviewed_plate_index();
CREATE OR REPLACE FUNCTION dirty_reviewed_plate_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.record_version IS DISTINCT FROM OLD.record_version OR NEW.deleted IS DISTINCT FROM OLD.deleted THEN
  UPDATE reviewed_plate_index_tasks SET requested_revision=requested_revision+1,attempts=0,next_attempt_at=clock_timestamp(),
   last_error_code=NULL,projection_outcome='pending',updated_at=clock_timestamp() WHERE observation_id=NEW.observation_id;
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS reviewed_plate_source_changed ON observations;
CREATE TRIGGER reviewed_plate_source_changed AFTER UPDATE OF record_version,deleted ON observations FOR EACH ROW EXECUTE FUNCTION dirty_reviewed_plate_source();
INSERT INTO reviewed_plate_index_tasks(observation_id)
 SELECT DISTINCT i.anchor_observation_id FROM specialist_plate_reviews r
 JOIN specialist_analysis_jobs j ON j.job_id=r.job_id JOIN analysis_inputs i ON i.input_id=j.input_id
 ON CONFLICT DO NOTHING;
