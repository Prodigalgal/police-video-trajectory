-- Application version: 0.22.1. Additive bounded recording-review pagination.
ALTER TABLE capture_outbox ADD COLUMN IF NOT EXISTS admission_requested boolean NOT NULL DEFAULT true;
CREATE INDEX IF NOT EXISTS capture_admission_requested_idx
    ON capture_outbox(registered_at,frame_id) WHERE enqueued_at IS NULL AND admission_requested;
CREATE INDEX IF NOT EXISTS capture_recording_review_idx
    ON capture_outbox(camera_id,registered_at DESC,frame_id DESC);
