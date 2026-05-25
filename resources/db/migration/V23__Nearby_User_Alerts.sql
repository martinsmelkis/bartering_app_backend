CREATE TABLE IF NOT EXISTS nearby_user_alerts (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    radius_meters DOUBLE PRECISION NOT NULL DEFAULT 10000,
    min_user_count INTEGER NOT NULL DEFAULT 5,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_checked_at TIMESTAMPTZ,
    last_notified_at TIMESTAMPTZ,
    last_nearby_user_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_nearby_user_alerts_latitude CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT chk_nearby_user_alerts_longitude CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT chk_nearby_user_alerts_radius CHECK (radius_meters BETWEEN 100 AND 200000),
    CONSTRAINT chk_nearby_user_alerts_min_user_count CHECK (min_user_count BETWEEN 1 AND 100)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_nearby_user_alerts_user_id
    ON nearby_user_alerts(user_id);

CREATE INDEX IF NOT EXISTS idx_nearby_user_alerts_enabled_checked
    ON nearby_user_alerts(enabled, last_checked_at)
    WHERE enabled = TRUE;

COMMENT ON TABLE nearby_user_alerts IS
    'User opt-in alerts for being notified when a saved nearby-search area reaches a minimum number of users';

COMMENT ON COLUMN nearby_user_alerts.min_user_count IS
    'Minimum nearby user count required before sending a one-shot notification';
