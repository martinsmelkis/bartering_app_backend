package app.bartering.features.migration.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Unified migration sessions table supporting device-to-device and email recovery.
 */
object MigrationSessionsTable : Table("device_migration_sessions") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 255).references(app.bartering.features.profile.db.UserRegistrationDataTable.id)

    // Migration type: 'device_to_device' or 'email_recovery'
    val type = varchar("type", 30).default("device_to_device")

    // Session identification
    val sessionCode = varchar("session_code", 10).nullable()           // For device-to-device
    val recoveryCodeHash = varchar("recovery_code_hash", 255).nullable() // For email recovery

    // Source device (NULL for email recovery)
    val sourceDeviceId = varchar("source_device_id", 64).nullable()
    val sourceDeviceKeyId = varchar("source_device_key_id", 36).nullable()
    val sourcePublicKey = text("source_public_key").nullable()

    // Target/New device
    val targetDeviceId = varchar("target_device_id", 64).nullable()
    val targetDeviceKeyId = varchar("target_device_key_id", 36).nullable()
    val targetPublicKey = text("target_public_key").nullable()
    val newDeviceId = varchar("new_device_id", 64).nullable()              // Alias for email recovery
    val newDevicePublicKey = text("new_device_public_key").nullable()

    // Email recovery fields
    val contactEmail = varchar("contact_email", 255).nullable()

    // Session state
    val status = varchar("status", 30).default("pending")

    // Data transfer (device-to-device only)
    val encryptedPayload = text("encrypted_payload").nullable()
    val payloadCreatedAt = timestamp("payload_created_at").nullable()

    // Security
    val attemptCount = integer("attempt_count").default(0)
    val maxAttempts = integer("max_attempts").default(5)
    val ipAddress = varchar("ip_address", 45).nullable()

    // Timestamps
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
    val verifiedAt = timestamp("verified_at").nullable()
    val completedAt = timestamp("completed_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Unified migration audit log for both device-to-device and email recovery.
 */
object MigrationAuditLogTable : Table("device_migration_audit_log") {
    val id = long("id").autoIncrement()
    val eventType = varchar("event_type", 50)
    val migrationType = varchar("migration_type", 30)  // 'device_to_device' or 'email_recovery'
    val userId = varchar("user_id", 255).references(app.bartering.features.profile.db.UserRegistrationDataTable.id)
    val sessionId = varchar("session_id", 36).nullable()
    val ipAddress = varchar("ip_address", 45).nullable()
    val details = text("details").nullable() // JSON
    val riskScore = integer("risk_score").default(0)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
