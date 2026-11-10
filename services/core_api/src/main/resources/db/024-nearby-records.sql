-- Application version: 0.30.0. Record browsing does not create a media clock.
CREATE INDEX IF NOT EXISTS frame_nearby_records_idx
    ON frames(task_id,lease_generation,received_at,frame_id) WHERE analysis_state='completed';
