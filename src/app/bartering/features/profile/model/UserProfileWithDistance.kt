package app.bartering.features.profile.model

import app.bartering.features.reviews.model.ReputationBadge
import kotlinx.serialization.Serializable

@Serializable
data class UserProfileWithDistance(
    val profile: UserProfile,
    val distanceKm: Double,
    val matchRelevancyScore: Double?,
    val averageRating: Double? = null,
    val totalReviews: Int? = null,
    val badges: List<ReputationBadge>? = null
)