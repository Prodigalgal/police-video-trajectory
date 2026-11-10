-- Application version: 0.32.0. Extend only explicit v2 session inputs; archive limits remain unchanged.
ALTER TABLE media_production_jobs DROP CONSTRAINT IF EXISTS media_production_jobs_check;
ALTER TABLE media_production_jobs DROP CONSTRAINT IF EXISTS media_production_jobs_window_check;
ALTER TABLE media_production_jobs ADD CONSTRAINT media_production_jobs_window_check CHECK (
    end_ms-start_ms BETWEEN 2000 AND CASE
        WHEN input_kind='session' AND input_descriptor->>'version'='session-segment-input-v2' THEN 75000
        ELSE 30000
    END
);
