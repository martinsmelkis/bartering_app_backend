package app.bartering.features.reviews.db

import app.bartering.model.point
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * Unified review risk tracking table with separation by entryType.
 * Includes device tracking, ip tracking, and user location changes.
 */
object ReviewRiskTrackingTable : Table("review_risk_tracking") {
    val id = varchar("id", 36)
    val entryType = varchar("entry_type", 30).index() // device | ip | location_change

    val userId = varchar("user_id", 255).index()
    val action = varchar("action", 50).nullable()

    // Device-related fields
    val deviceFingerprint = varchar("device_fingerprint", 255).nullable().index()
    val userAgent = text("user_agent").nullable()

    // IP-related fields
    val ipAddress = varchar("ip_address", 45).nullable().index()
    val isVpn = bool("is_vpn").default(false)
    val isProxy = bool("is_proxy").default(false)
    val isTor = bool("is_tor").default(false)
    val isDataCenter = bool("is_datacenter").default(false)
    val country = varchar("country", 2).nullable()
    val city = varchar("city", 100).nullable()
    val isp = varchar("isp", 255).nullable()

    // Location change fields
    val oldLocation = point("old_location", srid = 4326).nullable()
    val newLocation = point("new_location", srid = 4326).nullable()

    val occurredAt = timestamp("occurred_at").default(Instant.now()).index()

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_review_risk_tracking_type_user", false, entryType, userId)
        index("idx_review_risk_tracking_type_occurred", false, entryType, occurredAt)
        index("idx_review_risk_tracking_device", false, entryType, deviceFingerprint, occurredAt)
        index("idx_review_risk_tracking_ip", false, entryType, ipAddress, occurredAt)
    }
}
