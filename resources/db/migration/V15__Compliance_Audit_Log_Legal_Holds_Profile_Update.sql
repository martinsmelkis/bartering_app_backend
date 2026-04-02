-- Cross-domain compliance audit log for GDPR accountability evidence
CREATE TABLE IF NOT EXISTS compliance_audit_log (
    id BIGSERIAL PRIMARY KEY,

    actor_type VARCHAR(20) NOT NULL,
    actor_id VARCHAR(255),

    event_type VARCHAR(80) NOT NULL,
    entity_type VARCHAR(80),
    entity_id VARCHAR(255),

    purpose VARCHAR(255),
    outcome VARCHAR(20) NOT NULL,

    request_id VARCHAR(128),
    ip_hash VARCHAR(128),
    device_id_hash VARCHAR(128),

    details_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_compliance_audit_log_event_type
    ON compliance_audit_log(event_type);

CREATE INDEX IF NOT EXISTS idx_compliance_audit_log_actor_id
    ON compliance_audit_log(actor_id);

CREATE INDEX IF NOT EXISTS idx_compliance_audit_log_entity
    ON compliance_audit_log(entity_type, entity_id);

CREATE INDEX IF NOT EXISTS idx_compliance_audit_log_created_at
    ON compliance_audit_log(created_at DESC);

COMMENT ON TABLE compliance_audit_log IS
    'Append-only compliance audit evidence log (GDPR accountability, DSAR, retention, deletion, consent changes).';
    
    
CREATE TABLE IF NOT EXISTS compliance_legal_holds (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    reason TEXT NOT NULL,
    scope VARCHAR(64) NOT NULL DEFAULT 'all',
    imposed_by VARCHAR(255) NOT NULL,
    imposed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    released_at TIMESTAMPTZ,
    released_by VARCHAR(255),
    release_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_compliance_legal_holds_user_active
    ON compliance_legal_holds(user_id, is_active);

CREATE INDEX IF NOT EXISTS idx_compliance_legal_holds_expires_at
    ON compliance_legal_holds(expires_at);

COMMENT ON TABLE compliance_legal_holds IS
    'Legal holds that suspend retention/deletion/export actions for specific users until released.';


-- Add profile extension fields and remove legacy federation_enabled flag from user_profiles

ALTER TABLE user_profiles
ADD COLUMN IF NOT EXISTS self_description VARCHAR(128),
ADD COLUMN IF NOT EXISTS account_type VARCHAR(32) NOT NULL DEFAULT 'INDIVIDUAL',
ADD COLUMN IF NOT EXISTS profile_avatar_icon TEXT,
ADD COLUMN IF NOT EXISTS work_reference_image_urls JSONB NOT NULL DEFAULT '[]'::jsonb;

-- Legacy federation flag has been replaced with user_privacy_consents.federation_consent
DROP INDEX IF EXISTS idx_user_profiles_federation_enabled;
ALTER TABLE user_profiles
DROP COLUMN IF EXISTS federation_enabled;