-- Worker 2 writes its packet beside the frames in object storage, exactly as worker 1 writes the
-- manifest. The database keeps the pointer and the outcome so a reader can find a measurement
-- without listing a bucket, and so a segment's own row says whether one exists.
--
-- V4 and V5 belong to the dashboard branch. Numbering around them costs nothing; two branches
-- claiming one version is a Flyway failure at boot, not a merge conflict anyone would see.
ALTER TABLE capture_segments ADD COLUMN measurement_state VARCHAR(32);
ALTER TABLE capture_segments ADD COLUMN measurement_object_key VARCHAR(2048);
ALTER TABLE capture_segments ADD COLUMN measurement_run_id VARCHAR(128);
ALTER TABLE capture_segments ADD COLUMN measurement_is_mock BOOLEAN;
ALTER TABLE capture_segments ADD COLUMN measured_at TIMESTAMP WITH TIME ZONE;
