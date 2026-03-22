-- Dedicated table for GDPR/privacy consent state
CREATE TABLE IF NOT EXISTS user_privacy_consents (
    user_id VARCHAR(255) PRIMARY KEY REFERENCES user_registration_data(id) ON DELETE CASCADE,

    location_consent BOOLEAN NOT NULL DEFAULT FALSE,
    ai_processing_consent BOOLEAN NOT NULL DEFAULT TRUE,
    analytics_cookies_consent BOOLEAN,
    federation_consent BOOLEAN NOT NULL DEFAULT FALSE,

    consent_updated_at TIMESTAMPTZ,
    privacy_policy_version VARCHAR(50),
    privacy_policy_accepted_at TIMESTAMPTZ
);

COMMENT ON TABLE user_privacy_consents IS
    'Dedicated GDPR/privacy consent state per user';

COMMENT ON COLUMN user_privacy_consents.federation_consent IS
    'Explicit user consent for sharing profile data through federation';

COMMENT ON COLUMN user_privacy_consents.consent_updated_at IS
    'Timestamp of the most recent consent change for accountability';

COMMENT ON COLUMN user_privacy_consents.privacy_policy_version IS
    'Version identifier of privacy policy accepted by the user';

COMMENT ON COLUMN user_privacy_consents.privacy_policy_accepted_at IS
    'Timestamp when user accepted the referenced privacy policy version';
