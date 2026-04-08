CREATE TABLE IF NOT EXISTS user_purchases (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,
    purchase_type VARCHAR(50) NOT NULL,
    status VARCHAR(32) NOT NULL,
    currency VARCHAR(10),
    fiat_amount_minor BIGINT,
    coin_amount BIGINT,
    external_ref VARCHAR(255),
    metadata_json TEXT,
    fulfillment_ref VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_purchases_user_id ON user_purchases(user_id);
CREATE INDEX IF NOT EXISTS idx_user_purchases_type ON user_purchases(purchase_type);
CREATE INDEX IF NOT EXISTS idx_user_purchases_status ON user_purchases(status);
CREATE INDEX IF NOT EXISTS idx_user_purchases_external_ref ON user_purchases(external_ref);
CREATE INDEX IF NOT EXISTS idx_user_purchases_created_at ON user_purchases(created_at DESC);

CREATE TABLE IF NOT EXISTS user_premium_entitlements (
    user_id VARCHAR(50) PRIMARY KEY REFERENCES user_registration_data(id) ON DELETE CASCADE,
    is_premium BOOLEAN NOT NULL DEFAULT FALSE,
    is_lifetime BOOLEAN NOT NULL DEFAULT FALSE,
    granted_by_purchase_id VARCHAR(36) REFERENCES user_purchases(id) ON DELETE SET NULL,
    granted_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ;
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_premium_entitlements_expires_at
    ON user_premium_entitlements(expires_at);

COMMENT ON COLUMN user_premium_entitlements.expires_at IS
    'Optional expiration timestamp for time-limited entitlement/visibility boost. NULL means non-expiring.';

COMMENT ON TABLE user_purchases IS
    'Purchase records for premium and coin/boost product purchases.';

COMMENT ON TABLE user_premium_entitlements IS
    'Current premium entitlement state per user (orthogonal to account_type).';
