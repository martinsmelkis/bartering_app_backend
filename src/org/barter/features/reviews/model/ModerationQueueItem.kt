package org.barter.features.reviews.model

import kotlinx.serialization.Serializable

/**
 * Item in the moderation queue requiring human review.
 */
@Serializable
data class ModerationQueueItem(
    /**
     * Unique queue item ID.
     */
    val id: String,

    /**
     * Review ID being moderated.
     */
    val reviewId: String,

    /**
     * Transaction ID related to the review.
     */
    val transactionId: String,

    /**
     * Reason this was flagged for moderation.
     */
    val flagReason: TransactionStatus,

    /**
     * Risk factors detected (if any).
     */
    val riskFactors: List<String> = emptyList(),

    /**
     * Priority level for moderation.
     */
    val priority: ModerationPriority,

    /**
     * When this was added to the queue.
     */
    val submittedAt: Long,

    /**
     * User ID of the reviewer.
     */
    val reviewerId: String,

    /**
     * User ID of the person being reviewed.
     */
    val targetUserId: String
)
