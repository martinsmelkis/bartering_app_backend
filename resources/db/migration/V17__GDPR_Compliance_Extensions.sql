-- V17__GDPR_Hardening_Core.sql
-- GDPR hardening pass:
-- 1) Purchases/premium entitlement schema safety fixes
-- 2) DB-level FK/cascade guarantees for user-linked tables
-- 3) Compliance/DSAR retention helpers and indexes

-- ============================================================================
-- 1) PURCHASES / ENTITLEMENTS SAFETY FIXES
-- ============================================================================

-- Ensure user_premium_entitlements exists with correct columns even if prior migration partially applied.
CREATE TABLE IF NOT EXISTS user_premium_entitlements (
    user_id VARCHAR(50) PRIMARY KEY REFERENCES user_registration_data(id) ON DELETE CASCADE,
    is_premium BOOLEAN NOT NULL DEFAULT FALSE,
    is_lifetime BOOLEAN NOT NULL DEFAULT FALSE,
    granted_by_purchase_id VARCHAR(36),
    granted_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE user_premium_entitlements
    ADD COLUMN IF NOT EXISTS is_premium BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS is_lifetime BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS granted_by_purchase_id VARCHAR(36),
    ADD COLUMN IF NOT EXISTS granted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_type = 'FOREIGN KEY'
          AND table_name = 'user_premium_entitlements'
          AND constraint_name = 'fk_user_premium_entitlements_granted_by_purchase'
    ) THEN
        ALTER TABLE user_premium_entitlements
            ADD CONSTRAINT fk_user_premium_entitlements_granted_by_purchase
            FOREIGN KEY (granted_by_purchase_id)
            REFERENCES user_purchases(id)
            ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_user_premium_entitlements_expires_at
    ON user_premium_entitlements(expires_at);

-- ============================================================================
-- 2) DB-LEVEL FK/CASCADE GUARANTEES FOR USER-LINKED TABLES
-- ============================================================================

-- user_reports: ensure reporter/reported/reviewer are linked to user_registration_data
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_type = 'FOREIGN KEY'
          AND table_name = 'user_reports'
          AND constraint_name = 'fk_user_reports_reporter'
    ) THEN
        ALTER TABLE user_reports
            ADD CONSTRAINT fk_user_reports_reporter
            FOREIGN KEY (reporter_user_id)
            REFERENCES user_registration_data(id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_type = 'FOREIGN KEY'
          AND table_name = 'user_reports'
          AND constraint_name = 'fk_user_reports_reported'
    ) THEN
        ALTER TABLE user_reports
            ADD CONSTRAINT fk_user_reports_reported
            FOREIGN KEY (reported_user_id)
            REFERENCES user_registration_data(id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_type = 'FOREIGN KEY'
          AND table_name = 'user_reports'
          AND constraint_name = 'fk_user_reports_reviewed_by'
    ) THEN
        ALTER TABLE user_reports
            ADD CONSTRAINT fk_user_reports_reviewed_by
            FOREIGN KEY (reviewed_by)
            REFERENCES user_registration_data(id)
            ON DELETE SET NULL;
    END IF;
END $$;

-- review risk tracking tables
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_type = 'FOREIGN KEY'
          AND table_name = 'review_device_tracking'
          AND constraint_name = 'fk_review_device_tracking_user'
    ) THEN
        ALTER TABLE review_device_tracking
            ADD CONSTRAINT fk_review_device_tracking_user
            FOREIGN KEY (user_id)
            REFERENCES user_registration_data(id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_type = 'FOREIGN KEY'
          AND table_name = 'review_ip_tracking'
          AND constraint_name = 'fk_review_ip_tracking_user'
    ) THEN
        ALTER TABLE review_ip_tracking
            ADD CONSTRAINT fk_review_ip_tracking_user
            FOREIGN KEY (user_id)
            REFERENCES user_registration_data(id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_type = 'FOREIGN KEY'
          AND table_name = 'user_location_changes'
          AND constraint_name = 'fk_user_location_changes_user'
    ) THEN
        ALTER TABLE user_location_changes
            ADD CONSTRAINT fk_user_location_changes_user
            FOREIGN KEY (user_id)
            REFERENCES user_registration_data(id)
            ON DELETE CASCADE;
    END IF;
