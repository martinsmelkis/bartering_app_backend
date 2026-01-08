package org.barter.features.reviews.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.math.BigDecimal

// ============================================================================
// TRANSACTION API MODELS
// ============================================================================

@Serializable
data class CreateTransactionRequest(
    val user1Id: String,
    val user2Id: String,
    @Contextual val estimatedValue: BigDecimal? = null
)

@Serializable
data class CreateTransactionResponse(
    val success: Boolean,
    val transactionId: String
)

@Serializable
data class UpdateTransactionStatusRequest(
    val status: String
)

@Serializable
data class TransactionResponse(
    val id: String,
    val user1Id: String,
    val user2Id: String,
    val initiatedAt: Long,
    val completedAt: Long?,
    val status: String,
    @Contextual val estimatedValue: BigDecimal?,
    val locationConfirmed: Boolean,
    val riskScore: Double?
)

// ============================================================================
// REVIEW API MODELS
// ============================================================================

@Serializable
data class SubmitReviewRequest(
    val transactionId: String,
    val reviewerId: String,
    val targetUserId: String,
    val rating: Int,
    val reviewText: String? = null,
    val transactionStatus: String
)

@Serializable
data class SubmitReviewResponse(
    val success: Boolean,
    val reviewId: String,
    val message: String,
    val riskAnalysis: RiskAnalysisReport? = null
)

@Serializable
data class ReviewEligibilityRequest(
    val userId: String,
    val otherUserId: String
)

@Serializable
data class ReviewEligibilityResponse(
    val eligible: Boolean,
    val transactionId: String?,
    val reason: String?,
    val otherUserAvatarUrl: String? = null,
    val transactionCompletedAt: Long? = null
)

@Serializable
data class ReviewResponse(
    val id: String,
    val transactionId: String,
    val reviewerId: String,
    val targetUserId: String,
    val rating: Int,
    val reviewText: String?,
    val transactionStatus: String,
    val reviewWeight: Double,
    val isVisible: Boolean,
    val submittedAt: Long,
    val revealedAt: Long?,
    val isVerified: Boolean,
    val moderationStatus: String?
)

@Serializable
data class UserReviewsResponse(
    val userId: String,
    val reviews: List<ReviewResponse>,
    val totalCount: Int,
    val averageRating: Double
)

// ============================================================================
// REPUTATION API MODELS
// ============================================================================

@Serializable
data class ReputationResponse(
    val userId: String,
    val averageRating: Double,
    val totalReviews: Int,
    val verifiedReviews: Int,
    val tradeDiversityScore: Double,
    val trustLevel: String,
    val badges: List<String>,
    val lastUpdated: Long
)

@Serializable
data class BadgesResponse(
    val userId: String,
    val badges: List<BadgeInfo>
)

@Serializable
data class BadgeInfo(
    val type: String,
    val name: String,
    val description: String,
    val earnedAt: Long
)

// ============================================================================
// ERROR RESPONSE MODEL
// ============================================================================

@Serializable
data class ErrorResponse(
    val error: String,
    val code: String? = null,
    val details: Map<String, String>? = null
)

// ============================================================================
// SUCCESS RESPONSE MODEL
// ============================================================================

@Serializable
data class SuccessResponse(
    val success: Boolean,
    val message: String? = null
)

// ============================================================================
// REVIEW STATISTICS
// ============================================================================

@Serializable
data class ReviewStatistics(
    val totalReviews: Int,
    val ratingDistribution: Map<Int, Int>, // star rating -> count
    val averageRating: Double,
    val verifiedPercentage: Double,
    val recentReviews: List<ReviewResponse>
)

// ============================================================================
// TRANSACTION STATISTICS
// ============================================================================

@Serializable
data class TransactionStatistics(
    val totalTransactions: Int,
    val completedTransactions: Int,
    val cancelledTransactions: Int,
    val successRate: Double,
    val averageTransactionValue: Double?
)
