-- V2__Federation.sql
-- Database schema for federation feature

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

-- Stores information about federated servers that this instance trusts.
CREATE TABLE IF NOT EXISTS federated_servers (
    server_id VARCHAR(36) PRIMARY KEY,
    server_url VARCHAR(255) NOT NULL,
    server_name VARCHAR(255),
    public_key TEXT NOT NULL,
    trust_level VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    scope_permissions JSONB NOT NULL DEFAULT '{"users": false, "postings": false, "chat": false, "geolocation": false, "attributes": false}'::jsonb,
    federation_agreement_hash VARCHAR(255),
    last_sync_timestamp TIMESTAMPTZ,
    server_metadata JSONB,
    protocol_version VARCHAR(10) NOT NULL DEFAULT '1.0',
    is_active BOOLEAN NOT NULL DEFAULT true,
    data_retention_days INTEGER NOT NULL DEFAULT 30,
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

-- Maps remote users from federated servers to cached local representation.
CREATE TABLE IF NOT EXISTS federated_users (
    local_user_id VARCHAR(255) REFERENCES users(id) ON DELETE CASCADE,
    remote_user_id VARCHAR(255) NOT NULL,
    origin_server_id VARCHAR(36) NOT NULL REFERENCES federated_servers(server_id) ON DELETE CASCADE,
    federated_user_id VARCHAR(512) NOT NULL,
    cached_profile_data JSONB,
    public_key TEXT,
    federation_enabled BOOLEAN NOT NULL DEFAULT true,
    last_updated TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_online TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- Composite primary key
    PRIMARY KEY (remote_user_id, origin_server_id)
);

-- Indexes for efficient lookups
CREATE INDEX IF NOT EXISTS idx_federated_users_local_user_id 
    ON federated_users(local_user_id);
CREATE INDEX IF NOT EXISTS idx_federated_users_origin_server 
    ON federated_users(origin_server_id);
CREATE INDEX IF NOT EXISTS idx_federated_users_federated_id 
    ON federated_users(federated_user_id);
CREATE INDEX IF NOT EXISTS idx_federated_users_expires_at 
    ON federated_users(expires_at);
CREATE INDEX IF NOT EXISTS idx_federated_users_last_updated 
    ON federated_users(last_updated);

-- ============================================================================
-- FEDERATED POSTINGS
-- ============================================================================

-- Stores cached postings from federated servers.
CREATE TABLE IF NOT EXISTS federated_postings (
    local_posting_id VARCHAR(36) REFERENCES user_postings(id) ON DELETE CASCADE,
    remote_posting_id VARCHAR(36) NOT NULL,
    origin_server_id VARCHAR(36) NOT NULL REFERENCES federated_servers(server_id) ON DELETE CASCADE,
    remote_user_id VARCHAR(255) NOT NULL,
    cached_data JSONB NOT NULL,
    remote_url VARCHAR(512),
    is_active BOOLEAN NOT NULL DEFAULT true,
    expires_at TIMESTAMPTZ,
    last_synced TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    data_hash VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- Composite primary key
    PRIMARY KEY (remote_posting_id, origin_server_id)
);

-- Indexes for efficient queries
CREATE INDEX IF NOT EXISTS idx_federated_postings_origin_server 
    ON federated_postings(origin_server_id);
CREATE INDEX IF NOT EXISTS idx_federated_postings_remote_user 
    ON federated_postings(remote_user_id);
CREATE INDEX IF NOT EXISTS idx_federated_postings_is_active 
    ON federated_postings(is_active);
CREATE INDEX IF NOT EXISTS idx_federated_postings_expires_at 
    ON federated_postings(expires_at);
CREATE INDEX IF NOT EXISTS idx_federated_postings_last_synced 
    ON federated_postings(last_synced);

-- GIN index for JSONB cached_data for efficient querying
CREATE INDEX IF NOT EXISTS idx_federated_postings_cached_data 
    ON federated_postings USING gin(cached_data);

-- ============================================================================
-- FEDERATION AUDIT LOG
-- ============================================================================

-- Audit log for all federation-related activities.
CREATE TABLE IF NOT EXISTS federation_audit_log (
    id VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(50) NOT NULL,
    server_id VARCHAR(36) REFERENCES federated_servers(server_id) ON DELETE SET NULL,
    local_user_id VARCHAR(255),
    remote_user_id VARCHAR(255),
    action VARCHAR(50) NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    details JSONB,
    error_message TEXT,
    remote_ip VARCHAR(45),
    duration_ms BIGINT,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- Constraints
    CONSTRAINT chk_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'TIMEOUT', 'REJECTED', 'PARTIAL'))
);

-- Indexes for audit log queries
CREATE INDEX IF NOT EXISTS idx_federation_audit_log_timestamp 
    ON federation_audit_log(timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_federation_audit_log_event_type 
    ON federation_audit_log(event_type);
CREATE INDEX IF NOT EXISTS idx_federation_audit_log_server_id 
    ON federation_audit_log(server_id);
CREATE INDEX IF NOT EXISTS idx_federation_audit_log_outcome 
    ON federation_audit_log(outcome);
CREATE INDEX IF NOT EXISTS idx_federation_audit_log_local_user 
    ON federation_audit_log(local_user_id);

-- GIN index for JSONB details
CREATE INDEX IF NOT EXISTS idx_federation_audit_log_details 
    ON federation_audit_log USING gin(details);

-- ============================================================================
-- AUTOMATIC TIMESTAMP UPDATES
-- ============================================================================

-- Function to update updated_at timestamp
CREATE OR REPLACE FUNCTION update_federation_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Triggers for automatic timestamp updates
CREATE TRIGGER trigger_update_local_server_identity_timestamp
    BEFORE UPDATE ON local_server_identity
    FOR EACH ROW
    EXECUTE FUNCTION update_federation_updated_at();

CREATE TRIGGER trigger_update_federated_servers_timestamp
    BEFORE UPDATE ON federated_servers
    FOR EACH ROW
    EXECUTE FUNCTION update_federation_updated_at();

-- ============================================================================
-- COMMENTS FOR DOCUMENTATION
-- ============================================================================

COMMENT ON TABLE local_server_identity IS 'Stores this servers identity and cryptographic keys for federation';
COMMENT ON TABLE federated_servers IS 'Trusted federated servers that this instance can communicate with';
COMMENT ON TABLE federated_users IS 'Cached user profiles from federated servers';
COMMENT ON TABLE federated_postings IS 'Cached marketplace postings from federated servers';
COMMENT ON TABLE federation_audit_log IS 'Audit trail of all federation activities for security and compliance';

COMMENT ON COLUMN federated_servers.scope_permissions IS 'JSONB defining what data is shared: {users, postings, chat, geolocation, attributes}';
COMMENT ON COLUMN federated_servers.trust_level IS 'Trust level: FULL, PARTIAL, PENDING, or BLOCKED';
COMMENT ON COLUMN federated_users.federated_user_id IS 'Full federated address like user@server.com';
COMMENT ON COLUMN federation_audit_log.event_type IS 'Type of federation event: HANDSHAKE, USER_SYNC, MESSAGE_RELAY, etc.';
