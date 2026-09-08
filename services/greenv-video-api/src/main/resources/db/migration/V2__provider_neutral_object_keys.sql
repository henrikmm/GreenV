ALTER TABLE capture_segments RENAME COLUMN video_uri TO video_object_key;
ALTER TABLE capture_segments RENAME COLUMN telemetry_uri TO telemetry_object_key;
ALTER TABLE capture_segments RENAME COLUMN manifest_uri TO manifest_object_key;
