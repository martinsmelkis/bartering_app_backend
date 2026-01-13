-- V1__Populate_initial_attributes_and_categories.sql

-- Enable PostGIS for spatial data types and functions
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS ai CASCADE;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Creates the master table for all attributes (skills, interests, items)
CREATE TABLE IF NOT EXISTS attributes (
    id SERIAL PRIMARY KEY,
    attribute_key VARCHAR(100) UNIQUE NOT NULL,
    localization_key VARCHAR(150) UNIQUE NOT NULL,
    -- Stores the original, user-defined text for an unapproved attribute.
    -- This serves as a display fallback until proper localization is added.
    custom_user_attr_text VARCHAR(100) DEFAULT NULL,
    is_approved BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    embedding vector(1024)
);

-- Create a high-performance index for vector similarity searches
CREATE INDEX ON attributes USING hnsw (embedding vector_cosine_ops);

CREATE INDEX idx_attributes_custom_text_gin
ON attributes USING gin(custom_user_attr_text gin_trgm_ops);

-- Index for attribute_name_key
CREATE INDEX idx_attributes_name_key_gin
ON attributes USING gin(attribute_key gin_trgm_ops);

-- Set pg_trgm thresholds for the current database
ALTER DATABASE "mainDatabase" SET pg_trgm.similarity_threshold = 0.45;
ALTER DATABASE "mainDatabase" SET pg_trgm.word_similarity_threshold = 0.45;

