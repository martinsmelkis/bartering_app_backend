-- Chat Read Receipts Feature
-- Tracks message status: SENT, DELIVERED, READ
-- Enables read receipt notifications in real-time chat

CREATE TABLE IF NOT EXISTS chat_read_receipts (
    message_id VARCHAR(100) NOT NULL,
    sender_id VARCHAR(255) NOT NULL,
    recipient_id VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('SENT', 'DELIVERED', 'READ')),
    timestamp BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, recipient_id)
);

-- Index for querying receipts by sender (to show status of sent messages)
CREATE INDEX idx_read_receipts_sender_message ON chat_read_receipts(sender_id, message_id);

-- Index for querying by message ID
CREATE INDEX idx_read_receipts_message ON chat_read_receipts(message_id);

-- Index for querying by recipient
CREATE INDEX idx_read_receipts_recipient ON chat_read_receipts(recipient_id);

-- Index for cleanup operations (by created_at)
CREATE INDEX idx_read_receipts_created_at ON chat_read_receipts(created_at);

-- Add foreign key constraints if user table exists
-- ALTER TABLE chat_read_receipts ADD CONSTRAINT fk_read_receipt_sender
--     FOREIGN KEY (sender_id) REFERENCES user_registration_data(id) ON DELETE CASCADE;
-- ALTER TABLE chat_read_receipts ADD CONSTRAINT fk_read_receipt_recipient
--     FOREIGN KEY (recipient_id) REFERENCES user_registration_data(id) ON DELETE CASCADE;

-- Comments for documentation
COMMENT ON TABLE chat_read_receipts IS 'Tracks message delivery and read status for chat messages';
COMMENT ON COLUMN chat_read_receipts.message_id IS 'Server-generated message ID';
COMMENT ON COLUMN chat_read_receipts.sender_id IS 'Original sender of the message';
COMMENT ON COLUMN chat_read_receipts.recipient_id IS 'Recipient who received/read the message';
COMMENT ON COLUMN chat_read_receipts.status IS 'Message status: SENT, DELIVERED, or READ';
COMMENT ON COLUMN chat_read_receipts.timestamp IS 'Client timestamp when status changed (Unix ms)';
COMMENT ON COLUMN chat_read_receipts.created_at IS 'Server timestamp when receipt was created';
