-- Enforce unique notification contact email across users (case-insensitive)
-- Keeps NULL emails allowed (partial unique index)

CREATE UNIQUE INDEX IF NOT EXISTS uq_user_notification_contacts_email_lower
    ON user_notification_contacts (LOWER(email))
    WHERE email IS NOT NULL;
