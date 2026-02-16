-- V11__device_migration_sessions.sql
-- Secure device migration support for cross-device data transfer
-- Enables users to migrate their profile to a new device while maintaining E2EE

-- ============================================================================
-- MIGRATION SESSIONS TABLE
-- ============================================================================
-- Stores ephemeral sessions for device-to-device data migration.
-- Each session is time-limited (15 minutes) and single-use.

-- For backward compatibility with old clients, user_id can be NULL initially
-- (when target device creates the session before source device sends payload)

CREATE TABLE IF NOT EXISTS device_migration_sessions (
    id VARCHAR(36) PRIMARY KEY,              -- UUID for the session
    session_code VARCHAR(10) NOT NULL,       -- 10-character user-facing code (e.g., "X7B9K2M4P1")
    user_id VARCHAR(255),                    -- NULL until source device sends payload (backward compatibility)
    
    -- Source device (the one initiating migration)
    source_device_id VARCHAR(64),            -- NULL until source device sends payload
    source_device_key_id VARCHAR(36) REFERENCES user_device_keys(id),
    source_public_key TEXT,                    -- Ephemeral ECDH public key from source
    
    -- Target device (populated when target joins)
    target_device_id VARCHAR(64),            -- Nullable until target joins
    target_device_key_id VARCHAR(36) REFERENCES user_device_keys(id),
    target_public_key TEXT,                    -- Ephemeral ECDH public key from target
    
    -- Session state
    status VARCHAR(30) NOT NULL DEFAULT 'pending', -- pending, awaiting_confirmation, transferring, completed, expired, cancelled
    
    -- Encrypted payload storage (temporary, max 5 min)
    encrypted_payload TEXT,                    -- The encrypted migration data
    payload_created_at TIMESTAMPTZ,            -- When payload was stored
    
    -- Timestamps
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,         -- 15 minutes from created_at
    completed_at TIMESTAMPTZ,                -- When session was completed
    
    -- Rate limiting and security
    attempt_count INTEGER NOT NULL DEFAULT 0, -- Failed code entry attempts
    
    CONSTRAINT unique_session_code UNIQUE(session_code),
    CONSTRAINT check_status CHECK (status IN ('pending', 'awaiting_confirmation', 'transferring', 'completed', 'expired', 'cancelled'))
);

-- Index for session code lookup (user enters the code)
CREATE INDEX idx_device_migration_sessions_code ON device_migration_sessions(session_code, status)
    WHERE status IN ('pending', 'awaiting_confirmation');

-- Index for user sessions
CREATE INDEX idx_device_migration_sessions_user ON device_migration_sessions(user_id, created_at DESC)
    WHERE user_id IS NOT NULL;

-- Index for cleanup of expired sessions
CREATE INDEX idx_device_migration_sessions_expired ON device_migration_sessions(expires_at)
    WHERE status IN ('pending', 'awaiting_confirmation', 'transferring');

-- Index for source device lookups
CREATE INDEX idx_device_migration_sessions_source ON device_migration_sessions(source_device_id, status)
    WHERE source_device_id IS NOT NULL;

-- ============================================================================
-- MIGRATION PAYLOADS TABLE (Alternative: Separate table for larger payloads)
-- ============================================================================
-- If payloads are large or you want more granular control, store separately
-- For now, we store in the sessions table to reduce complexity

-- ============================================================================
-- FUNCTIONS AND TRIGGERS
-- ============================================================================

-- Function to cleanup expired sessions (run periodically)
CREATE OR REPLACE FUNCTION cleanup_expired_device_migration_sessions(cutoff_hours INTEGER DEFAULT 24)
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM device_migration_sessions
    WHERE status IN ('pending', 'awaiting_confirmation', 'transferring')
      AND expires_at < NOW();
    
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- Function to cleanup completed/old sessions (after some retention period)
CREATE OR REPLACE FUNCTION cleanup_old_device_migration_sessions(retention_days INTEGER DEFAULT 7)
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM device_migration_sessions
    WHERE (status IN ('completed', 'expired', 'cancelled') 
           AND created_at < (NOW() - (retention_days || ' days')::INTERVAL))
       OR (payload_created_at IS NOT NULL 
           AND payload_created_at < (NOW() - INTERVAL '10 minutes'));
    
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- Function to check if user has too many active sessions (rate limiting)
CREATE OR REPLACE FUNCTION check_user_migration_session_limit(
    p_user_id VARCHAR,
    p_max_sessions INTEGER DEFAULT 3
)
RETURNS BOOLEAN AS $$
DECLARE
    active_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO active_count
    FROM device_migration_sessions
    WHERE user_id = p_user_id
      AND status IN ('pending', 'awaiting_confirmation', 'transferring')
      AND expires_at > NOW();
    
    RETURN active_count >= p_max_sessions;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- COMMENTS FOR DOCUMENTATION
-- ============================================================================

COMMENT ON TABLE device_migration_sessions IS
    'Ephemeral sessions for secure device-to-device data migration. Sessions expire after 15 minutes. user_id and source_device_id can be NULL for backward compatibility with old clients that create sessions on-demand.';

COMMENT ON COLUMN device_migration_sessions.session_code IS
    '10-character alphanumeric code that users enter to join a migration session';

COMMENT ON COLUMN device_migration_sessions.user_id IS
    'User ID. Can be NULL initially for backward compatibility (when target creates session before source sends payload)';

COMMENT ON COLUMN device_migration_sessions.status IS
    'Session lifecycle: pending -> awaiting_confirmation -> transferring -> completed';

COMMENT ON COLUMN device_migration_sessions.encrypted_payload IS
    'Temporary storage for encrypted migration data (AES-256-GCM). Auto-deleted after 10 minutes.';
