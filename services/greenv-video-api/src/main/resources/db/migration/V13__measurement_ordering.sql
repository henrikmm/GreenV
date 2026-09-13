-- Ordering the list of readings is the database's job.
--
-- The dashboard was asking for two hundred rows and sorting them in the browser. That answer is
-- only right while every measured segment fits in one request: the moment it does not, page one
-- is the tallest of an arbitrary two hundred rather than the tallest there is, and nothing on
-- screen says so. Sorting and paging move to SQL, and these are the two orders that list will
-- actually be asked for.
--
-- Both indexes carry the primary key behind the sort key. Two segments measured at the same
-- height, or captured in the same second, would otherwise be free to swap places between one
-- page and the next, and a row would appear twice or not at all while somebody scrolls.
--
-- NULLS LAST in the height index matches the ORDER BY it exists for, and that direction is a
-- decision, not a default: a segment nobody could measure is not a short one. Sorted as zero it
-- would sit among the trimmed verges, which is the opposite of what the list is for.
--
-- Not partial. `WHERE measurement_state IS NOT NULL` would suit both of these, and PostgreSQL
-- would take it, but H2 in PostgreSQL mode rejects a partial index and the whole test suite runs
-- on H2. The predicate stays in the query.

CREATE INDEX IF NOT EXISTS capture_segments_height_idx
    ON capture_segments (measurement_extent95_p95_m DESC NULLS LAST, session_id, segment_index);

CREATE INDEX IF NOT EXISTS capture_segments_captured_idx
    ON capture_segments (captured_at DESC, session_id, segment_index);
