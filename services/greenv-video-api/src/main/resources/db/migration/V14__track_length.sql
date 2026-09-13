-- How far the camera actually travelled in a segment.
--
-- The area on a service order was `segments × 10 m × 150 m`, so every stretch was 1500 m² and
-- every order was a multiple of it. The 150 was a stated assumption — segment length "was not
-- recorded" — and it is wrong by more than an order of magnitude for the captures on file.
-- Measured from the tracks already in this table: the walking segments cover 7.7 to 11.7 metres
-- in ten seconds, at 2.7 to 6.8 km/h, and the one car segment covers 83.2 metres at 35.7 km/h.
-- A fixed 150 overstates a walk fifteenfold and happens to land near a car.
--
-- The track is the honest source and it was already being stored beside this column, as the
-- GeoJSON the map draws. What was missing was the one number an area needs.
--
-- Backfilled separately rather than here: the sum over a LineString wants jsonb and the test
-- suite runs on H2, which has none. The rows measured before this migration are updated by hand
-- against PostgreSQL, and a null keeps the old assumption until they are.

ALTER TABLE capture_segments
    ADD COLUMN IF NOT EXISTS measurement_track_length_m DOUBLE PRECISION;
