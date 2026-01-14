package app.bartering.features.profile.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Database table for tracking user presence/activity
 */
object UserPresenceTable : Table("user_presence") {
    val userId = varchar("user_id", 255).references(UserRegistrationDataTable.id)
    val lastActivityAt = timestamp("last_activity_at").default(Instant.now())
    val lastActivityType = varchar("last_activity_type", 50).nullable()
    val updatedAt = timestamp("updated_at").default(Instant.now())

    override val primaryKey = PrimaryKey(userId)
}
