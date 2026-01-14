package app.bartering.features.reviews.model

import kotlinx.serialization.Serializable

/**
 * Calculated weight/impact of a review on overall reputation.
 * Not all reviews are equal - this tracks the weighting factors.
 */
@Serializable
data class ReviewWeight(
    /**
     * Final calculated weight for this review.
     */
    val baseWeight: Double,

    /**
     * List of modifiers applied to calculate the weight.
     */
    val modifiers: List<String>
)
