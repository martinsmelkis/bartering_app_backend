package app.bartering.features.migration.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Database table for storing device migration sessions.
 * Enables secure device-to-device data transfer with ephemeral sessions.
 */
object MigrationSessionsTable : Table("device_migration_sessions") {
    val id = varchar("id", 36)
    val sessionCode = varchar("session_code", 10).uniqueIndex()
    val userId = varchar("user_id", 255).nullable()  // NULL for backward compatibility (target creates first)
    
    // Source device (NULL until source sends payload for backward compatibility)
    val sourceDeviceId = varchar("source_device_id", 64).nullable()
    val sourceDeviceKeyId = varchar("source_device_key_id", 36).nullable()
    val sourcePublicKey = text("source_public_key").nullable()
    
    // Target device
    val targetDeviceId = varchar("target_device_id", 64).nullable()
    val targetDeviceKeyId = varchar("target_device_key_id", 36).nullable()
    val targetPublicKey = text("target_public_key").nullable()
    
    // Session state
    val status = varchar("status", 30)
    val encryptedPayload = text("encrypted_payload").nullable()
    val payloadCreatedAt = timestamp("payload_created_at").nullable()
    
    // Timestamps
    val createdAt = timestamp("created_at").default(Instant.now())
    val expiresAt = timestamp("expires_at")
    val completedAt = timestamp("completed_at").nullable()
    
    // Security
    val attemptCount = integer("attempt_count").default(0)

    override val primaryKey = PrimaryKey(id)
}
