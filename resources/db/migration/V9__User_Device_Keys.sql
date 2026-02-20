-- V10__User_Device_Keys.sql
-- Multi-device support: Store multiple public keys per user for authentication across devices

-- ============================================================================
-- USER DEVICE KEYS TABLE
-- ============================================================================
-- Stores device-specific public keys for multi-device authentication.
-- Each device gets its own keypair, allowing users to use the app on multiple
-- devices (phone, tablet, etc.) simultaneously.

CREATE TABLE IF NOT EXISTS user_device_keys (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,
    device_id VARCHAR(64) NOT NULL,          -- Client-generated unique device identifier
    public_key TEXT NOT NULL,                -- Device's ECDSA public key (Base64)
    device_name VARCHAR(100),                -- User-friendly name (e.g., "iPhone 14", "Galaxy S23")
    device_type VARCHAR(20),                 -- Device category: mobile, tablet, desktop, web
    platform VARCHAR(20),                    -- OS: ios, android, windows, macos, linux, web
    is_active BOOLEAN NOT NULL DEFAULT TRUE, -- Whether this device key is currently valid
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deactivated_at TIMESTAMPTZ,              -- When the device was revoked (if applicable)
    deactivated_reason VARCHAR(50),            -- Reason: user_revoked, logout, security_concern, etc.
    
    -- Ensure a device can only be registered once per user
    CONSTRAINT unique_device_per_user UNIQUE(user_id, device_id),
    -- Limit device types to known values
    CONSTRAINT check_device_type CHECK (device_type IN ('mobile', 'tablet', 'desktop', 'web', 'wearable', 'other')),
    -- Limit platforms to known values  
    CONSTRAINT check_platform CHECK (platform IN ('ios', 'android', 'windows', 'macos', 'linux', 'web', 'other'))
);

-- Index for fast key lookup during authentication (device_id is provided)
CREATE INDEX idx_device_keys_user_device ON user_device_keys(user_id, device_id, is_active);

-- Index for fetching all active keys for a user (fallback when device_id not provided)
CREATE INDEX idx_device_keys_user_active ON user_device_keys(user_id, is_active, last_used_at DESC);

-- Index for cleanup operations (inactive devices)
CREATE INDEX idx_device_keys_inactive ON user_device_keys(is_active, deactivated_at) WHERE is_active = FALSE;

-- Index for finding devices by their public key (rare but useful for debugging)
CREATE INDEX idx_device_keys_pubkey ON user_device_keys(public_key) WHERE is_active = TRUE;

-- ============================================================================
-- DEVICE ACTIVITY LOG (Optional but recommended for security auditing)
-- ============================================================================
CREATE TABLE IF NOT EXISTS user_device_activity (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,
    device_id VARCHAR(64) NOT NULL,
    activity_type VARCHAR(50) NOT NULL,      -- login, logout, revoke, register, migrate_from, migrate_to
    ip_address INET,                           -- Client IP (if available)
    user_agent TEXT,                           -- Device user agent string
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    CONSTRAINT check_activity_type CHECK (activity_type IN (
        'register', 'login', 'logout', 'revoke', 'reactivate', 
        'migrate_from', 'migrate_to', 'key_rotation'
    ))
);

CREATE INDEX idx_device_activity_user ON user_device_activity(user_id, timestamp DESC);
CREATE INDEX idx_device_activity_device ON user_device_activity(device_id, timestamp DESC);

-- ============================================================================
-- FUNCTIONS AND TRIGGERS
-- ============================================================================

-- Auto-update last_used_at when a device authenticates
-- This is called by the application, not a trigger, for performance reasons

-- Function to cleanup old inactive device records (run periodically)
CREATE OR REPLACE FUNCTION cleanup_inactive_devices(cutoff_days INTEGER DEFAULT 90)
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM user_device_keys 
    WHERE is_active = FALSE 
      AND deactivated_at < (NOW() - (cutoff_days || ' days')::INTERVAL);
    
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- Function to get device count for a user (useful for rate limiting)
CREATE OR REPLACE FUNCTION get_active_device_count(p_user_id VARCHAR)
RETURNS INTEGER AS $$
DECLARE
    device_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO device_count
    FROM user_device_keys
    WHERE user_id = p_user_id AND is_active = TRUE;
    
    RETURN device_count;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- MIGRATION: Copy existing public keys from user_registration_data
-- ============================================================================
-- During migration, we treat the existing public_key in user_registration_data
-- as the "primary" or "legacy" device. This ensures backward compatibility.

-- Note: This migration only creates the table structure.
-- The actual population of device keys happens when:
-- 1. Existing users authenticate (device key is auto-created)
-- 2. New devices register via the migration framework or fresh install

-- ============================================================================
-- COMMENTS FOR DOCUMENTATION
-- ============================================================================

COMMENT ON TABLE user_device_keys IS 
    'Stores device-specific public keys for multi-device authentication. Each device gets its own ECDSA keypair.';

COMMENT ON COLUMN user_device_keys.device_id IS 
    'Client-generated unique identifier for the device. Should be persistent across app reinstalls where possible.';

COMMENT ON COLUMN user_device_keys.public_key IS 
    'The device ECDSA public key in Base64 format. Used to verify request signatures from this device.';

COMMENT ON COLUMN user_device_keys.is_active IS 
    'If FALSE, this device key cannot be used for authentication. Set to FALSE on logout, revoke, or security concern.';

COMMENT ON TABLE user_device_activity IS 
    'Audit log for device-related activities (registrations, logins, revocations, migrations).';