END $$;

-- chat_read_receipts sender/recipient FK + cascade
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_type = 'FOREIGN KEY'
          AND table_name = 'chat_read_receipts'
          AND constraint_name = 'fk_read_receipt_sender'
    ) THEN
        ALTER TABLE chat_read_receipts
            ADD CONSTRAINT fk_read_receipt_sender
            FOREIGN KEY (sender_id)
            REFERENCES user_registration_data(id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_type = 'FOREIGN KEY'
          AND table_name = 'chat_read_receipts'
          AND constraint_name = 'fk_read_receipt_recipient'
    ) THEN
        ALTER TABLE chat_read_receipts
            ADD CONSTRAINT fk_read_receipt_recipient
            FOREIGN KEY (recipient_id)
            REFERENCES user_registration_data(id)
            ON DELETE CASCADE;
    END IF;
END $$;

-- review_moderation_queue reviewer/target/assignee cleanup guarantees
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_type = 'FOREIGN KEY'
          AND table_name = 'review_moderation_queue'
          AND constraint_name = 'fk_review_moderation_queue_reviewer'
    ) THEN
        ALTER TABLE review_moderation_queue
            ADD CONSTRAINT fk_review_moderation_queue_reviewer
            FOREIGN KEY (reviewer_id)
            REFERENCES user_registration_data(id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_type = 'FOREIGN KEY'
          AND table_name = 'review_moderation_queue'
          AND constraint_name = 'fk_review_moderation_queue_target'
    ) THEN
        ALTER TABLE review_moderation_queue
            ADD CONSTRAINT fk_review_moderation_queue_target
            FOREIGN KEY (target_user_id)
            REFERENCES user_registration_data(id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_type = 'FOREIGN KEY'
          AND table_name = 'review_moderation_queue'
          AND constraint_name = 'fk_review_moderation_queue_assigned_to'
    ) THEN
        ALTER TABLE review_moderation_queue
            ADD CONSTRAINT fk_review_moderation_queue_assigned_to
            FOREIGN KEY (assigned_to)
            REFERENCES user_registration_data(id)
            ON DELETE SET NULL;
    END IF;
END $$;

-- ============================================================================
-- 3) COMPLIANCE / DSAR RETENTION INDEXES + HELPER SQL FUNCTIONS
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_compliance_dsr_completed_at
    ON compliance_data_subject_requests(completed_at);

-- Adds event lookup acceleration by actor+time for evidence APIs
CREATE INDEX IF NOT EXISTS idx_compliance_audit_log_actor_created_at
    ON compliance_audit_log(actor_id, created_at DESC);

-- Cleanup function for compliance audit events older than cutoff days.
-- By design this keeps user-level deletion/export history for accountability while removing stale operational logs.
CREATE OR REPLACE FUNCTION cleanup_old_compliance_audit_events(cutoff_days INTEGER DEFAULT 365)
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM compliance_audit_log
    WHERE created_at < NOW() - (cutoff_days || ' days')::INTERVAL
      AND event_type NOT IN (
          'ACCOUNT_DELETION_REQUESTED',
          'ACCOUNT_DELETION_COMPLETED',
          'DATA_EXPORT_REQUESTED',
          'DATA_EXPORT_COMPLETED',
          'LEGAL_HOLD_APPLIED',
          'LEGAL_HOLD_RELEASED',
          'CONSENT_UPDATED'
      );

    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- Cleanup function for DSAR case records older than cutoff days after completion/rejection/cancel.
CREATE OR REPLACE FUNCTION cleanup_old_completed_dsar_requests(cutoff_days INTEGER DEFAULT 365)
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM compliance_data_subject_requests
    WHERE status IN ('completed', 'rejected', 'cancelled')
      AND COALESCE(completed_at, updated_at, created_at) < NOW() - (cutoff_days || ' days')::INTERVAL;

    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION cleanup_old_compliance_audit_events(INTEGER) IS
    'Deletes stale operational compliance audit events while preserving key GDPR accountability events';

