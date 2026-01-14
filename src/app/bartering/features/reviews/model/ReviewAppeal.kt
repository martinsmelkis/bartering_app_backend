package app.bartering.features.reviews.model

import kotlinx.serialization.Serializable

/**
 * Represents an appeal/dispute of a review.
 */
@Serializable
data class ReviewAppeal(
    /**
     * Unique appeal ID.
     */
    val id: String,

    /**
     * ID of the review being appealed.
     */
    val reviewId: String,

    /**
     * User ID of the person appealing.
     */
    val appealedBy: String,

    /**
     * Reason for the appeal.
     */
    val reason: String,

    /**
     * Evidence items supporting the appeal.
     */
    val evidenceItems: List<EvidenceItem> = emptyList(),

    /**
     * Current status of the appeal.
     */
    val status: AppealStatus,

    /**
     * When the appeal was submitted.
     */
    val appealedAt: Long,

    /**
     * When the appeal was resolved (if applicable).
     */
    val resolvedAt: Long? = null,

    /**
     * Moderator notes (if reviewed).
     */
    val moderatorNotes: String? = null
)

/**
 * Evidence item attached to a review appeal.
 */
@Serializable
data class EvidenceItem(
    /**
     * Type of evidence (e.g., "screenshot", "chat_log", "photo").
     */
    val type: String,

    /**
     * URL or reference to the evidence.
     */
    val reference: String,

    /**
     * Optional description of the evidence.
     */
    val description: String? = null
)
