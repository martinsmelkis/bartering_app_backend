package app.bartering.features.reviews.db

import io.propertium.gis.point
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Tracks user profile location changes for abuse detection.
 * Detects location hopping, coordinated multi-account movements,
 * and pattern-based fraud networks without requiring continuous GPS tracking.
 */
object UserLocationChangesTable : Table("user_location_changes") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 255).index()
    val oldLocation = point("old_location", srid = 4326).nullable()
    val newLocation = point("new_location", srid = 4326).nullable()
    val changedAt = timestamp("changed_at").default(Instant.now()).index()

    // Composite index for efficient queries
    init {
        index(isUnique = false, userId, changedAt)
    }

    override val primaryKey = PrimaryKey(id)
}