-- Migration V7: User Reports System
-- Description: Adds comprehensive user reporting functionality for abuse, spam, harassment, etc.
-- Author: System
-- Date: 2026-01-11

-- Create user_reports table for tracking user reports
CREATE TABLE IF NOT EXISTS user_reports (
    id VARCHAR(36) PRIMARY KEY,
    reporter_user_id VARCHAR(255) NOT NULL,
    reported_user_id VARCHAR(255) NOT NULL,
    report_reason VARCHAR(50) NOT NULL, -- SPAM, HARASSMENT, INAPPROPRIATE_CONTENT, SCAM, FAKE_PROFILE, IMPERSONATION, THREATENING_BEHAVIOR, OTHER
    description TEXT,
    context_type VARCHAR(50), -- PROFILE, POSTING, CHAT, REVIEW, GENERAL
    context_id VARCHAR(36), -- ID of the specific content being reported
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING', -- PENDING, UNDER_REVIEW, REVIEWED, DISMISSED, ACTION_TAKEN
    reported_at TIMESTAMP NOT NULL DEFAULT NOW(),
    reviewed_at TIMESTAMP,
    reviewed_by VARCHAR(255), -- Moderator user ID
    moderator_notes TEXT,
    action_taken VARCHAR(50), -- WARNING, TEMPORARY_BAN, PERMANENT_BAN, CONTENT_REMOVED, ACCOUNT_RESTRICTED, NONE
    
    -- Constraints
    CONSTRAINT chk_different_users CHECK (reporter_user_id != reported_user_id),
    CONSTRAINT chk_valid_reason CHECK (report_reason IN (
        'spam', 'harassment', 'inappropriate_content', 'scam', 
        'fake_profile', 'impersonation', 'threatening_behavior', 'other'
    )),
    CONSTRAINT chk_valid_context_type CHECK (
        context_type IS NULL OR 
        context_type IN ('profile', 'posting', 'chat', 'review', 'general')
    ),
    CONSTRAINT chk_valid_status CHECK (status IN (
        'pending', 'under_review', 'reviewed', 'dismissed', 'action_taken'
    )),
    CONSTRAINT chk_valid_action CHECK (
        action_taken IS NULL OR 
        action_taken IN ('warning', 'temporary_ban', 'permanent_ban', 
                        'content_removed', 'account_restricted', 'none')
    )
);

-- Create indexes for efficient queries
CREATE INDEX idx_user_reports_reporter ON user_reports(reporter_user_id);
CREATE INDEX idx_user_reports_reported ON user_reports(reported_user_id);
CREATE INDEX idx_user_reports_reporter_reported ON user_reports(reporter_user_id, reported_user_id);
CREATE INDEX idx_user_reports_reported_status ON user_reports(reported_user_id, status);
CREATE INDEX idx_user_reports_status_reported_at ON user_reports(status, reported_at DESC);
CREATE INDEX idx_user_reports_context ON user_reports(context_type, context_id);

-- Add comment to table
COMMENT ON TABLE user_reports IS 'Stores user reports for moderation purposes including spam, harassment, scams, etc.';

-- Add comments to columns
COMMENT ON COLUMN user_reports.id IS 'Unique identifier for the report';
COMMENT ON COLUMN user_reports.reporter_user_id IS 'User ID of the person filing the report';
COMMENT ON COLUMN user_reports.reported_user_id IS 'User ID of the person being reported';
COMMENT ON COLUMN user_reports.report_reason IS 'Category of the report (spam, harassment, etc.)';
COMMENT ON COLUMN user_reports.description IS 'Optional detailed description from the reporter';
COMMENT ON COLUMN user_reports.context_type IS 'Type of content being reported (profile, posting, chat, review)';
COMMENT ON COLUMN user_reports.context_id IS 'ID of the specific content item being reported';
COMMENT ON COLUMN user_reports.status IS 'Current status of the report (pending, reviewed, etc.)';
COMMENT ON COLUMN user_reports.reviewed_at IS 'Timestamp when the report was reviewed by a moderator';
COMMENT ON COLUMN user_reports.reviewed_by IS 'User ID of the moderator who reviewed the report';
COMMENT ON COLUMN user_reports.moderator_notes IS 'Internal notes from the moderator';
COMMENT ON COLUMN user_reports.action_taken IS 'Action taken as a result of the report';

-- Grant necessary permissions (adjust based on your database user)
-- GRANT SELECT, INSERT, UPDATE ON user_reports TO barter_app_user;
