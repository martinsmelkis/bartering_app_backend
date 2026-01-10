-- V6__Federated_Servers.sql
-- Complete federation feature database schema
-- Combines best features from all versions with full functionality
-- Includes: server infrastructure, user federation support, sync tracking, and metadata

-- ============================================================================
-- LOCAL SERVER IDENTITY
-- ============================================================================

-- Stores the identity and cryptographic keys for THIS server instance.
-- This is a singleton table (should only have one row).
CREATE TABLE IF NOT EXISTS local_server_identity (
    server_id VARCHAR(36) PRIMARY KEY,
    server_url VARCHAR(255) NOT NULL,
    server_name VARCHAR(255) NOT NULL,
    public_key TEXT NOT NULL,
    private_key TEXT NOT NULL, -- Encrypted at rest, never exposed via API
    key_algorithm VARCHAR(20) NOT NULL DEFAULT 'RSA',
    key_size INTEGER NOT NULL DEFAULT 2048,
    protocol_version VARCHAR(10) NOT NULL DEFAULT '1.0',
    admin_contact VARCHAR(255),
    description TEXT,
    location_hint VARCHAR(255),
    key_generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    key_rotation_due TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Index for efficient lookups
CREATE INDEX IF NOT EXISTS idx_local_server_identity_server_id 
    ON local_server_identity(server_id);

-- ============================================================================
-- FEDERATED SERVERS
-- ============================================================================

-- Stores trusted federated servers that this instance can communicate with.
CREATE TABLE IF NOT EXISTS federated_servers (
    server_id VARCHAR(36) PRIMARY KEY,
    
    -- Server identity (UNIQUE prevents duplicate registrations)
    server_url VARCHAR(255) NOT NULL UNIQUE,
    server_name VARCHAR(255), -- Nullable to allow handshake initiation
    public_key TEXT NOT NULL,
    
    -- Trust & permissions
    trust_level VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    scope_permissions JSONB NOT NULL DEFAULT '{"users": false, "postings": false, "chat": false, "geolocation": false, "attributes": false}'::jsonb,
    
    -- Federation agreement
    federation_agreement_hash VARCHAR(255), -- Support longer hashes/signatures
    
    -- Sync tracking
    last_sync_timestamp TIMESTAMPTZ,
    
    -- Flexible metadata storage for custom federation data
    server_metadata JSONB,
    
    -- Protocol & lifecycle
    protocol_version VARCHAR(10) NOT NULL DEFAULT '1.0',
    is_active BOOLEAN NOT NULL DEFAULT true,
    
    -- Data management policy (how long to cache data from this server)
    data_retention_days INTEGER NOT NULL DEFAULT 30,
    
    -- Timestamps
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- Constraints
    CONSTRAINT chk_trust_level CHECK (trust_level IN ('FULL', 'PARTIAL', 'PENDING', 'BLOCKED'))
);

-- Indexes for efficient queries
CREATE INDEX IF NOT EXISTS idx_federated_servers_trust_level
    ON federated_servers(trust_level);
CREATE INDEX IF NOT EXISTS idx_federated_servers_is_active
    ON federated_servers(is_active);
CREATE INDEX IF NOT EXISTS idx_federated_servers_url
    ON federated_servers(server_url);

-- ============================================================================
-- FEDERATED USERS
-- ============================================================================

-- Maps remote users from federated servers to their cached local representation.
CREATE TABLE IF NOT EXISTS federated_users (
    local_user_id VARCHAR(255) REFERENCES user_registration_data(id) ON DELETE CASCADE,
    remote_user_id VARCHAR(255) NOT NULL,
    origin_server_id VARCHAR(36) NOT NULL REFERENCES federated_servers(server_id) ON DELETE CASCADE,
    federated_user_id VARCHAR(512) NOT NULL UNIQUE, -- Globally unique (e.g., user@server.com)
    cached_profile_data JSONB,
    public_key TEXT,
    federation_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_updated TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_online TIMESTAMPTZ,
    expires_at TIMESTAMPTZ, -- When cached data expires (based on data_retention_days)
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    PRIMARY KEY (remote_user_id, origin_server_id)
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_federated_users_origin_server 
    ON federated_users(origin_server_id);
CREATE INDEX IF NOT EXISTS idx_federated_users_federated_user_id 
    ON federated_users(federated_user_id);
CREATE INDEX IF NOT EXISTS idx_federated_users_last_updated 
    ON federated_users(last_updated);
CREATE INDEX IF NOT EXISTS idx_federated_users_expires_at
    ON federated_users(expires_at);

-- ============================================================================
-- FEDERATED POSTINGS
-- ============================================================================

-- Cached postings from federated servers.
CREATE TABLE IF NOT EXISTS federated_postings (
    local_posting_id VARCHAR(36) REFERENCES user_postings(id) ON DELETE CASCADE,
    remote_posting_id VARCHAR(36) NOT NULL,
    origin_server_id VARCHAR(36) NOT NULL REFERENCES federated_servers(server_id) ON DELETE CASCADE,
    remote_user_id VARCHAR(255) NOT NULL,
    cached_data JSONB NOT NULL,
    remote_url VARCHAR(512),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    expires_at TIMESTAMPTZ, -- When cached data expires
    last_synced TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    data_hash VARCHAR(64), -- SHA-256 hash for change detection
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    PRIMARY KEY (remote_posting_id, origin_server_id)
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_federated_postings_origin_server 
    ON federated_postings(origin_server_id);
CREATE INDEX IF NOT EXISTS idx_federated_postings_is_active 
    ON federated_postings(is_active);
CREATE INDEX IF NOT EXISTS idx_federated_postings_expires_at 
    ON federated_postings(expires_at);
CREATE INDEX IF NOT EXISTS idx_federated_postings_last_synced 
    ON federated_postings(last_synced);

-- ============================================================================
-- FEDERATION AUDIT LOG
-- ============================================================================

-- Audit trail of all federation activities for security and compliance.
CREATE TABLE IF NOT EXISTS federation_audit_log (
    id SERIAL PRIMARY KEY,
    event_type VARCHAR(50) NOT NULL,
    server_id VARCHAR(36), -- NULL for local events
    action TEXT NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    details JSONB, -- Flexible storage for event-specific data
    error_message TEXT,
    duration_ms BIGINT, -- Performance tracking
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    CONSTRAINT chk_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'PARTIAL'))
);

-- Indexes for efficient audit queries
CREATE INDEX IF NOT EXISTS idx_federation_audit_log_server_id 
    ON federation_audit_log(server_id);
CREATE INDEX IF NOT EXISTS idx_federation_audit_log_event_type 
    ON federation_audit_log(event_type);
CREATE INDEX IF NOT EXISTS idx_federation_audit_log_created_at 
    ON federation_audit_log(created_at);
CREATE INDEX IF NOT EXISTS idx_federation_audit_log_outcome 
    ON federation_audit_log(outcome);

-- ============================================================================
-- USER PROFILE FEDERATION SUPPORT
-- ============================================================================

-- Add federation_enabled flag to user_profiles
-- This allows users to opt-in/out of federation (default: opted in)
ALTER TABLE user_profiles
ADD COLUMN IF NOT EXISTS federation_enabled BOOLEAN DEFAULT TRUE;

-- Add index for efficient federation queries (partial index for better performance)
CREATE INDEX IF NOT EXISTS idx_user_profiles_federation_enabled 
ON user_profiles(federation_enabled) 
WHERE federation_enabled = TRUE;

-- Add updated_at column for tracking profile changes (enables incremental sync)
ALTER TABLE user_profiles
ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW();

-- Create trigger to automatically update updated_at on profile changes
CREATE OR REPLACE FUNCTION update_user_profiles_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_user_profiles_updated_at ON user_profiles;
CREATE TRIGGER trigger_user_profiles_updated_at
    BEFORE UPDATE ON user_profiles
    FOR EACH ROW
    EXECUTE FUNCTION update_user_profiles_updated_at();

-- ============================================================================
-- TRIGGERS FOR FEDERATION TABLES
-- ============================================================================

-- Function to automatically update updated_at timestamp for federation infrastructure tables
CREATE OR REPLACE FUNCTION update_federation_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger for local_server_identity (tracks when server config changes)
CREATE TRIGGER trigger_update_local_server_identity_timestamp
    BEFORE UPDATE ON local_server_identity
    FOR EACH ROW
    EXECUTE FUNCTION update_federation_updated_at();

-- Trigger for federated_servers (tracks when remote server configs change)
CREATE TRIGGER trigger_update_federated_servers_timestamp
    BEFORE UPDATE ON federated_servers
    FOR EACH ROW
    EXECUTE FUNCTION update_federation_updated_at();

-- ============================================================================
-- COMMENTS FOR DOCUMENTATION
-- ============================================================================

-- Tables
COMMENT ON TABLE local_server_identity IS 'Stores this servers identity and cryptographic keys for federation';
COMMENT ON TABLE federated_servers IS 'Trusted federated servers that this instance can communicate with';
COMMENT ON TABLE federated_users IS 'Cached user profiles from federated servers';
COMMENT ON TABLE federated_postings IS 'Cached marketplace postings from federated servers';
COMMENT ON TABLE federation_audit_log IS 'Audit trail of all federation activities for security and compliance';

-- Key columns
COMMENT ON COLUMN federated_servers.server_url IS 'Base URL of federated server (e.g., https://barter.example.com)';
COMMENT ON COLUMN federated_servers.scope_permissions IS 'JSONB defining what data is shared: {users, postings, chat, geolocation, attributes}';
COMMENT ON COLUMN federated_servers.trust_level IS 'Trust level: FULL (trusted), PARTIAL (limited), PENDING (handshake), BLOCKED (rejected)';
COMMENT ON COLUMN federated_servers.server_metadata IS 'Flexible JSONB storage for custom federation metadata (capabilities, rate limits, etc.)';
COMMENT ON COLUMN federated_servers.data_retention_days IS 'How many days to cache data from this server before requiring refresh';
COMMENT ON COLUMN federated_users.federated_user_id IS 'Full federated address like user@server.com (globally unique)';
COMMENT ON COLUMN federated_users.expires_at IS 'When cached profile data expires based on origin servers data_retention_days';
COMMENT ON COLUMN federation_audit_log.event_type IS 'Type of federation event: HANDSHAKE, USER_SYNC, MESSAGE_RELAY, POSTING_SEARCH, etc.';
COMMENT ON COLUMN user_profiles.federation_enabled IS 'Whether this user has opted into federation (cross-server discovery)';
COMMENT ON COLUMN user_profiles.updated_at IS 'Timestamp of last profile update, used for incremental federation sync';

-- ============================================================================
-- ADDITIONAL UTILITIES (OPTIONAL VIEWS)
-- ============================================================================

-- View for active federated servers (convenience)
CREATE OR REPLACE VIEW active_federated_servers AS
SELECT 
    server_id,
    server_name,
    server_url,
    trust_level,
    scope_permissions,
    last_sync_timestamp,
    data_retention_days
FROM federated_servers
WHERE is_active = true
  AND trust_level != 'BLOCKED'
ORDER BY trust_level DESC, server_name;

COMMENT ON VIEW active_federated_servers IS 'Convenience view showing all active non-blocked federated servers';

-- View for expired cached data (for cleanup jobs)
CREATE OR REPLACE VIEW expired_federation_cache AS
SELECT 
    'federated_users' as table_name,
    federated_user_id as item_id,
    origin_server_id,
    expires_at
FROM federated_users
WHERE expires_at IS NOT NULL 
  AND expires_at < NOW()
UNION ALL
SELECT 
    'federated_postings' as table_name,
    remote_posting_id as item_id,
    origin_server_id,
    expires_at
FROM federated_postings
WHERE expires_at IS NOT NULL 
  AND expires_at < NOW();

COMMENT ON VIEW expired_federation_cache IS 'Shows all expired cached federation data that should be cleaned up or refreshed';
