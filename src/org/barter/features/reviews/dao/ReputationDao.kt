package org.barter.features.reviews.dao

import org.barter.features.reviews.model.ReputationBadge
import org.barter.features.reviews.model.TrustLevel
import java.time.Instant

/**
 * Data Access Object interface for reputation operations
 */
interface ReputationDao {

    /**
     * Gets reputation score for a user
     */
    suspend fun getReputation(userId: String): ReputationDto?

    /**
     * Updates or creates reputation score for a user
     */
    suspend fun updateReputation(reputation: ReputationDto): Boolean

    /**
     * Gets badges for a user
     */
    suspend fun getUserBadges(userId: String): List<ReputationBadge>

    /**
     * Adds a badge to a user
     */
    suspend fun addBadge(userId: String, badge: ReputationBadge): Boolean

    /**
     * Removes a badge from a user
     */
    suspend fun removeBadge(userId: String, badge: ReputationBadge): Boolean

    /**
     * Checks if user has a specific badge
     */
    suspend fun hasBadge(userId: String, badge: ReputationBadge): Boolean
}

/**
 * DTO for reputation data
 */
data class ReputationDto(
    val userId: String,
    val averageRating: Double,
    val totalReviews: Int,
    val verifiedReviews: Int,
    val tradeDiversityScore: Double,
    val trustLevel: TrustLevel,
    val lastUpdated: Instant
)
