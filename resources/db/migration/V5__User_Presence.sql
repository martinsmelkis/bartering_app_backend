-- User Presence Tracking
-- Tracks user activity for online/offline status detection

CREATE TABLE IF NOT EXISTS user_presence (
    user_id VARCHAR(255) PRIMARY KEY REFERENCES user_registration_data(id) ON DELETE CASCADE,
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_activity_type VARCHAR(50), -- 'browsing', 'searching', 'chatting', 'matching', etc.
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Index for fast online status queries
CREATE INDEX idx_user_presence_activity ON user_presence(last_activity_at);

-- Index for activity type analytics (optional)
CREATE INDEX idx_user_presence_type ON user_presence(last_activity_type);

-- Function to clean up old presence data (optional maintenance)
CREATE OR REPLACE FUNCTION cleanup_old_presence() RETURNS void AS $$
BEGIN
    -- Delete presence records for users who haven't been active in 30 days
    DELETE FROM user_presence 
    WHERE last_activity_at < (NOW() - INTERVAL '30 days');
END;
$$ LANGUAGE plpgsql;

-- Comments for documentation
COMMENT ON TABLE user_presence IS 'Tracks user last activity time for presence detection (online/offline status)';
COMMENT ON COLUMN user_presence.last_activity_at IS 'Timestamp of last user activity (API call, message, etc.)';
COMMENT ON COLUMN user_presence.last_activity_type IS 'Type of last activity for analytics (browsing, searching, chatting, etc.)';
