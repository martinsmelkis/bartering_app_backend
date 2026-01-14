package app.bartering.features.reviews.db

import app.bartering.features.profile.db.UserRegistrationDataTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Tracks which reputation badges each user has earned.
 */
object ReputationBadgesTable : Table("reputation_badges") {
    val userId = reference("user_id", UserRegistrationDataTable.id).index()
    val badgeType = varchar("badge_type", 50) // e.g., "top_rated", "verified_business"
    val earnedAt = timestamp("earned_at").default(Instant.now())
    val expiresAt = timestamp("expires_at").nullable() // Some badges may expire

    override val primaryKey = PrimaryKey(userId, badgeType)
}
