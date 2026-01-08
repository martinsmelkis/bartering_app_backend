package org.barter.features.reviews.dao

import org.barter.features.reviews.model.TransactionStatus
import java.time.Instant

/**
 * Data Access Object interface for review operations
 */
interface ReviewDao {

    /**
     * Creates a new review
     */
    suspend fun createReview(review: ReviewDto): Boolean

    /**
     * Gets all visible reviews for a user
     */
    suspend fun getUserReviews(userId: String): List<ReviewDto>

    /**
     * Gets reviews for a specific transaction
     */
    suspend fun getTransactionReviews(transactionId: String): List<ReviewDto>

    /**
     * Checks if a user has already reviewed a transaction
     */
    suspend fun hasAlreadyReviewed(reviewerId: String, targetUserId: String, transactionId: String): Boolean

    /**
     * Gets count of reviews given by a user in the last N days
     */
    suspend fun getReviewsInLastDays(userId: String, days: Int): Int

    /**
     * Checks if both parties in a transaction have submitted reviews
     */
    suspend fun haveBothPartiesSubmitted(transactionId: String, user1Id: String, user2Id: String): Boolean

    /**
     * Makes reviews visible (after blind review period)
     */
    suspend fun makeReviewsVisible(transactionId: String): Boolean

    /**
     * Updates moderation status of a review
     */
    suspend fun updateModerationStatus(reviewId: String, status: String): Boolean

    /**
     * Sets a review as verified
     */
    suspend fun markAsVerified(reviewId: String): Boolean

    /**
     * Gets weighted reviews for reputation calculation
     */
    suspend fun getWeightedReviews(userId: String): List<WeightedReviewDto>

    /**
     * Gets count of verified reviews for a user
     */
    suspend fun getVerifiedReviewCount(userId: String): Int

    /**
     * Deletes a review (for moderation)
     */
    suspend fun deleteReview(reviewId: String): Boolean

    /**
     * Updates review weight
     */
    suspend fun updateReviewWeight(reviewId: String, weight: Double): Boolean
}

/**
 * DTO for reviews
 */
data class ReviewDto(
    val id: String,
    val transactionId: String,
    val reviewerId: String,
    val targetUserId: String,
    val rating: Int,
    val reviewText: String?,
    val transactionStatus: TransactionStatus,
    val reviewWeight: Double,
    val isVisible: Boolean,
    val submittedAt: Instant,
    val revealedAt: Instant?,
    val isVerified: Boolean,
    val moderationStatus: String?
)

/**
 * Simplified DTO for weighted reputation calculation
 */
data class WeightedReviewDto(
    val rating: Double,
    val weight: Double
)
