package app.bartering.features.nearbyalerts.db

import app.bartering.features.profile.db.UserRegistrationDataTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

object NearbyUserAlertsTable : Table("nearby_user_alerts") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 255).references(UserRegistrationDataTable.id)
    val latitude = double("latitude")
    val longitude = double("longitude")
    val radiusMeters = double("radius_meters").default(10_000.0)
    val minUserCount = integer("min_user_count").default(5)
    val enabled = bool("enabled").default(true)
    val lastCheckedAt = timestamp("last_checked_at").nullable()
    val lastNotifiedAt = timestamp("last_notified_at").nullable()
    val lastNearbyUserCount = integer("last_nearby_user_count").default(0)
    val createdAt = timestamp("created_at").default(Instant.now())
    val updatedAt = timestamp("updated_at").default(Instant.now())

    override val primaryKey = PrimaryKey(id)
}
