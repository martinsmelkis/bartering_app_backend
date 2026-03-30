-- Add cumulative total activity counter to user presence.
ALTER TABLE user_presence
    ADD COLUMN IF NOT EXISTS total_activity_count BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN user_presence.total_activity_count IS
    'Cumulative number of user activity actions recorded for reward calculations.';

-- Track per-user reward checkpoints to ensure idempotent coin grants by activity count.
CREATE TABLE IF NOT EXISTS user_activity_reward_progress (
    user_id VARCHAR(50) PRIMARY KEY REFERENCES user_registration_data(id) ON DELETE CASCADE,
    rewarded_activity_count BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_activity_reward_progress_updated_at
    ON user_activity_reward_progress(updated_at DESC);

COMMENT ON TABLE user_activity_reward_progress IS
    'Stores rewarded activity-count progress used to grant wallet bonuses exactly once per threshold.';

COMMENT ON COLUMN user_activity_reward_progress.user_id IS
    'User id linked to user_registration_data.id.';

COMMENT ON COLUMN user_activity_reward_progress.rewarded_activity_count IS
    'Number of total activity actions that have already been converted into wallet rewards.';
