CREATE TABLE IF NOT EXISTS posting_expiry_reminders (
    posting_id VARCHAR(36) NOT NULL REFERENCES user_postings(id) ON DELETE CASCADE,
    user_id VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    reminded_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (posting_id, expires_at)
);

CREATE INDEX IF NOT EXISTS idx_posting_expiry_reminders_user_reminded
    ON posting_expiry_reminders(user_id, reminded_at);

COMMENT ON TABLE posting_expiry_reminders IS
    'Tracks one-day-before-expiry renewal reminders sent for postings';

COMMENT ON COLUMN posting_expiry_reminders.expires_at IS
    'Posting expiration timestamp at the time the reminder was sent';
