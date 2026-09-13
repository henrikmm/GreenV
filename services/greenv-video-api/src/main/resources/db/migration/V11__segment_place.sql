-- Where a measured stretch is, in words.
--
-- The coordinate is the truth and the word is the convenience, so this is a cache and never an
-- input: every column below is derived from `track_center_lat`/`track_center_lon` and can be
-- thrown away and rebuilt. `place_resolved_at` is what says a row was tried, so a lookup that
-- legitimately finds nothing is not retried forever.
--
-- It lives on the segment and not on the session on purpose. A session is a drive, and a drive
-- crosses streets; naming the whole drive after the average of its stretches is exactly the
-- distortion this column exists to remove.
ALTER TABLE capture_segments ADD COLUMN place_label VARCHAR(256);
ALTER TABLE capture_segments ADD COLUMN place_detail VARCHAR(256);

-- Approximate on purpose, and the API says so in the field name. Reverse geocoding returns the
-- number of the nearest addressable point, which on a verge is the building across the road.
ALTER TABLE capture_segments ADD COLUMN place_house_number VARCHAR(32);

-- The nearest kilometre marker of the reference road, and how far it was. The markers average
-- 1066 m apart, so this names a stretch of road and must never be read as a position: a reading
-- 400 m from the KM 12 post is still reported as KM 12.
ALTER TABLE capture_segments ADD COLUMN place_road VARCHAR(32);
ALTER TABLE capture_segments ADD COLUMN place_km INTEGER;
ALTER TABLE capture_segments ADD COLUMN place_km_offset_m DOUBLE PRECISION;

ALTER TABLE capture_segments ADD COLUMN place_source VARCHAR(32);
ALTER TABLE capture_segments ADD COLUMN place_resolved_at TIMESTAMP WITH TIME ZONE;

-- The backfill asks for measured stretches that carry a position and were never tried. Without
-- this index that question is a full scan every minute, forever.
--
-- Not a partial index on `track_center_lat IS NOT NULL`, which is what the query really wants:
-- H2 in PostgreSQL mode, which the contract test runs against, has no partial indexes. The pair
-- below narrows the same scan and works on both engines.
CREATE INDEX IF NOT EXISTS capture_segments_place_pending_idx
    ON capture_segments (place_resolved_at, track_center_lat);
