CREATE TABLE IF NOT EXISTS auth_users (
    user_id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    email_normalized VARCHAR(320) NOT NULL,
    password_hash VARCHAR(200) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_login_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT auth_users_email_uk UNIQUE (email_normalized)
);

CREATE TABLE IF NOT EXISTS auth_clients (
    client_id VARCHAR(64) PRIMARY KEY,
    client_secret_hash VARCHAR(200) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE
);

-- Only the SHA-256 of the refresh token is stored, never the token: a database dump yields
-- nothing a thief can present. `replaced_by` is what makes reuse detection possible - a refresh
-- token whose row already has a successor can only have been copied.
CREATE TABLE IF NOT EXISTS auth_sessions (
    session_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    refresh_token_hash CHAR(64) NOT NULL,
    fingerprint_hash CHAR(64) NOT NULL,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    revoked_reason VARCHAR(64),
    replaced_by UUID,
    CONSTRAINT fk_auth_session_user
        FOREIGN KEY (user_id) REFERENCES auth_users(user_id) ON DELETE CASCADE,
    CONSTRAINT auth_sessions_refresh_uk UNIQUE (refresh_token_hash)
);

CREATE INDEX IF NOT EXISTS auth_sessions_user_idx ON auth_sessions(user_id, expires_at);