COMMENT ON FUNCTION cleanup_old_completed_dsar_requests(INTEGER) IS
    'Deletes old completed/rejected/cancelled DSAR case records after retention window';


-- Enforceable operational tracking for backup/external storage erasure workflows.

CREATE TABLE IF NOT EXISTS compliance_erasure_tasks (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    task_type VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'pending',

    storage_scope VARCHAR(32) NOT NULL,
    target_ref TEXT,

    requested_by VARCHAR(255),
    handled_by VARCHAR(255),

    due_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    notes TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_compliance_erasure_task_status CHECK (status IN (
        'pending', 'in_progress', 'completed', 'failed', 'skipped'
    )),
    CONSTRAINT chk_compliance_erasure_task_type CHECK (task_type IN (
        'local_uploads', 'cloud_media', 'backup_retention', 'backup_verification'
    )),
    CONSTRAINT chk_compliance_erasure_storage_scope CHECK (storage_scope IN (
        'local', 'cloud', 'backup'
    ))
);

CREATE INDEX IF NOT EXISTS idx_compliance_erasure_tasks_user
    ON compliance_erasure_tasks(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_compliance_erasure_tasks_status
    ON compliance_erasure_tasks(status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_compliance_erasure_tasks_due
    ON compliance_erasure_tasks(due_at)
    WHERE status IN ('pending', 'in_progress');

COMMENT ON TABLE compliance_erasure_tasks IS
    'Operational task queue for GDPR erasure follow-ups in non-primary stores (uploads, cloud media, backups).';

COMMENT ON COLUMN compliance_erasure_tasks.task_type IS
    'local_uploads | cloud_media | backup_retention | backup_verification';

COMMENT ON COLUMN compliance_erasure_tasks.storage_scope IS
    'Storage domain where erasure action must be executed: local, cloud, backup';


-- Adds formal retention policy registry and Article 30 ROPA register.

-- ============================================================================
-- RETENTION POLICY REGISTER
-- ============================================================================

CREATE TABLE IF NOT EXISTS compliance_retention_policy_register (
    id BIGSERIAL PRIMARY KEY,
    data_domain VARCHAR(80) NOT NULL,
    table_name VARCHAR(120) NOT NULL,
    processing_purpose TEXT NOT NULL,
    legal_basis VARCHAR(120) NOT NULL,
    retention_period_days INTEGER NOT NULL,
    deletion_trigger VARCHAR(120) NOT NULL,
    deletion_method VARCHAR(120) NOT NULL,
    exception_rules TEXT,
    owner_role VARCHAR(120),
    enforcement_job VARCHAR(120),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_retention_period_days_positive CHECK (retention_period_days >= 0),
    CONSTRAINT uq_retention_policy_table UNIQUE (table_name)
);

CREATE INDEX IF NOT EXISTS idx_retention_policy_domain
    ON compliance_retention_policy_register(data_domain, is_active);

CREATE INDEX IF NOT EXISTS idx_retention_policy_table
    ON compliance_retention_policy_register(table_name, is_active);

COMMENT ON TABLE compliance_retention_policy_register IS
    'Formal retention schedule registry per data domain/table for GDPR accountability and audits.';

COMMENT ON COLUMN compliance_retention_policy_register.legal_basis IS
    'GDPR legal basis for storage/processing (e.g., consent, contract, legal obligation, legitimate interests).';

-- ============================================================================
-- ARTICLE 30 ROPA REGISTER (Record of Processing Activities)
-- ============================================================================

CREATE TABLE IF NOT EXISTS compliance_ropa_register (
    id BIGSERIAL PRIMARY KEY,
    activity_key VARCHAR(120) NOT NULL UNIQUE,
    activity_name VARCHAR(255) NOT NULL,
    controller_name VARCHAR(255) NOT NULL,
    controller_contact VARCHAR(255),
    dpo_contact VARCHAR(255),

    processing_purposes TEXT NOT NULL,
    data_subject_categories TEXT NOT NULL,
    personal_data_categories TEXT NOT NULL,
    recipient_categories TEXT,
    third_country_transfers TEXT,
    safeguards_description TEXT,

    legal_basis VARCHAR(255) NOT NULL,
    retention_summary TEXT NOT NULL,
    toms_summary TEXT NOT NULL,

    source_systems TEXT,
    processors TEXT,
    joint_controllers TEXT,

    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    review_due_at TIMESTAMPTZ,
    last_reviewed_at TIMESTAMPTZ,

    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ropa_active_review_due
    ON compliance_ropa_register(is_active, review_due_at);

COMMENT ON TABLE compliance_ropa_register IS
    'Article 30 GDPR Record of Processing Activities (ROPA) register.';

COMMENT ON COLUMN compliance_ropa_register.toms_summary IS
    'Summary of technical and organizational measures (TOMs) used for this processing activity.';


-- Baseline seed data for:
-- 1) compliance_retention_policy_register
-- 2) compliance_ropa_register (Article 30)

-- ============================================================================
-- RETENTION POLICY BASELINE
-- ============================================================================

INSERT INTO compliance_retention_policy_register (
    data_domain, table_name, processing_purpose, legal_basis,
    retention_period_days, deletion_trigger, deletion_method,
    exception_rules, owner_role, enforcement_job, is_active,
    created_by, updated_by
) VALUES
    (
        'identity',
        'user_registration_data',
        'Account identity and authentication linkage',
        'contract',
        0,
        'account_deletion',
        'hard_delete_cascade',
        'May be delayed by active legal hold',
        'backend_owner',
        'authentication_delete_user',
        TRUE,
        'system_seed',
        'system_seed'
    ),
    (
        'profile',
        'user_profiles',
        'User profile and matching metadata',
        'contract',
        0,
        'account_deletion',
        'hard_delete_cascade',
        'May be delayed by active legal hold',
        'backend_owner',
        'authentication_delete_user',
        TRUE,
        'system_seed',
        'system_seed'
    ),
    (
        'consent',
        'user_privacy_consents',
        'Privacy and processing consent state tracking',
        'legal_obligation',
        0,
        'account_deletion',
        'hard_delete_cascade',
        'Consent evidence may be retained in compliance_audit_log per policy',
        'compliance_officer',
        'authentication_delete_user',
        TRUE,
        'system_seed',
        'system_seed'
    ),
    (
        'chat',
        'offline_messages',
        'Temporary message delivery for offline users',
        'contract',
        7,
        'time_based_retention',
        'scheduled_purge',
        'Excluded when legal hold is active',
        'backend_owner',
        'retention_orchestrator',
        TRUE,
        'system_seed',
        'system_seed'
    ),
    (
        'chat',
        'chat_read_receipts',
        'Message delivery/read-state tracking',
        'contract',
        30,
        'time_based_retention',
        'scheduled_purge',
        'Excluded when legal hold is active',
        'backend_owner',
        'retention_orchestrator',
        TRUE,
        'system_seed',
        'system_seed'
    ),
    (
        'analytics',
        'chat_response_times',
        'Service quality analytics and badge calculation support',
        'legitimate_interests',
        90,
        'time_based_retention',
        'scheduled_purge',
        'Excluded when legal hold is active',
        'product_analytics_owner',
        'retention_orchestrator',
        TRUE,
        'system_seed',
        'system_seed'
    ),
    (
        'security',
        'review_device_tracking',
        'Abuse prevention and fraud/risk investigation',
        'legitimate_interests',
        90,
        'time_based_retention',
        'scheduled_purge',
        'Excluded when legal hold is active',
        'trust_safety_owner',
        'retention_orchestrator',
        TRUE,
        'system_seed',
        'system_seed'
    ),
    (
        'security',
        'review_ip_tracking',
        'Abuse prevention and fraud/risk investigation',
        'legitimate_interests',
        90,
        'time_based_retention',
        'scheduled_purge',
        'Excluded when legal hold is active',
        'trust_safety_owner',
        'retention_orchestrator',
        TRUE,
        'system_seed',
        'system_seed'
    ),
    (
        'security',
        'user_location_changes',
        'Location anomaly/risk pattern analysis',
        'legitimate_interests',
        90,
        'time_based_retention',
        'scheduled_purge',
        'Excluded when legal hold is active',
        'trust_safety_owner',
        'retention_orchestrator',
        TRUE,
        'system_seed',
        'system_seed'
    ),
    (
        'marketplace',
        'user_postings',
        'Marketplace listing lifecycle',
        'contract',
        30,
        'expiry_plus_grace',
        'status_then_hard_delete',
        'Excluded when legal hold is active',
        'marketplace_owner',
        'retention_orchestrator',
        TRUE,
        'system_seed',
        'system_seed'
    ),
    (
        'moderation',
        'user_reports',
        'Trust & safety moderation records',
        'legitimate_interests',
        365,
        'policy_retention',
        'manual_or_scheduled_purge',
        'May be retained longer under legal hold or legal obligation',
        'trust_safety_owner',
        'compliance_manual_review',
        TRUE,
        'system_seed',
        'system_seed'
    ),
    (
        'compliance',
        'compliance_data_subject_requests',
        'DSAR lifecycle evidence and SLA tracking',
        'legal_obligation',
        365,
        'closed_request_age',
        'scheduled_purge',
        'Open cases are never auto-purged',
        'compliance_officer',
        'retention_orchestrator',
        TRUE,
        'system_seed',
        'system_seed'
    ),
    (
        'compliance',
        'compliance_audit_log',
        'Compliance accountability and event traceability',
        'legal_obligation',
        365,
        'time_based_retention',
        'scheduled_purge_with_protected_events',
        'Core accountability events are retained longer by exclusion policy',
        'compliance_officer',
        'retention_orchestrator',
        TRUE,
        'system_seed',
        'system_seed'
    ),
    (
        'compliance',
        'compliance_erasure_tasks',
        'Operational follow-up for uploads/cloud/backups erasure',
        'legal_obligation',
        365,
        'task_closure_age',
        'manual_or_scheduled_purge',
        'Keep failed tasks until remediated',
        'compliance_officer',
        'compliance_admin_workflow',
        TRUE,
        'system_seed',
        'system_seed'
    )
ON CONFLICT (table_name) DO UPDATE
SET
    data_domain = EXCLUDED.data_domain,
    processing_purpose = EXCLUDED.processing_purpose,
    legal_basis = EXCLUDED.legal_basis,
    retention_period_days = EXCLUDED.retention_period_days,
    deletion_trigger = EXCLUDED.deletion_trigger,
    deletion_method = EXCLUDED.deletion_method,
    exception_rules = EXCLUDED.exception_rules,
    owner_role = EXCLUDED.owner_role,
    enforcement_job = EXCLUDED.enforcement_job,
    is_active = EXCLUDED.is_active,
    updated_by = EXCLUDED.updated_by,
    updated_at = NOW();

-- ============================================================================
-- ARTICLE 30 ROPA BASELINE
-- ============================================================================

INSERT INTO compliance_ropa_register (
    activity_key, activity_name, controller_name, controller_contact, dpo_contact,
    processing_purposes, data_subject_categories, personal_data_categories,
    recipient_categories, third_country_transfers, safeguards_description,
    legal_basis, retention_summary, toms_summary,
    source_systems, processors, joint_controllers,
    is_active, review_due_at, last_reviewed_at,
    created_by, updated_by
) VALUES
    (
        'user_account_management',
        'User account management and authentication',
        'Barter App Backend Controller',
        'privacy@bartering.app',
        'dpo@bartering.app',
        'Account creation, authentication, account security, account deletion',
        'Registered users',
        'User identifiers, public key, account metadata, consent state',
        'Internal backend services, infrastructure operators',
        'Potential infrastructure transfer depending on cloud region',
        'Signature verification, access control, audit logging, key-based authentication',
        'contract; legal_obligation',
        'Primary account records retained until user deletion; compliance evidence retained per policy register',
        'Access controls, role checks, event logging, TLS in transit, database constraints',
        'main_backend',
        'Hosting provider, managed database provider',
        NULL,
        TRUE,
        NOW() + INTERVAL '180 days',
        NOW(),
        'system_seed',
        'system_seed'
    ),
    (
        'marketplace_and_matching',
        'Marketplace postings and matching',
        'Barter App Backend Controller',
        'privacy@bartering.app',
        'dpo@bartering.app',
        'Provide barter posting functionality and profile/posting matching',
        'Registered users',
        'Profile data, posting text, optional images, location (if consented), matching metadata',
        'Other users of the platform, federated servers where consent and policy permit',
        'Possible cross-border transfer via federation endpoints',
        'Consent checks for location/federation, scoped sharing, legal hold controls',
        'contract; consent; legitimate_interests',
        'Postings and matching metadata retained by lifecycle + retention policy register',
        'Consent-gated filters, rate limiting, scoped API access, retention orchestrator',
        'main_backend,federation_module',
        'Image storage provider (local/cloud)',
        NULL,
        TRUE,
        NOW() + INTERVAL '180 days',
        NOW(),
        'system_seed',
        'system_seed'
    ),
    (
        'trust_safety_and_moderation',
        'Trust, safety and abuse prevention',
        'Barter App Backend Controller',
        'privacy@bartering.app',
        'dpo@bartering.app',
        'Fraud detection, abuse detection, moderation decisions, user reporting',
        'Registered users, reported users, moderators',
        'Device fingerprints, IP metadata, location changes, reports, moderation notes',
        'Internal moderation/compliance roles',
        'No intended transfer outside platform controls except infrastructure operations',
        'Risk scoring, retained evidence, legal hold support, controlled admin access',
        'legitimate_interests; legal_obligation',
        'Risk telemetry retained for limited windows (e.g., 90 days) per register; moderation retained per policy',
        'Network restrictions, admin authorization checks, audit logging, retention cleanup',
        'reviews_module,relationships_module,compliance_module',
        'Infrastructure hosting and security tooling providers',
        NULL,
        TRUE,
        NOW() + INTERVAL '180 days',
        NOW(),
        'system_seed',
        'system_seed'
    ),
    (
        'gdpr_compliance_operations',
        'GDPR rights handling and compliance governance',
        'Barter App Backend Controller',
        'privacy@bartering.app',
        'dpo@bartering.app',
        'Handle DSAR requests, legal holds, audit traceability, retention governance and erasure follow-up',
        'Registered users, requesters, compliance admins',
        'DSAR case records, compliance audit events, legal hold records, policy/ROPA metadata',
        'Internal compliance and authorized administrators',
        'No intentional external transfer beyond required infrastructure operations',
        'Audit evidence, legal hold gates, controlled admin routes, retention cleanup',
        'legal_obligation',
        'Compliance records retained according to policy register with protected accountability events',
        'Admin allowlist controls, signature auth, immutable-style audit event practices',
        'compliance_module',
        'Infrastructure provider(s)',
        NULL,
        TRUE,
        NOW() + INTERVAL '180 days',
        NOW(),
        'system_seed',
        'system_seed'
    )
ON CONFLICT (activity_key) DO UPDATE
SET
    activity_name = EXCLUDED.activity_name,
    controller_name = EXCLUDED.controller_name,
    controller_contact = EXCLUDED.controller_contact,
    dpo_contact = EXCLUDED.dpo_contact,
    processing_purposes = EXCLUDED.processing_purposes,
    data_subject_categories = EXCLUDED.data_subject_categories,
    personal_data_categories = EXCLUDED.personal_data_categories,
    recipient_categories = EXCLUDED.recipient_categories,
    third_country_transfers = EXCLUDED.third_country_transfers,
    safeguards_description = EXCLUDED.safeguards_description,
    legal_basis = EXCLUDED.legal_basis,
    retention_summary = EXCLUDED.retention_summary,
    toms_summary = EXCLUDED.toms_summary,
    source_systems = EXCLUDED.source_systems,
    processors = EXCLUDED.processors,
    joint_controllers = EXCLUDED.joint_controllers,
    is_active = EXCLUDED.is_active,
    review_due_at = EXCLUDED.review_due_at,
    last_reviewed_at = EXCLUDED.last_reviewed_at,
    updated_by = EXCLUDED.updated_by,
    updated_at = NOW();


-- Basic Article 33/34 breach workflow foundation:
-- 1) Incident register (detection, status, 72h deadline tracking)
-- 2) Affected-user notification queue/status tracking

