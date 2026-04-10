-- Make user_daily_activity_stats truly daily by keying rows per anonymized user + date.
-- Also preserves existing index strategy and removes old PK on anonymized_user_id.

ALTER TABLE user_daily_activity_stats
    DROP CONSTRAINT IF EXISTS user_daily_activity_stats_pkey;

ALTER TABLE user_daily_activity_stats
    ADD CONSTRAINT user_daily_activity_stats_pkey
    PRIMARY KEY (anonymized_user_id, activity_date);

CREATE INDEX IF NOT EXISTS idx_user_daily_activity_stats_anon_user_date
    ON user_daily_activity_stats(anonymized_user_id, activity_date DESC);

ALTER TABLE user_daily_activity_stats
    DROP COLUMN IF EXISTS failed_actions_count;

ALTER TABLE user_daily_activity_stats
    ADD COLUMN IF NOT EXISTS average_response_time BIGINT NOT NULL DEFAULT 0;

UPDATE user_daily_activity_stats
SET average_response_time = CASE
    WHEN response_time_count > 0 THEN (total_response_time_ms / response_time_count)
    ELSE 0
END;

ALTER TABLE user_daily_activity_stats
    DROP COLUMN IF EXISTS response_time_count;

ALTER TABLE user_daily_activity_stats
    DROP COLUMN IF EXISTS min_response_time_ms;

ALTER TABLE user_daily_activity_stats
    DROP COLUMN IF EXISTS max_response_time_ms;
