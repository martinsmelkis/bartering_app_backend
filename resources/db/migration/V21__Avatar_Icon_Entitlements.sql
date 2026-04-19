ALTER TABLE user_purchases
    ADD CONSTRAINT uq_user_purchases_external_ref UNIQUE (external_ref);

CREATE INDEX IF NOT EXISTS idx_user_purchases_avatar_icon_entitlement
    ON user_purchases(user_id, purchase_type, status, fulfillment_ref)
    WHERE purchase_type = 'avatar_icon_unlock' AND status = 'completed';

ALTER TABLE user_profiles
    ADD COLUMN IF NOT EXISTS active_avatar_icon_id VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_user_profiles_active_avatar_icon_id
    ON user_profiles(active_avatar_icon_id);
