package org.barter.features.reviews.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Reviews table storing all user reviews.
 * Reviews are initially hidden until both parties submit (blind review period).
 */
object ReviewsTable : Table("reviews") {
    val id = varchar("id", 36)
    val transactionId = reference("transaction_id", BarterTransactionsTable.id).index()
    val reviewerId = varchar("reviewer_id", 255).index()
    val targetUserId = varchar("target_user_id", 255).index()
    val rating = integer("rating") // 1-5
    val reviewText = text("review_text").nullable()
    val transactionStatus = varchar("transaction_status", 50) // done, scam, no_deal, etc.
    val reviewWeight = decimal("review_weight", 3, 2).default(1.0.toBigDecimal())
    val isVisible = bool("is_visible").default(false) // Hidden until both submit
    val submittedAt = timestamp("submitted_at").default(Instant.now())
    val revealedAt = timestamp("revealed_at").nullable()
    val isVerified = bool("is_verified").default(false)
    val moderationStatus = varchar("moderation_status", 50).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_transaction_reviewer", false, transactionId, reviewerId)
        index("idx_target_visible", false, targetUserId, isVisible)
    }
}
