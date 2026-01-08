-- V2__Reviews_System.sql
-- Creates the complete reviews, reputation, and transaction tracking system

-- ============================================================================
-- BARTER TRANSACTIONS TABLE
-- ============================================================================
-- Tracks all barter transactions between users
-- A completed transaction is a prerequisite for leaving a review
CREATE TABLE IF NOT EXISTS barter_transactions (
    id VARCHAR(36) PRIMARY KEY,
    user1_id VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,
    user2_id VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,
    initiated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    status VARCHAR(50) NOT NULL DEFAULT 'pending', -- pending, done, cancelled, disputed, expired, no_deal, scam
    estimated_value DECIMAL(10, 2),
    location_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    risk_score DECIMAL(3, 2),
    
    -- Indexes for performance
    CONSTRAINT check_different_users CHECK (user1_id != user2_id)
);

CREATE INDEX idx_transactions_user1 ON barter_transactions(user1_id);
CREATE INDEX idx_transactions_user2 ON barter_transactions(user2_id);
CREATE INDEX idx_transactions_status ON barter_transactions(status);
CREATE INDEX idx_transactions_completed ON barter_transactions(completed_at);

-- ============================================================================
-- USER REPUTATIONS TABLE
-- ============================================================================
-- Stores aggregated reputation scores for users
-- Updated whenever new reviews are submitted
CREATE TABLE IF NOT EXISTS user_reputations (
    user_id VARCHAR(255) PRIMARY KEY REFERENCES user_registration_data(id) ON DELETE CASCADE,
    average_rating DECIMAL(3, 2) NOT NULL DEFAULT 0.00,
    total_reviews INTEGER NOT NULL DEFAULT 0,
    verified_reviews INTEGER NOT NULL DEFAULT 0,
    trade_diversity_score DECIMAL(3, 2) NOT NULL DEFAULT 0.50,
    trust_level VARCHAR(50) NOT NULL DEFAULT 'new', -- new, emerging, established, trusted, verified
    last_updated TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- Constraints
    CONSTRAINT check_rating_range CHECK (average_rating >= 0 AND average_rating <= 5),
    CONSTRAINT check_diversity_range CHECK (trade_diversity_score >= 0 AND trade_diversity_score <= 1),
    CONSTRAINT check_verified_count CHECK (verified_reviews <= total_reviews)
);

CREATE INDEX idx_reputations_rating ON user_reputations(average_rating DESC);
CREATE INDEX idx_reputations_trust_level ON user_reputations(trust_level);

-- ============================================================================
-- USER REVIEWS TABLE
-- ============================================================================
-- Stores all user reviews
-- Reviews are initially hidden until both parties submit (blind review period)
CREATE TABLE IF NOT EXISTS user_reviews (
    id VARCHAR(36) PRIMARY KEY,
    transaction_id VARCHAR(36) NOT NULL REFERENCES barter_transactions(id) ON DELETE CASCADE,
    reviewer_id VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,
    target_user_id VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,
    rating INTEGER NOT NULL,
    review_text TEXT,
    transaction_status VARCHAR(50) NOT NULL, -- done, scam, no_deal, etc.
    review_weight DECIMAL(3, 2) NOT NULL DEFAULT 1.00,
    is_visible BOOLEAN NOT NULL DEFAULT FALSE, -- Hidden until both submit
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revealed_at TIMESTAMPTZ,
    is_verified BOOLEAN NOT NULL DEFAULT FALSE,
    moderation_status VARCHAR(50), -- pending, approved, rejected
    
    -- Constraints
    CONSTRAINT check_rating_1_5 CHECK (rating >= 1 AND rating <= 5),
    CONSTRAINT check_weight_range CHECK (review_weight >= 0.1 AND review_weight <= 2.0),
    CONSTRAINT check_different_reviewer_target CHECK (reviewer_id != target_user_id),
    CONSTRAINT unique_review_per_transaction UNIQUE (transaction_id, reviewer_id, target_user_id)
);

CREATE INDEX idx_reviews_transaction ON user_reviews(transaction_id);
CREATE INDEX idx_reviews_reviewer ON user_reviews(reviewer_id);
CREATE INDEX idx_reviews_target ON user_reviews(target_user_id);
CREATE INDEX idx_reviews_visible ON user_reviews(target_user_id, is_visible);
CREATE INDEX idx_reviews_submitted ON user_reviews(submitted_at);
CREATE INDEX idx_reviews_transaction_reviewer ON user_reviews(transaction_id, reviewer_id);

-- ============================================================================
-- PENDING REVIEWS TABLE
-- ============================================================================
-- Stores reviews that have been submitted but not yet revealed
-- Reviews are encrypted until both parties submit or deadline expires
CREATE TABLE IF NOT EXISTS pending_reviews (
    transaction_id VARCHAR(255) NOT NULL,
    reviewer_id VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,
    encrypted_review TEXT NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reveal_deadline TIMESTAMPTZ NOT NULL,
    revealed BOOLEAN NOT NULL DEFAULT FALSE,
    revealed_at TIMESTAMPTZ,
    
    PRIMARY KEY (transaction_id, reviewer_id)
);

CREATE INDEX idx_pending_reveal_deadline ON pending_reviews(reveal_deadline, revealed);

