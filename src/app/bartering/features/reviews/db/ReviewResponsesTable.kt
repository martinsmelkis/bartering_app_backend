package app.bartering.features.reviews.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Allows users to respond to reviews they've received.
 * Provides a defense mechanism against unfair reviews.
 */
object ReviewResponsesTable : Table("review_responses") {
    val reviewId = reference("review_id", ReviewsTable.id)
    val responseText = text("response_text")
    val respondedAt = timestamp("responded_at").default(Instant.now())

    override val primaryKey = PrimaryKey(reviewId)
}