CREATE TABLE IF NOT EXISTS compliance_security_incidents (
    id BIGSERIAL PRIMARY KEY,
    incident_key VARCHAR(80) NOT NULL UNIQUE,
    incident_type VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'detected',

    summary TEXT NOT NULL,
    detection_source VARCHAR(120),
    affected_systems TEXT,

    detected_at TIMESTAMPTZ NOT NULL,
    contained_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,

    risk_to_rights BOOLEAN NOT NULL DEFAULT TRUE,
    regulator_notification_required BOOLEAN NOT NULL DEFAULT TRUE,
    regulator_notified_at TIMESTAMPTZ,
    notification_deadline_at TIMESTAMPTZ NOT NULL,

    likely_consequences TEXT,
    mitigation_steps TEXT,

    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_security_incident_severity
        CHECK (severity IN ('low', 'medium', 'high', 'critical')),
    CONSTRAINT chk_security_incident_status
        CHECK (status IN ('detected', 'triaging', 'contained', 'notified', 'resolved', 'closed'))
);

CREATE INDEX IF NOT EXISTS idx_security_incidents_status
    ON compliance_security_incidents(status, detected_at DESC);

CREATE INDEX IF NOT EXISTS idx_security_incidents_notification_deadline
    ON compliance_security_incidents(regulator_notification_required, regulator_notified_at, notification_deadline_at);

