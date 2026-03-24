-- Aggregated per-user daily activity statistics (consent-aware analytics)
CREATE TABLE IF NOT EXISTS user_daily_activity_stats (
    user_id VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,
    activity_date DATE NOT NULL,

    active_minutes INT NOT NULL DEFAULT 0,
    session_count INT NOT NULL DEFAULT 0,
    api_request_count INT NOT NULL DEFAULT 0,

    search_count INT NOT NULL DEFAULT 0,
    nearby_search_count INT NOT NULL DEFAULT 0,
    profile_update_count INT NOT NULL DEFAULT 0,
    chat_messages_sent_count INT NOT NULL DEFAULT 0,
    chat_messages_received_count INT NOT NULL DEFAULT 0,
    transactions_created_count INT NOT NULL DEFAULT 0,
    reviews_submitted_count INT NOT NULL DEFAULT 0,

    successful_actions_count INT NOT NULL DEFAULT 0,
    failed_actions_count INT NOT NULL DEFAULT 0,

    analytics_consent BOOLEAN NOT NULL DEFAULT FALSE,
    consent_version VARCHAR(50),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (user_id, activity_date)
);

CREATE INDEX IF NOT EXISTS idx_user_daily_activity_stats_date
    ON user_daily_activity_stats(activity_date);

CREATE INDEX IF NOT EXISTS idx_user_daily_activity_stats_user_date
    ON user_daily_activity_stats(user_id, activity_date DESC);

COMMENT ON TABLE user_daily_activity_stats IS
    'Aggregated daily user activity counters for product analytics and usability improvements';

COMMENT ON COLUMN user_daily_activity_stats.analytics_consent IS
    'Snapshot flag indicating analytics processing consent at write time';
