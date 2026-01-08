-- ============================================================================
-- Review Risk Analysis System - Device/IP/Location Pattern Detection
-- Migration V3
-- ============================================================================

-- Table: review_device_tracking
-- Tracks device fingerprints used by users for multi-account detection
CREATE TABLE IF NOT EXISTS review_device_tracking (
    id VARCHAR(36) PRIMARY KEY,
    device_fingerprint VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    ip_address VARCHAR(45),
    user_agent TEXT,
    action VARCHAR(50) NOT NULL,
    timestamp TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Indexes for efficient pattern detection
CREATE INDEX IF NOT EXISTS idx_device_fingerprint ON review_device_tracking(device_fingerprint);
CREATE INDEX IF NOT EXISTS idx_device_user_id ON review_device_tracking(user_id);
CREATE INDEX IF NOT EXISTS idx_device_user ON review_device_tracking(device_fingerprint, user_id);
CREATE INDEX IF NOT EXISTS idx_device_timestamp ON review_device_tracking(device_fingerprint, timestamp);
CREATE INDEX IF NOT EXISTS idx_review_device_tracking_timestamp ON review_device_tracking(timestamp);

-- Table: review_ip_tracking
-- Tracks IP addresses used by users for VPN/proxy detection
CREATE TABLE IF NOT EXISTS review_ip_tracking (
    id VARCHAR(36) PRIMARY KEY,
    ip_address VARCHAR(45) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    action VARCHAR(50) NOT NULL,
    timestamp TIMESTAMP NOT NULL DEFAULT NOW(),
    
    -- IP metadata for analysis
    is_vpn BOOLEAN DEFAULT FALSE,
    is_proxy BOOLEAN DEFAULT FALSE,
    is_tor BOOLEAN DEFAULT FALSE,
    is_datacenter BOOLEAN DEFAULT FALSE,
    country VARCHAR(2),
    city VARCHAR(100),
    isp VARCHAR(255)
);

-- Indexes for efficient pattern detection
CREATE INDEX IF NOT EXISTS idx_ip_address ON review_ip_tracking(ip_address);
CREATE INDEX IF NOT EXISTS idx_ip_user_id ON review_ip_tracking(user_id);
CREATE INDEX IF NOT EXISTS idx_ip_user ON review_ip_tracking(ip_address, user_id);
CREATE INDEX IF NOT EXISTS idx_ip_timestamp ON review_ip_tracking(ip_address, timestamp);
CREATE INDEX IF NOT EXISTS idx_review_ip_tracking_timestamp ON review_ip_tracking(timestamp);
CREATE INDEX IF NOT EXISTS idx_vpn_proxy ON review_ip_tracking(is_vpn, is_proxy, is_tor);

-- Table: user_location_changes
-- Tracks profile location changes for abuse detection without requiring GPS
CREATE TABLE IF NOT EXISTS user_location_changes (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    old_location GEOMETRY(POINT, 4326),
    new_location GEOMETRY(POINT, 4326) NOT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE user_location_changes IS 'Tracks user profile location changes for Review abuse detection. Identifies location hopping, coordinated movements, and fraud networks without requiring continuous GPS tracking.';

COMMENT ON COLUMN user_location_changes.old_location IS 'Previous location before change (NULL for initial location at registration)';
COMMENT ON COLUMN user_location_changes.new_location IS 'New location after update';
COMMENT ON COLUMN user_location_changes.changed_at IS 'When the location change occurred';

-- Indexes for efficient location pattern detection
CREATE INDEX IF NOT EXISTS idx_user_location_changes_user_id ON user_location_changes(user_id);
CREATE INDEX IF NOT EXISTS idx_user_location_changes_user_changed_at ON user_location_changes(user_id, changed_at);
CREATE INDEX IF NOT EXISTS idx_user_location_changes_changed_at ON user_location_changes(changed_at);

-- Table: review_risk_patterns
-- Stores detected suspicious patterns for investigation
CREATE TABLE IF NOT EXISTS review_risk_patterns (
    id VARCHAR(36) PRIMARY KEY,
    pattern_type VARCHAR(50) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    description TEXT NOT NULL,
    affected_users JSONB NOT NULL,
    evidence JSONB NOT NULL,
    detected_at TIMESTAMP NOT NULL DEFAULT NOW(),
    status VARCHAR(20) DEFAULT 'pending',
    resolved_at TIMESTAMP,
    reviewed_by VARCHAR(255),
    notes TEXT
);

-- Indexes for moderation workflow
CREATE INDEX IF NOT EXISTS idx_pattern_type ON review_risk_patterns(pattern_type);
CREATE INDEX IF NOT EXISTS idx_severity ON review_risk_patterns(severity);
CREATE INDEX IF NOT EXISTS idx_pattern_severity ON review_risk_patterns(pattern_type, severity);
CREATE INDEX IF NOT EXISTS idx_risk_status ON review_risk_patterns(status);
CREATE INDEX IF NOT EXISTS idx_status_detected ON review_risk_patterns(status, detected_at);
CREATE INDEX IF NOT EXISTS idx_risk_detected_at ON review_risk_patterns(detected_at);

-- ============================================================================
-- Optional: Add comments for documentation
-- ============================================================================

COMMENT ON TABLE review_device_tracking IS 'Tracks device fingerprints to detect multi-account abuse';
COMMENT ON TABLE review_ip_tracking IS 'Tracks IP addresses to detect VPN/proxy usage and coordinated attacks';
COMMENT ON TABLE review_risk_patterns IS 'Stores detected suspicious patterns for manual investigation';

COMMENT ON COLUMN review_ip_tracking.is_vpn IS 'IP identified as VPN endpoint';
COMMENT ON COLUMN review_ip_tracking.is_proxy IS 'IP identified as proxy server';
COMMENT ON COLUMN review_ip_tracking.is_tor IS 'IP identified as Tor exit node';
COMMENT ON COLUMN review_ip_tracking.is_datacenter IS 'IP belongs to data center (AWS, GCP, etc.)';

COMMENT ON COLUMN review_risk_patterns.status IS 'pending, investigating, resolved, false_positive';
COMMENT ON COLUMN review_risk_patterns.severity IS 'info, low, medium, high, critical';

-- ============================================================================
-- Data retention policy
-- ============================================================================
-- Auto-delete old tracking data after 90 days
CREATE OR REPLACE FUNCTION cleanup_old_tracking_data() RETURNS void AS $$
BEGIN
     DELETE FROM review_device_tracking WHERE timestamp < NOW() - INTERVAL '90 days';
     DELETE FROM review_ip_tracking WHERE timestamp < NOW() - INTERVAL '90 days';
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- Migration complete
-- ============================================================================
