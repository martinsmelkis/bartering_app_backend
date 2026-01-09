-- Chat Analytics Tables for Response Time Tracking
-- This migration adds tables to track message response times for reputation badges

-- Table to track conversation response times
CREATE TABLE IF NOT EXISTS chat_response_times (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,
    conversation_partner_id VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,
    message_received_at TIMESTAMPTZ NOT NULL,
    response_sent_at TIMESTAMPTZ NOT NULL,
    response_time_hours DECIMAL(10, 2) NOT NULL, -- Calculated response time in hours
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for efficient queries
CREATE INDEX idx_chat_response_times_user ON chat_response_times(user_id);
CREATE INDEX idx_chat_response_times_timestamp ON chat_response_times(created_at);
CREATE INDEX idx_chat_response_times_user_partner ON chat_response_times(user_id, conversation_partner_id);

-- Comments for documentation
COMMENT ON TABLE chat_response_times IS 'Tracks response times between users for calculating average response time badges';
COMMENT ON COLUMN chat_response_times.response_time_hours IS 'Time between receiving a message and responding, in hours';
