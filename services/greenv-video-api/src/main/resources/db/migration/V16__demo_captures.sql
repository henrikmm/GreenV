-- The FIAP Next demo: a phone sends a short video, this API samples it, a RunPod endpoint turns
-- the frames into a reconstruction, and a public site shows every step.
--
-- The pipeline that walks a capture through those steps runs on every replica at once and has to
-- survive the API scaling to zero halfway through, so its whole state lives here and nowhere
-- else. A replica works on a capture only while it holds the lease below, and writes only while
-- it still holds it.
--
-- Nothing here joins to capture_sessions. A demo capture is a person filming an object in a hall,
-- not a road, and mixing the two would put a visitor's video into the readings an operations team
-- sends crews from.
CREATE TABLE IF NOT EXISTS demo_captures (
    -- `cap-YYYYMMDD-HHMMSS-xxxx`, the same shape the Mac gateway gives out, so the phone and the
    -- site treat an id from either the same way. Sorts by time.
    capture_id VARCHAR(64) PRIMARY KEY,
    -- The last phase written; one of the contract's phase names.
    state VARCHAR(32) NOT NULL,
    -- The phone's verge-phone-capture/1 document as it arrived, unknown fields included.
    meta_json TEXT NOT NULL,

    -- The four fields every GreenV artifact carries. A demo capture is on no road and says so with
    -- nulls rather than by leaving the columns out; capturado_em is the phone's own timestamp.
    rodovia VARCHAR(32),
    sentido VARCHAR(16),
    km DOUBLE PRECISION,
    capturado_em TIMESTAMP WITH TIME ZONE NOT NULL,

    video_object_key VARCHAR(512),
    video_content_type VARCHAR(64),
    video_bytes BIGINT,
    video_sha256 CHAR(64),
    video_transfer_ms BIGINT,
    -- What ffprobe said the file is, once the pipeline has opened it.
    video_probe_json TEXT,

    -- A JSON array of object keys, in frame order. Keys and never URLs: a URL is signed when read.
    frame_keys_json TEXT,

    depth_job_id VARCHAR(128),
    depth_job_submitted_at TIMESTAMP WITH TIME ZONE,
    -- The handler's manifest, as it answered. Its artifact URLs are re-signed on every read.
    manifest_json TEXT,
    gpu VARCHAR(128),
    gpu_seconds DOUBLE PRECISION,

    -- How many times extraction was started. A capture that kills the replica working on it would
    -- otherwise be picked up again forever.
    attempts INTEGER NOT NULL DEFAULT 0,
    lease_owner VARCHAR(128),
    lease_until TIMESTAMP WITH TIME ZONE,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- The pipeline asks for captures in a given state whose lease is free.
CREATE INDEX IF NOT EXISTS demo_captures_state_idx ON demo_captures (state, lease_until);

-- The site lists the newest captures, and retention removes the oldest.
CREATE INDEX IF NOT EXISTS demo_captures_created_idx ON demo_captures (created_at);

-- A capture's timeline, one row per phase, in the order written. `t_ms` is milliseconds since the
-- capture was announced, on this server's clock only.
CREATE TABLE IF NOT EXISTS demo_capture_phases (
    capture_id VARCHAR(64) NOT NULL REFERENCES demo_captures (capture_id) ON DELETE CASCADE,
    seq INTEGER NOT NULL,
    phase VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    t_ms BIGINT NOT NULL,
    detail_json TEXT,
    PRIMARY KEY (capture_id, seq)
);
