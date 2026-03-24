-- Aggregated daily activity statistics (consent-aware analytics)
-- Default identity key is anonymized_user_id; real user_id link is optional.
CREATE TABLE IF NOT EXISTS user_daily_activity_stats (
    anonymized_user_id VARCHAR(64) NOT NULL,
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

    searched_keywords JSONB NOT NULL DEFAULT '{}'::jsonb,
    keyword_response_times JSONB NOT NULL DEFAULT '{}'::jsonb,
    response_time_count BIGINT NOT NULL DEFAULT 0,
    total_response_time_ms BIGINT NOT NULL DEFAULT 0,
    min_response_time_ms INT,
    max_response_time_ms INT,

    analytics_consent BOOLEAN NOT NULL DEFAULT FALSE,
    consent_version VARCHAR(50),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (anonymized_user_id)
);

CREATE INDEX IF NOT EXISTS idx_user_daily_activity_stats_date
    ON user_daily_activity_stats(activity_date);

CREATE INDEX IF NOT EXISTS idx_user_daily_activity_stats_anon_user_date
    ON user_daily_activity_stats(anonymized_user_id, activity_date DESC);

COMMENT ON TABLE user_daily_activity_stats IS
    'Aggregated daily user activity counters for product analytics and usability improvements';

COMMENT ON COLUMN user_daily_activity_stats.anonymized_user_id IS
    'Pseudonymized user identifier used as default analytics identity key';

COMMENT ON COLUMN user_daily_activity_stats.searched_keywords IS
    'JSON object of normalized keyword -> count for this anonymized user/day';

COMMENT ON COLUMN user_daily_activity_stats.keyword_response_times IS
    'JSON object of keyword -> {count,totalMs,minMs,maxMs} response-time aggregates';

COMMENT ON COLUMN user_daily_activity_stats.total_response_time_ms IS
    'Sum of response times used to compute average response time';

COMMENT ON COLUMN user_daily_activity_stats.analytics_consent IS
    'Snapshot flag indicating analytics processing consent at write time';
