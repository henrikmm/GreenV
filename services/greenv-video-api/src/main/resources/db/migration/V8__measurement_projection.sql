-- What a map needs from a measurement, in the database rather than in object storage.
--
-- The readings themselves stay where worker 2 wrote them: a packet is 10 to 55 KB and the full
-- cell grid far more, and none of it belongs in a row. What goes here is the handful of values a
-- list query has to sort, filter and colour by. Without them a dashboard drawing one screen of
-- segments has to fetch one packet per segment from R2 and aggregate in the browser.
--
-- Every column below is derived, never reported: the API recomputes them from the stored packet
-- when it records a measurement, so a wrong projection is repaired by re-reading R2 and never by
-- paying for the GPU again.
--
-- V4 and V5 belong to the unmerged dashboard branch. Numbering past them costs nothing.

-- The height. `extent95` is measured from each cell's own local ground and is the only estimator
-- ever graded against a tape; `h95` in the packet is measured from the fitted plane and reads
-- higher by whatever that cell's ground sits above it. A dashboard that shows the wrong one
-- overstates every reading, so the name here says which it is.
ALTER TABLE capture_segments ADD COLUMN measurement_extent95_p95_m DOUBLE PRECISION;
ALTER TABLE capture_segments ADD COLUMN measurement_extent95_max_m DOUBLE PRECISION;

-- 1, 2 or 3 on the thresholds the dashboard already draws: under 10 cm, 10 to 30 cm, over 30 cm.
-- Null when no cell was measured, and null must never render as level 1 — an unknown height is
-- not a short one.
ALTER TABLE capture_segments ADD COLUMN measurement_level INTEGER;

ALTER TABLE capture_segments ADD COLUMN measurement_cells_measured INTEGER;
ALTER TABLE capture_segments ADD COLUMN measurement_cells_abstained INTEGER;

-- Of the cells the cameras actually observed, the fraction that could be measured. It is not the
-- fraction of the verge covered: the packet carries `intendedAreaCoverage: null` on purpose,
-- because nothing knows how much verge there was to see.
ALTER TABLE capture_segments ADD COLUMN measurement_coverage DOUBLE PRECISION;

-- Where the segment is. The centroid answers "which pin on the map"; the track answers "what
-- shape". Both come from the camera positions in the packet, which are the only geo-referenced
-- thing the measurement produces — a measured cell lives in road-local metres and cannot be
-- placed on a map at all.
ALTER TABLE capture_segments ADD COLUMN track_center_lat DOUBLE PRECISION;
ALTER TABLE capture_segments ADD COLUMN track_center_lon DOUBLE PRECISION;
ALTER TABLE capture_segments ADD COLUMN track_geojson TEXT;

-- The worst fix quality along the track: good, degraded or unavailable. A track drawn from
-- degraded fixes is still worth drawing and must not be read as a survey.
ALTER TABLE capture_segments ADD COLUMN track_location_quality VARCHAR(16);

-- Listing indexes. None of these existed, because until now nothing listed anything: every read
-- path was keyed by a primary key the caller already had.
CREATE INDEX IF NOT EXISTS capture_sessions_started_idx
    ON capture_sessions(started_at DESC);

CREATE INDEX IF NOT EXISTS capture_sessions_road_idx
    ON capture_sessions(rodovia, sentido);

-- Ordered by when it was measured, which is how a dashboard opens: most recent first.
CREATE INDEX IF NOT EXISTS capture_segments_measured_idx
    ON capture_segments(measurement_state, measured_at DESC);

-- Session-scoped listing. The existing capture_segments_state_idx leads with `state`, so it
-- cannot serve "every segment of this session in index order".
CREATE INDEX IF NOT EXISTS capture_segments_session_idx
    ON capture_segments(session_id, segment_index);
