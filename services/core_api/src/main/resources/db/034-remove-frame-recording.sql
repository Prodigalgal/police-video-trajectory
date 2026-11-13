-- Application version: 1.0.0. Removes same-session recording retention and nearby-frame browsing.
--
-- The application no longer retains recordings next to valid frames. What stays is
-- the frame's own position in the source stream - capture_outbox.media_offset_ms /
-- relative_media_ms, frames.received_at - which is written by the capture path and
-- is independent of this feature. Operators look the recording up in the upstream
-- recording platform using that position.
--
-- Dropped here: only the objects that exist to bind a frame to a locally retained
-- segment. Deliberately kept:
--   * capture_outbox / frames time columns - the frame's real recording position.
--   * capture_outbox.admission_requested and its index (023) - analysis handoff,
--     a different capability from recording.
--   * media_production_jobs / media_production_requests / recorded_media_clips -
--     shared with historical-archive media production, which remains supported.
--   * recorded_media_clips rows are left intact: the table has no input-kind column,
--     and its archive rows are still in use.
--
-- This migration is destructive for the dropped objects and is not reversible by
-- re-running earlier migrations; those recreate empty tables without their data.

-- Order matters: media_job_frames and media_production_requests both reference
-- media_production_jobs, so the children go before session job rows are deleted.
DROP TABLE IF EXISTS media_job_frames;
DELETE FROM media_production_requests
WHERE job_id IN (SELECT job_id FROM media_production_jobs WHERE input_kind = 'session');
DELETE FROM media_production_jobs WHERE input_kind = 'session';

DROP TABLE IF EXISTS frame_recording_links;
DROP TABLE IF EXISTS stream_recording_segments;
DROP INDEX IF EXISTS capture_recording_review_idx;
DROP INDEX IF EXISTS frame_nearby_records_idx;
DELETE FROM pipeline_features WHERE feature_key = 'frame_recording';

-- 015 accepted session inputs and 029 raised the window ceiling for the session-v2
-- context descriptor. With the session path gone, production is archive-only again,
-- so restore the 014 contract and drop the session-only columns. Leaving the looser
-- check in place would advertise a capability the application no longer implements.
ALTER TABLE media_production_jobs DROP CONSTRAINT IF EXISTS media_production_jobs_window_check;
ALTER TABLE media_production_jobs DROP CONSTRAINT IF EXISTS media_production_jobs_profile_version_check;
ALTER TABLE media_production_jobs DROP CONSTRAINT IF EXISTS media_production_jobs_input_kind_check;
ALTER TABLE media_production_jobs DROP COLUMN IF EXISTS input_descriptor;
ALTER TABLE media_production_jobs DROP COLUMN IF EXISTS input_kind;
ALTER TABLE media_production_jobs ADD CONSTRAINT media_production_jobs_window_check
    CHECK (end_ms - start_ms BETWEEN 2000 AND 30000);
ALTER TABLE media_production_jobs ADD CONSTRAINT media_production_jobs_profile_version_check
    CHECK (profile_version = 'archive-h264-10fps-v1');
