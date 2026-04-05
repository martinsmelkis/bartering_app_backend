package app.bartering.dashboard_user_moderation.models.moderation

import kotlinx.serialization.Serializable

@Serializable
data class UserReviewsResponse(
    val userId: String,
    val reviews: List<ReviewResponse>,
    val totalCount: Int,
    val averageRating: Double
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