COMMENT ON TABLE compliance_security_incidents IS
    'Security breach/incident register for GDPR Article 33/34 workflows.';

COMMENT ON COLUMN compliance_security_incidents.notification_deadline_at IS
    'Supervisory authority notification deadline (typically detected_at + 72 hours).';


CREATE TABLE IF NOT EXISTS compliance_security_incident_users (
    id BIGSERIAL PRIMARY KEY,
    incident_id BIGINT NOT NULL REFERENCES compliance_security_incidents(id) ON DELETE CASCADE,
    user_id VARCHAR(255) NOT NULL,

    notification_status VARCHAR(24) NOT NULL DEFAULT 'pending',
    notified_at TIMESTAMPTZ,
    last_error TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_security_incident_user UNIQUE (incident_id, user_id),
    CONSTRAINT chk_security_incident_user_notification_status
        CHECK (notification_status IN ('pending', 'sent', 'failed', 'skipped'))
);

CREATE INDEX IF NOT EXISTS idx_security_incident_users_incident
    ON compliance_security_incident_users(incident_id, notification_status);

CREATE INDEX IF NOT EXISTS idx_security_incident_users_user
    ON compliance_security_incident_users(user_id);

COMMENT ON TABLE compliance_security_incident_users IS
    'Affected users linked to security incidents with notification delivery status for Article 34.';


-- Basic legal acceptance tracking for Terms & Conditions.

ALTER TABLE user_privacy_consents
    ADD COLUMN IF NOT EXISTS terms_conditions_version VARCHAR(50),
    ADD COLUMN IF NOT EXISTS terms_conditions_accepted_at TIMESTAMPTZ;

COMMENT ON COLUMN user_privacy_consents.terms_conditions_version IS
    'Version identifier of Terms & Conditions accepted by the user';

COMMENT ON COLUMN user_privacy_consents.terms_conditions_accepted_at IS
    'Timestamp when user accepted the referenced Terms & Conditions version';