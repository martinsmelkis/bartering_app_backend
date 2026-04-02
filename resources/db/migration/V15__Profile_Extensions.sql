-- Add profile extension fields and remove legacy federation_enabled flag from user_profiles

ALTER TABLE user_profiles
ADD COLUMN IF NOT EXISTS self_description VARCHAR(128),
ADD COLUMN IF NOT EXISTS account_type VARCHAR(32) NOT NULL DEFAULT 'INDIVIDUAL',
ADD COLUMN IF NOT EXISTS profile_avatar_icon TEXT,
ADD COLUMN IF NOT EXISTS work_reference_image_urls JSONB NOT NULL DEFAULT '[]'::jsonb;

-- Legacy federation flag has been replaced with user_privacy_consents.federation_consent
DROP INDEX IF EXISTS idx_user_profiles_federation_enabled;
ALTER TABLE user_profiles
DROP COLUMN IF EXISTS federation_enabled;
