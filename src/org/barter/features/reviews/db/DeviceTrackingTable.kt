package org.barter.features.reviews.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Tracks device fingerprints used by users for pattern detection.
 * Helps identify users operating multiple accounts from same device.
 */
object DeviceTrackingTable : Table("review_device_tracking") {
    val id = varchar("id", 36)
    val deviceFingerprint = varchar("device_fingerprint", 255).index()
    val userId = varchar("user_id", 255).index()
    val ipAddress = varchar("ip_address", 45).nullable()
    val userAgent = text("user_agent").nullable()
    val action = varchar("action", 50) // login, review_submit, transaction_create, etc.
    val timestamp = timestamp("timestamp").default(Instant.now()).index()
    
    // Composite index for efficient queries
    init {
        index(isUnique = false, deviceFingerprint, userId)
        index(isUnique = false, deviceFingerprint, timestamp)
    }

    override val primaryKey = PrimaryKey(id)
}