-- ============================================================================
-- REVIEW RESPONSES TABLE
-- ============================================================================
-- Allows users to respond to reviews they've received
-- Provides a defense mechanism against unfair reviews
CREATE TABLE IF NOT EXISTS review_responses (
    review_id VARCHAR(36) PRIMARY KEY REFERENCES user_reviews(id) ON DELETE CASCADE,
    response_text TEXT NOT NULL,
    responded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================================
-- REVIEW APPEALS TABLE
-- ============================================================================
-- Review appeals/disputes for moderation
CREATE TABLE IF NOT EXISTS review_appeals (
    id VARCHAR(36) PRIMARY KEY,
    review_id VARCHAR(36) NOT NULL REFERENCES user_reviews(id) ON DELETE CASCADE,
    appealed_by VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,
    reason TEXT NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'pending', -- pending, under_review, approved, rejected, evidence_requested
    appealed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    moderator_id VARCHAR(255),
    moderator_notes TEXT
);

CREATE INDEX idx_appeals_review ON review_appeals(review_id);
CREATE INDEX idx_appeals_appealed_by ON review_appeals(appealed_by);
CREATE INDEX idx_appeals_status ON review_appeals(status, appealed_at);

-- ============================================================================
-- REVIEW AUDIT LOG TABLE
-- ============================================================================
-- Audit trail for review-related actions
-- Used for abuse detection and pattern analysis
CREATE TABLE IF NOT EXISTS review_audit_log (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,
    action VARCHAR(50) NOT NULL, -- review_submitted, review_edited, appeal_filed, etc.
    related_review_id VARCHAR(36),
    metadata JSONB,
    ip_address VARCHAR(45),
    device_fingerprint VARCHAR(255),
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_user ON review_audit_log(user_id);
CREATE INDEX idx_audit_review ON review_audit_log(related_review_id);
CREATE INDEX idx_audit_timestamp ON review_audit_log(timestamp);
CREATE INDEX idx_audit_device ON review_audit_log(device_fingerprint);

-- ============================================================================
-- REPUTATION BADGES TABLE
-- ============================================================================
-- Tracks which reputation badges each user has earned
CREATE TABLE IF NOT EXISTS reputation_badges (
    user_id VARCHAR(255) NOT NULL REFERENCES user_registration_data(id) ON DELETE CASCADE,
    badge_type VARCHAR(50) NOT NULL, -- identity_verified, veteran_trader, top_rated, etc.
    earned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ, -- Some badges may expire
    
    PRIMARY KEY (user_id, badge_type)
);

CREATE INDEX idx_badges_user ON reputation_badges(user_id);
CREATE INDEX idx_badges_type ON reputation_badges(badge_type);

-- ============================================================================
-- MODERATION QUEUE TABLE
-- ============================================================================
-- Queue of reviews requiring manual moderation
-- Reviews flagged as scam or with high risk scores are placed here
CREATE TABLE IF NOT EXISTS review_moderation_queue (
    id VARCHAR(36) PRIMARY KEY,
    review_id VARCHAR(36) NOT NULL REFERENCES user_reviews(id) ON DELETE CASCADE,
    transaction_id VARCHAR(36) NOT NULL REFERENCES barter_transactions(id) ON DELETE CASCADE,
    flag_reason VARCHAR(50) NOT NULL, -- scam, disputed, high_risk, etc.
    risk_factors JSONB,
    priority VARCHAR(50) NOT NULL DEFAULT 'medium', -- low, medium, high, urgent
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reviewer_id VARCHAR(255) NOT NULL,
    target_user_id VARCHAR(255) NOT NULL,
    assigned_to VARCHAR(255), -- Moderator ID
    status VARCHAR(50) NOT NULL DEFAULT 'pending', -- pending, in_review, resolved
    resolved_at TIMESTAMPTZ
);

CREATE INDEX idx_moderation_review ON review_moderation_queue(review_id);
CREATE INDEX idx_moderation_transaction ON review_moderation_queue(transaction_id);
CREATE INDEX idx_moderation_submitted ON review_moderation_queue(submitted_at);
CREATE INDEX idx_moderation_status ON review_moderation_queue(status, priority, submitted_at);

-- ============================================================================
-- COMMENTS
-- ============================================================================
COMMENT ON TABLE barter_transactions IS 'Tracks all barter transactions between users - required for reviews';
COMMENT ON TABLE user_reputations IS 'Aggregated reputation scores updated when reviews are submitted';
COMMENT ON TABLE user_reviews IS 'All user reviews - hidden initially for blind review period';
COMMENT ON TABLE pending_reviews IS 'Encrypted reviews awaiting reveal (14-day deadline or both submitted)';
COMMENT ON TABLE review_responses IS 'User responses to received reviews for reputation defense';
COMMENT ON TABLE review_appeals IS 'Disputed reviews requiring moderation';
COMMENT ON TABLE review_audit_log IS 'Complete audit trail for abuse detection';
COMMENT ON TABLE reputation_badges IS 'Achievement badges earned by users';
COMMENT ON TABLE review_moderation_queue IS 'Reviews flagged for human review';

-- ============================================================================
-- SUCCESS MESSAGE
-- ============================================================================
DO $$
BEGIN
    RAISE NOTICE '✅ Reviews system migration completed successfully!';
    RAISE NOTICE '📊 Created 9 tables: transactions, reputations, user_reviews, pending_reviews, responses, appeals, audit_log, badges, review_moderation_queue';
    RAISE NOTICE '🔒 All anti-abuse mechanisms are in place';
    RAISE NOTICE '🚀 System is ready for use!';
END $$;
