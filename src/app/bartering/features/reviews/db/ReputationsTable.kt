package app.bartering.features.reviews.db

import app.bartering.features.profile.db.UserRegistrationDataTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Core reputation table storing aggregated reputation scores for users.
 * This table is updated whenever new reviews are submitted.
 */
object ReputationsTable : Table("user_reputations") {
    val userId = reference("user_id", UserRegistrationDataTable.id)
    val averageRating = decimal("average_rating", 3, 2) // e.g., 4.75
    val totalReviews = integer("total_reviews").default(0)
    val verifiedReviews = integer("verified_reviews").default(0)
    val tradeDiversityScore = decimal("trade_diversity_score", 3, 2).default(0.5.toBigDecimal())
    val trustLevel = varchar("trust_level", 50).default("new")
    val lastUpdated = timestamp("last_updated").default(Instant.now())

    override val primaryKey = PrimaryKey(userId)
}
