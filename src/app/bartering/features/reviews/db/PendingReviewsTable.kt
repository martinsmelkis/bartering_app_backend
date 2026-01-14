package app.bartering.features.reviews.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Stores reviews that have been submitted but not yet revealed.
 * Reviews are encrypted until both parties submit or deadline expires.
 * This prevents reciprocal review manipulation.
 */
object PendingReviewsTable : Table("pending_reviews") {
    val transactionId = varchar("transaction_id", 255)
    val reviewerId = varchar("reviewer_id", 255)
    val encryptedReview = text("encrypted_review") // Encrypted JSON of the review
    val submittedAt = timestamp("submitted_at").default(Instant.now())
    val revealDeadline = timestamp("reveal_deadline") // 14 days from first submission
    val revealed = bool("revealed").default(false)
    val revealedAt = timestamp("revealed_at").nullable()

    override val primaryKey = PrimaryKey(transactionId, reviewerId)

    init {
        index("idx_reveal_deadline", false, revealDeadline, revealed)
    }
}
