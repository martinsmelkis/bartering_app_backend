-- V10__Device_Migration_Sessions.sql
-- Unified migration system supporting both device-to-device and email-based recovery
-- ============================================================================

-- ============================================================================
-- UNIFIED MIGRATION SESSIONS TABLE
-- ============================================================================
-- Supports two modes:
-- 1. 'device_to_device': Both devices functional, direct transfer via session code
-- 2. 'email_recovery': Source device broken/lost, recovery via email code

CREATE TABLE IF NOT EXISTS device_migration_sessions (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,

    -- Migration type
    type VARCHAR(30) NOT NULL DEFAULT 'device_to_device',

    -- Session identification
    session_code VARCHAR(10),              -- For device-to-device (NULL for email recovery)
    recovery_code_hash VARCHAR(255),        -- For email recovery (NULL for device-to-device)

    -- Source device info (NULL for email recovery when device is broken)
    source_device_id VARCHAR(64),
    source_device_key_id VARCHAR(36) REFERENCES user_device_keys(id),
    source_public_key TEXT,

    -- Target/New device info
    target_device_id VARCHAR(64),           -- For device-to-device
    target_device_key_id VARCHAR(36) REFERENCES user_device_keys(id),
    target_public_key TEXT,
    new_device_id VARCHAR(64),              -- For email recovery (alias for clarity)
    new_device_public_key TEXT,

    -- For email recovery: contact info
    contact_email VARCHAR(255),

    -- Session state
    -- device_to_device: pending -> awaiting_confirmation -> transferring -> completed
    -- email_recovery: pending -> verified -> completed
    status VARCHAR(30) NOT NULL DEFAULT 'pending',

    -- Data transfer (device-to-device only)
    encrypted_payload TEXT,
    payload_created_at TIMESTAMPTZ,

    -- Security tracking
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 5,
    ip_address VARCHAR(45),

    -- Timestamps
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,         -- 15 min for device, 24 hours for recovery
    verified_at TIMESTAMPTZ,                 -- Email code verified or target confirmed
    completed_at TIMESTAMPTZ,

    CONSTRAINT check_migration_type CHECK (type IN ('device_to_device', 'email_recovery')),
    CONSTRAINT check_migration_status CHECK (status IN (
        'pending', 'awaiting_confirmation', 'transferring', 'verified', 'completed', 'expired', 'cancelled', 'failed'
    )),
    -- Either session_code or recovery_code_hash should be set based on type
    CONSTRAINT check_code_presence CHECK (
        (type = 'device_to_device' AND session_code IS NOT NULL) OR
        (type = 'email_recovery' AND recovery_code_hash IS NOT NULL)
    )
);

-- Indexes for common queries
CREATE INDEX idx_device_migration_sessions_user ON device_migration_sessions(user_id, created_at DESC);
CREATE INDEX idx_device_migration_sessions_code ON device_migration_sessions(session_code, status)
    WHERE type = 'device_to_device' AND status IN ('pending', 'awaiting_confirmation');
CREATE INDEX idx_device_migration_sessions_expired ON device_migration_sessions(expires_at)
    WHERE status IN ('pending', 'awaiting_confirmation', 'transferring', 'verified');
CREATE INDEX idx_device_migration_sessions_type ON device_migration_sessions(type, status);

-- ============================================================================
-- UNIFIED MIGRATION AUDIT LOG
-- ============================================================================
-- Tracks all migration and recovery events

CREATE TABLE IF NOT EXISTS device_migration_audit_log (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(50) NOT NULL,       -- 'initiated', 'code_sent', 'verified', 'completed', 'failed', etc.
    migration_type VARCHAR(30) NOT NULL,   -- 'device_to_device' or 'email_recovery'
    user_id VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,
    session_id VARCHAR(36),  -- No FK constraint to avoid timing issues
    ip_address VARCHAR(45),
    details JSONB,                          -- Flexible metadata
    risk_score INTEGER DEFAULT 0,           -- 0-100 risk assessment
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_migration_audit_user ON device_migration_audit_log(user_id, created_at DESC);
CREATE INDEX idx_migration_audit_session ON device_migration_audit_log(session_id, created_at DESC);
CREATE INDEX idx_migration_audit_ip ON device_migration_audit_log(ip_address, created_at DESC)
    WHERE event_type IN ('failed', 'initiated');

-- ============================================================================
-- CLEANUP FUNCTION
-- ============================================================================

CREATE OR REPLACE FUNCTION cleanup_expired_device_migration_sessions()
RETURNS INTEGER AS $$
DECLARE
    expired_count INTEGER;
    deleted_count INTEGER;
BEGIN
    -- Mark expired sessions
    UPDATE device_migration_sessions
    SET status = 'expired'
    WHERE status IN ('pending', 'awaiting_confirmation', 'transferring', 'verified')
      AND expires_at < NOW();

    GET DIAGNOSTICS expired_count = ROW_COUNT;

    -- Delete old completed/expired/cancelled sessions (keep 30 days for audit)
    DELETE FROM device_migration_sessions
    WHERE status IN ('completed', 'expired', 'cancelled', 'failed')
      AND created_at < NOW() - INTERVAL '30 days';

    GET DIAGNOSTICS deleted_count = ROW_COUNT;

    -- Clean up old audit logs (keep 90 days)
    DELETE FROM device_migration_audit_log
    WHERE created_at < NOW() - INTERVAL '90 days';

    RETURN expired_count + deleted_count;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- COMMENTS
-- ============================================================================

COMMENT ON TABLE device_migration_sessions IS
    'Unified migration sessions supporting device-to-device transfer and email-based recovery';

COMMENT ON COLUMN device_migration_sessions.type IS
    'device_to_device: both devices functional; email_recovery: source device broken/lost';

COMMENT ON COLUMN device_migration_sessions.status IS
    'pending -> awaiting_confirmation/verified -> transferring -> completed';

COMMENT ON TABLE device_migration_audit_log IS
    'Audit trail for all migration and recovery attempts';
