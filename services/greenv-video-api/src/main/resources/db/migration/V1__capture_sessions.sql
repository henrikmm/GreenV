CREATE TABLE IF NOT EXISTS capture_sessions (
    session_id UUID PRIMARY KEY,
    device_id VARCHAR(128) NOT NULL,
    state VARCHAR(32) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_segment_index INTEGER
);

CREATE TABLE IF NOT EXISTS capture_segments (
    session_id UUID NOT NULL,
    segment_index INTEGER NOT NULL,
    state VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
    duration_millis BIGINT NOT NULL,
    video_uri VARCHAR(2048),
    video_sha256 CHAR(64),
    video_bytes BIGINT,
    telemetry_uri VARCHAR(2048),
    telemetry_sha256 CHAR(64),
    telemetry_bytes BIGINT,
    manifest_uri VARCHAR(2048),
    frame_count INTEGER,
    error_code VARCHAR(128),
    error_message VARCHAR(2048),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (session_id, segment_index),
    CONSTRAINT fk_capture_segment_session
        FOREIGN KEY (session_id) REFERENCES capture_sessions(session_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS capture_segments_state_idx ON capture_segments(state, updated_at);
