package app.bartering.features.authentication.db

import app.bartering.features.profile.db.UserRegistrationDataTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Database table for storing device-specific public keys.
 * Enables multi-device authentication where each device has its own ECDSA keypair.
 */
object UserDeviceKeysTable : Table("user_device_keys") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 255).references(UserRegistrationDataTable.id)
    val deviceId = varchar("device_id", 64)
    val publicKey = text("public_key")
    val deviceName = varchar("device_name", 100).nullable()
    val deviceType = varchar("device_type", 20).nullable()
    val platform = varchar("platform", 20).nullable()
    val isActive = bool("is_active").default(true)
    val lastUsedAt = timestamp("last_used_at").default(Instant.now())
    val createdAt = timestamp("created_at").default(Instant.now())
    val deactivatedAt = timestamp("deactivated_at").nullable()
    val deactivatedReason = varchar("deactivated_reason", 50).nullable()

    override val primaryKey = PrimaryKey(id)
}
