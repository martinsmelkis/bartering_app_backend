ALTER TABLE user_premium_entitlements
    ADD COLUMN IF NOT EXISTS rc_customer_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS rc_app_user_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS rc_entitlement_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS rc_last_event_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS rc_last_event_type VARCHAR(64),
    ADD COLUMN IF NOT EXISTS last_event_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS source VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_user_premium_entitlements_rc_customer_id
    ON user_premium_entitlements(rc_customer_id);

CREATE INDEX IF NOT EXISTS idx_user_premium_entitlements_rc_app_user_id
    ON user_premium_entitlements(rc_app_user_id);

CREATE TABLE IF NOT EXISTS revenuecat_processed_events (
    id VARCHAR(36) PRIMARY KEY,
    event_id VARCHAR(128) NOT NULL UNIQUE,
    app_user_id VARCHAR(128),
    event_type VARCHAR(64),
    event_at TIMESTAMPTZ,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_revenuecat_processed_events_app_user_id
    ON revenuecat_processed_events(app_user_id);

CREATE INDEX IF NOT EXISTS idx_revenuecat_processed_events_event_at
    ON revenuecat_processed_events(event_at DESC);
