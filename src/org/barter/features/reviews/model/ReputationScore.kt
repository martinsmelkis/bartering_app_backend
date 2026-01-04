package org.barter.features.reviews.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Comprehensive reputation score for a user.
 */
@Serializable
data class ReputationScore(
    /**
     * User ID this reputation belongs to.
     */
    val userId: String,

    /**
     * Average rating from 1.0 to 5.0.
     */
    val averageRating: Double,

    /**
     * Total number of reviews received.
     */
    val totalReviews: Int,

    /**
     * Number of reviews that have been verified.
     */
    val verifiedReviews: Int,

    /**
     * Trade diversity score from 0.0 to 1.0.
     * Higher = trades with many different people (less likely wash trading).
     */
    val tradeDiversityScore: Double,

    /**
     * Trust level based on trading history.
     */
    val trustLevel: TrustLevel,

    /**
     * Badges earned by the user.
     */
    val badges: List<ReputationBadge>,

    /**
     * When this reputation was last calculated.
     */
    val lastUpdated: Long = Instant.now().toEpochMilli()
)
