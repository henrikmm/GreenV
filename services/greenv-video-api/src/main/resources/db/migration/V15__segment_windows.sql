-- A measured stretch is a window of a segment, not the whole upload.
--
-- The frame extractor used to spend its entire frame budget on the first few tens of metres of a
-- segment, so most of the road it drove past was never measured. It now cuts a segment into
-- windows of about 25 m and publishes every one, and the measurement worker answers with one
-- reconstruction, one packet and one announcement per window. What the dashboard calls a trecho
-- and what an operations team sends a crew to is therefore a window, and a window needs a row of
-- its own before anything can list it, sort it, filter it or point a service order at it.
--
-- Every measurement column below is spelled exactly as `capture_segments` already spells it
-- (V8, V13, V14) and every place column exactly as V11 spells it. The two rows answer the same
-- question at two scales and are read through the same mapper, so a second vocabulary for the
-- same values would only be a translation layer to keep in step.
--
-- `capture_segments` keeps its own copies as a rollup of its windows -- the worst window's
-- height, max and level, the summed cell counts, the latest measurement -- so the session screen
-- and the per-segment counters keep working without knowing windows exist.
--
-- V4 and V5 belong to a branch that was never merged; this numbers past V14.
CREATE TABLE IF NOT EXISTS capture_segment_windows (
    session_id UUID NOT NULL,
    segment_index INTEGER NOT NULL,
    -- Zero based, in the order the extractor cut them, and the same number that names the
    -- packet's own directory: `<outputPrefix>/measurement/w03/`.
    window_index INTEGER NOT NULL,

    -- Where the window sits along the segment's camera path, in metres from its start. Null when
    -- the worker announced a window without them rather than zero, because a window that begins
    -- at the start of the segment and a window whose extent nobody recorded are not the same
    -- thing.
    start_meters DOUBLE PRECISION,
    end_meters DOUBLE PRECISION,

    measurement_state VARCHAR(32),
    measurement_object_key VARCHAR(2048),
    measurement_run_id VARCHAR(128),
    measurement_is_mock BOOLEAN,
    measured_at TIMESTAMP WITH TIME ZONE,

    -- Measured from each cell's own local ground, like every other height this API reports.
    measurement_extent95_p95_m DOUBLE PRECISION,
    measurement_extent95_max_m DOUBLE PRECISION,
    -- 1, 2 or 3 on the thresholds the dashboard draws. Null when no cell was measured, and null
    -- must never render as 1: an unknown height is not a short one.
    measurement_level INTEGER,
    measurement_cells_measured INTEGER,
    measurement_cells_abstained INTEGER,
    measurement_coverage DOUBLE PRECISION,
    measurement_track_length_m DOUBLE PRECISION,

    track_center_lat DOUBLE PRECISION,
    track_center_lon DOUBLE PRECISION,
    track_geojson TEXT,
    track_location_quality VARCHAR(16),

    -- Where this window is, in words. Cached from the window's own track centre and not inherited
    -- from the segment: a 200 m segment crosses streets, and naming all eight of its windows
    -- after the middle one is exactly the distortion the per-segment cache was introduced to
    -- remove.
    place_label VARCHAR(256),
    place_detail VARCHAR(256),
    place_house_number VARCHAR(32),
    place_road VARCHAR(32),
    place_km INTEGER,
    place_km_offset_m DOUBLE PRECISION,
    place_source VARCHAR(32),
    place_resolved_at TIMESTAMP WITH TIME ZONE,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    PRIMARY KEY (session_id, segment_index, window_index),
    CONSTRAINT fk_capture_segment_window_segment
        FOREIGN KEY (session_id, segment_index)
        REFERENCES capture_segments (session_id, segment_index) ON DELETE CASCADE
);

-- The readings list orders by height and pages through it, so the same index V13 gives segments.
-- The whole key follows the sort key: two windows measured at the same height would otherwise be
-- free to swap places between one page and the next, and a row would appear twice or not at all
-- while somebody scrolls. NULLS LAST matches the ORDER BY it exists for, and that direction is a
-- decision: a window nobody could measure is not a short one.
CREATE INDEX IF NOT EXISTS capture_segment_windows_height_idx
    ON capture_segment_windows (
        measurement_extent95_p95_m DESC NULLS LAST, session_id, segment_index, window_index);

-- Freshness rather than severity, and the filter that asks which windows carry a measurement.
CREATE INDEX IF NOT EXISTS capture_segment_windows_measured_idx
    ON capture_segment_windows (measurement_state, measured_at DESC);

-- The level chips filter on this alone, across every session.
CREATE INDEX IF NOT EXISTS capture_segment_windows_level_idx
    ON capture_segment_windows (measurement_level);

-- The place backfill asks for windows that carry a position and were never tried. Not a partial
-- index on `track_center_lat IS NOT NULL`, which is what the query really wants: H2 in PostgreSQL
-- mode runs the test suite and has no partial indexes. The pair narrows the same scan on both.
CREATE INDEX IF NOT EXISTS capture_segment_windows_place_pending_idx
    ON capture_segment_windows (place_resolved_at, track_center_lat);

-- An order targets a window now, and the table that records what it covers has to be able to say
-- which one -- including "none of them, the segment was measured whole", which is a null.
--
-- A null cannot sit in a primary key, in PostgreSQL or in H2, so the old key
-- `(order_id, session_id, segment_index)` cannot simply gain the column: it would also refuse an
-- order covering two windows of the same segment, which is the ordinary case for a 200 m stretch
-- where every window is overdue. Inventing a sentinel instead would make every reader of this
-- table decode it.
--
-- The table is therefore rebuilt without a primary key and with the indexes its two queries
-- actually read. The uniqueness the key used to enforce moves to OperationsService, which
-- deduplicates a draft's targets before inserting them; this table is written by that one code
-- path, once, inside the transaction that opens the order.
CREATE TABLE IF NOT EXISTS service_order_segments_v15 (
    order_id UUID NOT NULL REFERENCES service_orders (order_id) ON DELETE CASCADE,
    session_id UUID NOT NULL,
    segment_index INTEGER NOT NULL,
    -- Null means the whole segment: an order opened before windows existed, or one opened against
    -- a segment whose frames made a single window.
    window_index INTEGER
);

INSERT INTO service_order_segments_v15 (order_id, session_id, segment_index)
     SELECT order_id, session_id, segment_index FROM service_order_segments;

DROP TABLE service_order_segments;

ALTER TABLE service_order_segments_v15 RENAME TO service_order_segments;

CREATE INDEX IF NOT EXISTS service_order_segments_order_idx
    ON service_order_segments (order_id);

CREATE INDEX IF NOT EXISTS service_order_segments_segment_idx
    ON service_order_segments (session_id, segment_index);
