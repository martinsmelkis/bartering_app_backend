-- Drop legacy unused tables
-- review_appeals is the active moderation/appeal workflow table
-- wallet coin limits are currently not used by runtime logic
DROP TABLE IF EXISTS review_responses;
DROP TABLE IF EXISTS wallet_coin_limits;

-- Consolidate review_audit_log and review_moderation_queue into moderation_audit_log

CREATE TABLE IF NOT EXISTS moderation_audit_log (
    id VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    entity_type VARCHAR(80),
    entity_id VARCHAR(36),
    review_id VARCHAR(36),
    transaction_id VARCHAR(36),
    actor_user_id VARCHAR(255),
    target_user_id VARCHAR(255),
    assigned_to VARCHAR(255),
    status VARCHAR(50),
    priority VARCHAR(50),
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_moderation_audit_review ON moderation_audit_log(review_id);
CREATE INDEX IF NOT EXISTS idx_moderation_audit_transaction ON moderation_audit_log(transaction_id);
CREATE INDEX IF NOT EXISTS idx_moderation_audit_actor ON moderation_audit_log(actor_user_id);
CREATE INDEX IF NOT EXISTS idx_moderation_audit_target ON moderation_audit_log(target_user_id);
CREATE INDEX IF NOT EXISTS idx_moderation_audit_assigned ON moderation_audit_log(assigned_to);
CREATE INDEX IF NOT EXISTS idx_moderation_audit_created ON moderation_audit_log(created_at);
CREATE INDEX IF NOT EXISTS idx_moderation_audit_entity ON moderation_audit_log(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_moderation_audit_status ON moderation_audit_log(status, priority, created_at);

-- Backfill from review_audit_log (review actions, including appeals)
INSERT INTO moderation_audit_log (
    id,
    event_type,
    entity_type,
    entity_id,
    review_id,
    transaction_id,
    actor_user_id,
    target_user_id,
    assigned_to,
    status,
    priority,
    metadata,
    created_at
)
SELECT
    ral.id,
    ral.action,
    'review',
    ral.related_review_id,
    ral.related_review_id,
    NULL,
    ral.user_id,
    NULL,
    NULL,
    NULL,
    NULL,
    COALESCE(ral.metadata, '{}'::jsonb),
    ral.timestamp
FROM review_audit_log ral
ON CONFLICT (id) DO NOTHING;

-- Backfill from review_moderation_queue (review and report moderation state changes)
INSERT INTO moderation_audit_log (
    id,
    event_type,
    entity_type,
    entity_id,
    review_id,
    transaction_id,
    actor_user_id,
    target_user_id,
    assigned_to,
    status,
    priority,
    metadata,
    created_at
)
SELECT
    rmq.id,
    'moderation_queue_entry',
    'review_moderation',
    rmq.review_id::text,
    rmq.review_id::text,
    rmq.transaction_id::text,
    rmq.reviewer_id,
    rmq.target_user_id,
    rmq.assigned_to,
    rmq.status,
    rmq.priority,
    jsonb_build_object(
        'flag_reason', rmq.flag_reason,
        'risk_factors', COALESCE(rmq.risk_factors, '[]'::jsonb),
        'resolved_at', rmq.resolved_at
    ),
    rmq.submitted_at
FROM review_moderation_queue rmq
ON CONFLICT (id) DO NOTHING;

-- Remove old tables after successful backfill
DROP TABLE IF EXISTS review_moderation_queue;
DROP TABLE IF EXISTS review_audit_log;

-- Unify review risk tracking tables into review_risk_tracking with entry_type separation

CREATE TABLE IF NOT EXISTS review_risk_tracking (
    id VARCHAR(36) PRIMARY KEY,
    entry_type VARCHAR(30) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    action VARCHAR(50),

    device_fingerprint VARCHAR(255),
    user_agent TEXT,

    ip_address VARCHAR(45),
    is_vpn BOOLEAN NOT NULL DEFAULT FALSE,
    is_proxy BOOLEAN NOT NULL DEFAULT FALSE,
    is_tor BOOLEAN NOT NULL DEFAULT FALSE,
    is_datacenter BOOLEAN NOT NULL DEFAULT FALSE,
    country VARCHAR(2),
    city VARCHAR(100),
    isp VARCHAR(255),

    old_location geometry(Point, 4326),
    new_location geometry(Point, 4326),

    occurred_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_review_risk_tracking_entry_type
        CHECK (entry_type IN ('device', 'ip', 'location_change'))
);

CREATE INDEX IF NOT EXISTS idx_review_risk_tracking_type ON review_risk_tracking(entry_type);
CREATE INDEX IF NOT EXISTS idx_review_risk_tracking_user ON review_risk_tracking(user_id);
CREATE INDEX IF NOT EXISTS idx_review_risk_tracking_type_user ON review_risk_tracking(entry_type, user_id);
CREATE INDEX IF NOT EXISTS idx_review_risk_tracking_type_occurred ON review_risk_tracking(entry_type, occurred_at);
CREATE INDEX IF NOT EXISTS idx_review_risk_tracking_device ON review_risk_tracking(entry_type, device_fingerprint, occurred_at);
CREATE INDEX IF NOT EXISTS idx_review_risk_tracking_ip ON review_risk_tracking(entry_type, ip_address, occurred_at);

-- Preserve GDPR cascade semantics from legacy tables
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE table_schema = current_schema()
          AND table_name = 'review_risk_tracking'
          AND constraint_name = 'fk_review_risk_tracking_user'
    ) THEN
        ALTER TABLE review_risk_tracking
            ADD CONSTRAINT fk_review_risk_tracking_user
            FOREIGN KEY (user_id)
            REFERENCES user_registration_data(id)
            ON DELETE CASCADE;
    END IF;
END $$;

INSERT INTO review_risk_tracking (
    id, entry_type, user_id, action,
    device_fingerprint, user_agent, ip_address,
    occurred_at
)
SELECT
    id,
    'device',
    user_id,
    action,
    device_fingerprint,
    user_agent,
    ip_address,
    timestamp
FROM review_device_tracking
ON CONFLICT (id) DO NOTHING;

INSERT INTO review_risk_tracking (
    id, entry_type, user_id, action,
    ip_address,
    is_vpn, is_proxy, is_tor, is_datacenter,
    country, city, isp,
    occurred_at
)
SELECT
    id,
    'ip',
    user_id,
    action,
    ip_address,
    is_vpn, is_proxy, is_tor, is_datacenter,
    country, city, isp,
    timestamp
FROM review_ip_tracking
ON CONFLICT (id) DO NOTHING;

INSERT INTO review_risk_tracking (
    id, entry_type, user_id,
    old_location, new_location,
    occurred_at
)
SELECT
    id,
    'location_change',
    user_id,
    old_location,
    new_location,
    changed_at
FROM user_location_changes
ON CONFLICT (id) DO NOTHING;

DROP TABLE IF EXISTS review_device_tracking;
DROP TABLE IF EXISTS review_ip_tracking;
DROP TABLE IF EXISTS user_location_changes;

-- Update retention register to unified table name
DO $$
DECLARE
    existing_id BIGINT;
BEGIN
    SELECT id INTO existing_id
    FROM compliance_retention_policy_register
    WHERE table_name = 'review_risk_tracking'
    LIMIT 1;

    IF existing_id IS NULL THEN
        INSERT INTO compliance_retention_policy_register (
            data_domain,
            table_name,
            processing_purpose,
            legal_basis,
            retention_period_days,
            deletion_trigger,
            deletion_method,
            exception_rules,
            owner_role,
            enforcement_job,
            is_active,
            created_by,
            updated_by,
            created_at,
            updated_at
        )
        VALUES (
            'security',
            'review_risk_tracking',
            'Abuse prevention and fraud/risk investigation (device, IP, and location change telemetry)',
            'legitimate_interests',
            90,
            'time_based_retention',
            'scheduled_purge',
            'Excluded when legal hold is active',
            'trust_safety_owner',
            'retention_orchestrator',
            TRUE,
            'system_migration_v22',
            'system_migration_v22',
            NOW(),
            NOW()
        );
    END IF;

    DELETE FROM compliance_retention_policy_register
    WHERE table_name IN ('review_device_tracking', 'review_ip_tracking', 'user_location_changes');
END $$;