-- Creates the main user_registration_data table for authentication and core identification.
CREATE TABLE IF NOT EXISTS user_registration_data (
    id VARCHAR(255) PRIMARY KEY,
    public_key TEXT UNIQUE NOT NULL,      -- For signature verification and identification.
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Creates the user_profiles table for storing non-auth-related profile data.
-- This has a strict one-to-one relationship with the user_registration_data table.
CREATE TABLE IF NOT EXISTS user_profiles (
    user_id VARCHAR(255) PRIMARY KEY,       -- This is both the Primary Key and the Foreign Key.

    name VARCHAR(255) NOT NULL,
    -- Use a single 'geography' column to store the location.
    -- The format 'Point' and SRID 4326 are standard for lat/lon coordinates.
    location geography(Point, 4326),

    -- Onboarding format - Map: Keyword and associated weight
    profile_keywords_with_weights jsonb NOT NULL,

    preferred_language VARCHAR(10) NOT NULL DEFAULT 'en',

    CONSTRAINT fk_user_profile_user
        FOREIGN KEY(user_id)
        REFERENCES user_registration_data(id)  -- Enforces the one-to-one link.
        ON DELETE CASCADE    -- If a user is deleted, their profile is also deleted.
);

-- Index for language-based queries (optional but helpful for matching)
CREATE INDEX IF NOT EXISTS idx_user_profiles_language
ON user_profiles(preferred_language);

-- Comment for documentation
COMMENT ON COLUMN user_profiles.preferred_language IS 'User preferred language (ISO 639-1 code: en, fr, lv, etc.) for UI localization and matching';


-- This table stores the calculated semantic profile vectors for each user.
CREATE TABLE user_semantic_profiles (
    user_id VARCHAR(36) NOT NULL,
    -- The embedding vectors for the profile.
    -- embedding_profile - the onboarding data
    -- embedding_actions - historical user actions and interactions data
    embedding_profile VECTOR(1024),
    embedding_actions VECTOR(1024),
    embedding_haves VECTOR(1024),
    embedding_needs VECTOR(1024),
    hash_needs VARCHAR(64),
    hash_haves VARCHAR(64),
    hash_profile VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (user_id),
    FOREIGN KEY (user_id) REFERENCES user_registration_data(id) ON DELETE CASCADE
);

-- Create a high-performance index for fast similarity searches on the profile embeddings.
CREATE INDEX ON user_semantic_profiles USING hnsw (embedding_profile vector_cosine_ops);
CREATE INDEX ON user_semantic_profiles USING hnsw (embedding_actions vector_cosine_ops);
CREATE INDEX ON user_semantic_profiles USING hnsw (embedding_haves vector_cosine_ops);
CREATE INDEX ON user_semantic_profiles USING hnsw (embedding_needs vector_cosine_ops);

-- Creates the crucial link table between users and their attributes (interests/offers)
CREATE TABLE IF NOT EXISTS user_attributes (
    -- Foreign key linking to the user who has the attribute
    user_id VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,

    -- Foreign key linking to the master attribute definition
    attribute_id VARCHAR NOT NULL REFERENCES attributes(attribute_key) ON DELETE CASCADE,

    -- The user's relationship to this attribute (e.g., SEEKING, PROVIDING, SHARING)
    type VARCHAR(20) NOT NULL, -- Storing the enum as a string

    -- The user's self-assessed relevancy or skill level for this attribute
    relevancy DECIMAL(5, 4) NOT NULL,

    -- A user-provided description, e.g., "I can teach beginner guitar on weekends."
    description TEXT,

    -- Estimated monetary value. Useful for reference in barter ("trade items of similar value").
    estimated_value DECIMAL(10, 2),

    -- The timestamp when this offer expires and should no longer be shown.
    -- A null value implies the offer is long-term or indefinite (e.g., a skill).
    expires_at TIMESTAMPTZ,

    -- Auditing timestamps
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- A composite primary key to ensure a user has a unique link to an attribute
    PRIMARY KEY (user_id, attribute_id, type)
);

-- This table stores chat messages when recipients are offline
CREATE TABLE IF NOT EXISTS offline_messages (
    id VARCHAR(36) PRIMARY KEY,
    sender_id VARCHAR(255) NOT NULL,
    recipient_id VARCHAR(255) NOT NULL,
    sender_name VARCHAR(255) NOT NULL,
    encrypted_payload TEXT NOT NULL,
    timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index on recipient_id for efficient queries when fetching pending messages for a user
CREATE INDEX idx_offline_messages_recipient ON offline_messages(recipient_id);

-- Composite index for efficient queries of undelivered messages for a specific recipient
CREATE INDEX idx_offline_messages_recipient_delivered ON offline_messages(recipient_id, delivered);

-- Index on timestamp for cleanup operations
CREATE INDEX idx_offline_messages_timestamp ON offline_messages(timestamp);

CREATE TABLE IF NOT EXISTS user_relationships (
    -- The user who is initiating the action (e.g., the one who clicks "Add Friend")
    user_id_from VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,

    -- The user who is being followed or friended
    user_id_to VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,

    -- A field to describe the relationship type
    -- This allows you to have "friends", "favorites", "blocked", etc., all in one table.
    relationship_type VARCHAR(50) NOT NULL,

    -- The timestamp when the relationship was created
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- A composite primary key to ensure a user can only have one type of relationship
    -- with another user. For example, you can't "friend" the same person twice.
    PRIMARY KEY (user_id_from, user_id_to, relationship_type)
);

-- Creates the master table for all categories (main and sub)
CREATE TABLE IF NOT EXISTS categories (
    id SERIAL PRIMARY KEY,
    category_key VARCHAR(100) UNIQUE NOT NULL,
    localization_key VARCHAR(150) UNIQUE NOT NULL,
    parent_id INT REFERENCES categories(id) ON DELETE SET NULL,
    ui_style_hint VARCHAR(50),
    description TEXT, -- A description for e.g., semantic matching
    embedding vector(1024));
);

-- Creates the link table for the many-to-many relationship between attributes and categories
CREATE TABLE IF NOT EXISTS attribute_categories_link (
    attribute_id INT NOT NULL REFERENCES attributes(id) ON DELETE CASCADE,
    category_id INT NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    relevancy DECIMAL(5, 4) NOT NULL,
    PRIMARY KEY (attribute_id, category_id)
);

--- User postings - interests and offers ---
CREATE TABLE IF NOT EXISTS user_postings (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,

    -- Core posting information
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,

    -- Optional monetary value for the posting
    value DECIMAL(10, 2),

    -- When the posting expires (NULL means no expiration)
    expires_at TIMESTAMPTZ,

    -- Array of image URLs stored as JSON
    image_urls JSONB DEFAULT '[]'::jsonb,

    -- Type: true for offer, false for interest/need
    is_offer BOOLEAN NOT NULL,

    -- Status of the posting (active, expired, deleted)
    status VARCHAR(20) NOT NULL DEFAULT 'active',

    -- Semantic embedding for the posting (title + description)
    embedding VECTOR(1024),

    -- Auditing timestamps
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Index for efficient queries
    CONSTRAINT check_status CHECK (status IN ('active', 'expired', 'deleted', 'fulfilled'))
);

-- Create indexes for efficient queries
CREATE INDEX idx_user_postings_user_id ON user_postings(user_id);
CREATE INDEX idx_user_postings_is_offer ON user_postings(is_offer);
CREATE INDEX idx_user_postings_status ON user_postings(status);
CREATE INDEX idx_user_postings_expires_at ON user_postings(expires_at);
CREATE INDEX idx_user_postings_created_at ON user_postings(created_at DESC);

-- Composite index for common query patterns
CREATE INDEX idx_user_postings_status_is_offer ON user_postings(status, is_offer);

-- HNSW index for semantic similarity searches on posting embeddings
CREATE INDEX idx_user_postings_embedding ON user_postings USING hnsw (embedding vector_cosine_ops);

-- Link table between postings and attributes (many-to-many)
-- This allows a posting to be tagged with multiple attributes/categories
CREATE TABLE IF NOT EXISTS posting_attributes_link (
    posting_id VARCHAR(36) NOT NULL REFERENCES user_postings(id) ON DELETE CASCADE,
    attribute_id VARCHAR(100) NOT NULL REFERENCES attributes(attribute_key) ON DELETE CASCADE,
    relevancy DECIMAL(5, 4) NOT NULL DEFAULT 1.0,
    PRIMARY KEY (posting_id, attribute_id)
);

-- Index for efficient attribute-based posting searches
CREATE INDEX idx_posting_attributes_attribute_id ON posting_attributes_link(attribute_id);
CREATE INDEX idx_posting_attributes_posting_id ON posting_attributes_link(posting_id);

-- Function to automatically update the updated_at timestamp
CREATE OR REPLACE FUNCTION update_user_postings_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger to call the function before each update
CREATE TRIGGER trigger_user_postings_updated_at
    BEFORE UPDATE ON user_postings
    FOR EACH ROW
    EXECUTE FUNCTION update_user_postings_updated_at();

-- Adds support for temporary encrypted file storage for E2EE file sharing
-- Creates the encrypted_files table for storing encrypted file content
-- Files are encrypted client-side before upload and stored temporarily with TTL
CREATE TABLE IF NOT EXISTS encrypted_files (
    id VARCHAR(36) PRIMARY KEY,
    sender_id VARCHAR(255) NOT NULL,
    recipient_id VARCHAR(255) NOT NULL,
    filename VARCHAR(512) NOT NULL,
    mime_type VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    encrypted_data BYTEA NOT NULL, -- Encrypted file content as binary data
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    downloaded BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index on recipient_id for efficient queries when fetching pending files for a user
CREATE INDEX idx_encrypted_files_recipient ON encrypted_files(recipient_id);

-- Composite index for efficient queries of undownloaded files for a specific recipient
CREATE INDEX idx_encrypted_files_recipient_downloaded ON encrypted_files(recipient_id, downloaded);

-- Index on expires_at for cleanup operations
CREATE INDEX idx_encrypted_files_expires_at ON encrypted_files(expires_at);

-- Composite index for cleanup queries (expired or downloaded files)
CREATE INDEX idx_encrypted_files_cleanup ON encrypted_files(downloaded, expires_at);

------------- Functions and Procedures -------------------

-- This function multiplies each element of a vector by a scalar value.
-- It uses fast, set-based array operations instead of a slow loop.
CREATE OR REPLACE FUNCTION scalar_mult(v vector, s real) RETURNS vector AS $$
BEGIN
  -- Unnest the vector into a table of elements, multiply each element,
  -- then aggregate them back into a real[] array, and cast back to vector.
  RETURN (SELECT array_agg(elem * s)::vector FROM unnest(v::real[]) AS elem);
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- ============================================================================
-- USER NOTIFICATION CONTACTS
-- ============================================================================

-- Stores user's email and push notification identifiers
-- One row per user with all contact methods
CREATE TABLE IF NOT EXISTS user_notification_contacts (
    user_id VARCHAR(255) PRIMARY KEY REFERENCES user_registration_data(id) ON DELETE CASCADE,

    -- Email contact
    email VARCHAR(255),
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    email_verification_token VARCHAR(100),
    email_verified_at TIMESTAMPTZ,

    -- Push notification identifiers (can have multiple devices)
    -- Stored as JSONB array of {token, platform, deviceId, isActive}
    push_tokens JSONB DEFAULT '[]'::jsonb,

    -- Global notification settings
    notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    quiet_hours_start INTEGER, -- Hour 0-23
    quiet_hours_end INTEGER,   -- Hour 0-23

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT check_quiet_hours_start CHECK (quiet_hours_start IS NULL OR (quiet_hours_start >= 0 AND quiet_hours_start <= 23)),
    CONSTRAINT check_quiet_hours_end CHECK (quiet_hours_end IS NULL OR (quiet_hours_end >= 0 AND quiet_hours_end <= 23))
);

CREATE INDEX idx_user_notification_contacts_email ON user_notification_contacts(email) WHERE email IS NOT NULL;
CREATE INDEX idx_user_notification_contacts_verified ON user_notification_contacts(email_verified) WHERE email_verified = TRUE;

-- ============================================================================
-- ATTRIBUTE NOTIFICATION PREFERENCES
-- ============================================================================

-- Stores notification preferences for each user attribute
-- Only create rows for attributes where user wants notifications
CREATE TABLE IF NOT EXISTS attribute_notification_preferences (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,
    attribute_id VARCHAR(100) NOT NULL REFERENCES attributes(attribute_key) ON DELETE CASCADE,

    -- Notification settings for this specific attribute
    notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    notification_frequency VARCHAR(20) NOT NULL DEFAULT 'INSTANT', -- INSTANT, DAILY, WEEKLY, MANUAL
    min_match_score DOUBLE PRECISION NOT NULL DEFAULT 0.7,

    -- What to notify about
    notify_on_new_postings BOOLEAN NOT NULL DEFAULT TRUE,  -- New postings matching this attribute
    notify_on_new_users BOOLEAN NOT NULL DEFAULT FALSE,    -- New users with this attribute

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT check_notification_frequency CHECK (notification_frequency IN ('INSTANT', 'DAILY', 'WEEKLY', 'MANUAL')),
    CONSTRAINT check_min_match_score CHECK (min_match_score >= 0.0 AND min_match_score <= 1.0),
    CONSTRAINT unique_user_attribute UNIQUE(user_id, attribute_id)
);

CREATE INDEX idx_attr_notif_prefs_user ON attribute_notification_preferences(user_id);
CREATE INDEX idx_attr_notif_prefs_attribute ON attribute_notification_preferences(attribute_id);
CREATE INDEX idx_attr_notif_prefs_enabled ON attribute_notification_preferences(notifications_enabled) WHERE notifications_enabled = TRUE;

-- ============================================================================
-- POSTING NOTIFICATION PREFERENCES
-- ============================================================================

-- Stores notification preferences for each user posting
-- Only for "interest" postings (isOffer = false) where user wants notifications
CREATE TABLE IF NOT EXISTS posting_notification_preferences (
    posting_id VARCHAR(36) PRIMARY KEY REFERENCES user_postings(id) ON DELETE CASCADE,

    -- Notification settings for this specific posting
    notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    notification_frequency VARCHAR(20) NOT NULL DEFAULT 'INSTANT',
    min_match_score DOUBLE PRECISION NOT NULL DEFAULT 0.7,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT check_posting_notification_frequency CHECK (notification_frequency IN ('INSTANT', 'DAILY', 'WEEKLY', 'MANUAL')),
    CONSTRAINT check_posting_min_match_score CHECK (min_match_score >= 0.0 AND min_match_score <= 1.0)
);

CREATE INDEX idx_posting_notif_prefs_enabled ON posting_notification_preferences(notifications_enabled) WHERE notifications_enabled = TRUE;

-- ============================================================================
-- MATCH HISTORY
-- ============================================================================

-- Tracks matches that have been found and potentially notified
-- Prevents duplicate notifications and provides history
CREATE TABLE IF NOT EXISTS match_history (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,

    -- What type of match
    match_type VARCHAR(30) NOT NULL, -- POSTING_MATCH, ATTRIBUTE_MATCH, USER_MATCH

    -- Source (what the user has that caused the match)
    source_type VARCHAR(30) NOT NULL, -- ATTRIBUTE, POSTING
    source_id VARCHAR(100) NOT NULL,  -- attribute_id or posting_id

    -- Target (what matched)
    target_type VARCHAR(30) NOT NULL, -- POSTING, USER
    target_id VARCHAR(255) NOT NULL,  -- posting_id or user_id

    -- Match details
    match_score DOUBLE PRECISION NOT NULL,
    match_reason TEXT, -- Optional explanation of why it matched

    -- Notification tracking
    notification_sent BOOLEAN NOT NULL DEFAULT FALSE,
    notification_sent_at TIMESTAMPTZ,

    -- User interaction tracking
    viewed BOOLEAN NOT NULL DEFAULT FALSE,
    viewed_at TIMESTAMPTZ,
    dismissed BOOLEAN NOT NULL DEFAULT FALSE,
    dismissed_at TIMESTAMPTZ,

    matched_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT check_match_type CHECK (match_type IN ('POSTING_MATCH', 'ATTRIBUTE_MATCH', 'USER_MATCH')),
    CONSTRAINT check_source_type CHECK (source_type IN ('ATTRIBUTE', 'POSTING')),
    CONSTRAINT check_target_type CHECK (target_type IN ('POSTING', 'USER')),
    CONSTRAINT check_match_score CHECK (match_score >= 0.0 AND match_score <= 1.0)
);

CREATE INDEX idx_match_history_user ON match_history(user_id);
CREATE INDEX idx_match_history_source ON match_history(source_type, source_id);
CREATE INDEX idx_match_history_target ON match_history(target_type, target_id);
CREATE INDEX idx_match_history_type ON match_history(match_type);
CREATE INDEX idx_match_history_unviewed ON match_history(user_id, viewed) WHERE viewed = FALSE;
CREATE INDEX idx_match_history_pending_notification ON match_history(notification_sent) WHERE notification_sent = FALSE;
CREATE INDEX idx_match_history_matched_at ON match_history(matched_at DESC);

-- Prevent duplicate matches
CREATE UNIQUE INDEX idx_match_history_unique ON match_history(user_id, source_type, source_id, target_type, target_id);

-- ============================================================================
-- FUNCTIONS AND TRIGGERS
-- ============================================================================

-- Auto-update timestamp on user_notification_contacts
CREATE OR REPLACE FUNCTION update_user_notification_contacts_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_user_notification_contacts_updated_at
    BEFORE UPDATE ON user_notification_contacts
    FOR EACH ROW
    EXECUTE FUNCTION update_user_notification_contacts_updated_at();

-- Auto-update timestamp on attribute_notification_preferences
CREATE OR REPLACE FUNCTION update_attribute_notification_preferences_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_attribute_notification_preferences_updated_at
    BEFORE UPDATE ON attribute_notification_preferences
    FOR EACH ROW
    EXECUTE FUNCTION update_attribute_notification_preferences_updated_at();

-- Auto-update timestamp on posting_notification_preferences
CREATE OR REPLACE FUNCTION update_posting_notification_preferences_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_posting_notification_preferences_updated_at
    BEFORE UPDATE ON posting_notification_preferences
    FOR EACH ROW
    EXECUTE FUNCTION update_posting_notification_preferences_updated_at();

-- ============================================================================
-- COMMENTS FOR DOCUMENTATION
-- ============================================================================

COMMENT ON TABLE user_notification_contacts IS 'User email and push notification contact information';
COMMENT ON TABLE attribute_notification_preferences IS 'Notification preferences for user attributes (SEEKING type)';
COMMENT ON TABLE posting_notification_preferences IS 'Notification preferences for user postings (interest postings)';
COMMENT ON TABLE match_history IS 'History of matches found and notifications sent';

COMMENT ON COLUMN user_notification_contacts.push_tokens IS 'JSONB array of push token objects: [{token, platform, deviceId, isActive, addedAt}]';
COMMENT ON COLUMN attribute_notification_preferences.notification_frequency IS 'How often to notify: INSTANT, DAILY, WEEKLY, or MANUAL';
COMMENT ON COLUMN match_history.match_type IS 'Type of match: POSTING_MATCH (new posting matches my interests), ATTRIBUTE_MATCH (new user attribute), USER_MATCH (new user matches)';
COMMENT ON COLUMN match_history.source_type IS 'What caused the match: ATTRIBUTE (user attribute) or POSTING (user posting)';
COMMENT ON COLUMN match_history.target_type IS 'What was matched: POSTING (marketplace posting) or USER (user profile)';


------------- Initial data insertion ---------------------
INSERT INTO categories (category_key, localization_key, ui_style_hint, description) VALUES
    ('main_green', 'category.main_green', 'GREEN', 'Nature, outdoors, gardening, animals, environment, hiking, plants, sustainability, wildlife, ecology, forests, mountains, camping, fishing, farming, conservation, organic, natural, green living, eco-friendly, botanical, outdoor adventure, wilderness, backpacking, greenhouse, seeds, trails, wilderness, recycling, upcycling, flowers, herbs'),
    ('main_red', 'category.main_red', 'RED', 'Sports, physical exercise, physical work, partying, dancing, mechanisms, hands-on activities, fitness, athletics, fitness training, sports competition, movement, energy, active lifestyle, gym, workout, strength, manual labor, DIY, craftsmanship, building, equipment repair, nightlife, sports equipment, workout planning, fitness training, sports coaching, electrician, plumbing, furniture, metalworking, car maintenance'),
    ('main_blue', 'category.main_blue', 'BLUE', 'Business, entrepreneurship, paid work, making contacts, money matters, finance, career, professional development, networking, investments, trading, startups, corporate, management, consulting, sales, marketing, leadership, business strategy, economics, commerce, job opportunities, employment, project management, accounting, legal, laws, event planning, public speaking, motivation, logistics'),
    ('main_purple', 'category.main_purple', 'PURPLE', 'Art, spirituality, philosophy, culture, music, crafts, creativity, design, history, meditation, mindfulness, artistic expression, handmade, visual arts, performing arts, literature, poetry, aesthetics, cultural heritage, traditions, consciousness, self-discovery, contemplation, galleries, museums, photography, UI/UX Design, Animation, Illustration, Video editing, painting, Sculpting, mysticism, books, reading, writing, musician, musical instruments, vocals'),
    ('main_yellow', 'category.main_yellow', 'YELLOW', 'Communication, chat, social activities, casual conversation, local events, neighborhood, community gatherings, socializing, networking events, meetups, friendly exchanges, leisure time, entertainment, hobbies, interests, discussions, forums, group activities, public spaces, tutoring, advice, language exchange, translation, event hosting, public relations, dating, social, contacts, medical'),
    ('main_orange', 'category.main_orange', 'ORANGE', 'Volunteering, open-ended help, free exchange, consulting, non-specific assistance, community support, charity, giving back, mutual aid, neighbors helping neighbors, goodwill, kindness, sharing, cooperation, solidarity, social responsibility, humanitarian, service, outreach, collaboration, pet sitting, cooking, low-effort assistance, ridesharing, tool lending, equipment lending, errands, transporting'),
    ('main_teal', 'category.main_teal', 'TEAL', 'Technology, gaming, video games, multiplayer, e-sports, gaming PC, console gaming, learning, education, innovation, brainstorming, ideas, science, podcasts, software, programming, coding, digital, tech, computing, engineering, research and development, courses, tutorials, knowledge sharing, problem-solving, inventions, STEM, scripting, data, automation, artificial intelligence, software development, Python, Java, Kotlin, JavaScript, Web development, mobile apps, gadgets, electronics, computers, hardware, smartphones, routers, networking, wifi, game development, streaming')
ON CONFLICT (category_key) DO NOTHING;
