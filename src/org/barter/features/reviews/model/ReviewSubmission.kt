package org.barter.features.reviews.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Represents a user's submission of a review for a barter transaction.
 */
@Serializable
data class ReviewSubmission(
    /**
     * The barter transaction being reviewed.
     */
    val barterTransactionId: String,

    /**
     * User ID of the reviewer.
     */
    val reviewerId: String,

    /**
     * User ID of the person being reviewed.
     */
    val targetUserId: String,

    /**
     * Star rating (1-5).
     */
    val rating: Int,

    /**
     * Optional text review/comment.
     */
    val reviewText: String? = null,

    /**
     * Status of the transaction from reviewer's perspective.
     */
    val transactionStatus: TransactionStatus,

    /**
     * When the review was submitted (auto-populated).
     */
    val submittedAt: Long = Instant.now().toEpochMilli()
) {
    init {
        require(rating in 1..5) { "Rating must be between 1 and 5" }
    }
}
