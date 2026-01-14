package app.bartering.features.reviews.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Tracks IP addresses used by users for pattern detection.
 * Helps identify VPN usage, proxy detection, and multi-account abuse.
 */
object IpTrackingTable : Table("review_ip_tracking") {
    val id = varchar("id", 36)
    val ipAddress = varchar("ip_address", 45).index()
    val userId = varchar("user_id", 255).index()
    val action = varchar("action", 50) // login, review_submit, transaction_create, etc.
    val timestamp = timestamp("timestamp").default(Instant.now()).index()
    
    // IP metadata for analysis
    val isVpn = bool("is_vpn").default(false)
    val isProxy = bool("is_proxy").default(false)
    val isTor = bool("is_tor").default(false)
    val isDataCenter = bool("is_datacenter").default(false)
    val country = varchar("country", 2).nullable() // ISO country code
    val city = varchar("city", 100).nullable()
    val isp = varchar("isp", 255).nullable()
    
    // Composite index for efficient queries
    init {
        index(isUnique = false, ipAddress, userId)
        index(isUnique = false, ipAddress, timestamp)
    }

    override val primaryKey = PrimaryKey(id)
}
