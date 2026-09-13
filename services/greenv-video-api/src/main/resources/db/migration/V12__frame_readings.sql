-- What each photograph contributed, in the database instead of in object storage.
--
-- Verge Studio organises an assessment by cell: every measurement carries the frames that voted
-- in it. Asking "what did this photograph see" therefore meant walking hundreds of cells and
-- thousands of votes, from R2, on every click. The answer per frame is nine numbers and never
-- changes once the run is published, so it belongs in a row.
--
-- Derived, never reported: every column is recomputed from `assessment.json`, so a wrong row is
-- repaired by clearing it and letting the backfill run again. Nothing here justifies waking a
-- GPU.
--
-- The per-cell breakdown deliberately does not live here. The screen shows the aggregate, and a
-- row per cell per frame would be two thousand rows for a ten-second segment to answer a
-- question nobody has asked yet. When someone does, the assessment is still in storage.
CREATE TABLE IF NOT EXISTS segment_frame_readings (
    session_id UUID NOT NULL,
    segment_index INTEGER NOT NULL,
    -- The integer in the JPEG's file name, which is how a position and a vote both name a frame.
    canonical_frame INTEGER NOT NULL,
    cells_voted INTEGER NOT NULL,
    sample_count BIGINT NOT NULL,
    -- Above each cell's own local ground, like every other height this API reports. Null when no
    -- cell this frame voted in ever established its own ground.
    extent95_median_m DOUBLE PRECISION,
    extent95_max_m DOUBLE PRECISION,
    -- The widest gap between what this frame said and what its cell settled on across every
    -- frame. Large means this photograph disagreed with the others that saw the same patch.
    largest_disagreement_m DOUBLE PRECISION,
    evidence_for_cells INTEGER NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (session_id, segment_index, canonical_frame)
);

-- The backfill asks which measured segments have no rows yet. Without this it is a full scan of
-- the readings table for every segment it checks.
CREATE INDEX IF NOT EXISTS segment_frame_readings_segment_idx
    ON segment_frame_readings (session_id, segment_index);
