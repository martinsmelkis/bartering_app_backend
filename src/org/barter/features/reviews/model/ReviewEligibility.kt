package org.barter.features.reviews.model

import kotlinx.serialization.Serializable

/**
 * Represents whether a user is eligible to leave a review.
 */
@Serializable
data class ReviewEligibility(
    /**
     * Whether the user can submit a review.
     */
    val canReview: Boolean,

    /**
     * Reason why review is not allowed (if canReview = false).
     */
    val reason: String? = null,

    /**
     * Whether additional verification is required before review can be submitted.
     */
    val requiresVerification: Boolean = false
)
