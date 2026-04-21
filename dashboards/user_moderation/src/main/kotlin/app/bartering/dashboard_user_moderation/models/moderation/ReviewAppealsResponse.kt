package app.bartering.dashboard_user_moderation.models.moderation

import kotlinx.serialization.Serializable

@Serializable
data class ReviewAppealsResponse(
    val appeals: List<ReviewAppealModerationItem> = emptyList(),
    val totalCount: Int = 0
)

@Serializable
data class ReviewAppealModerationItem(
    val id: String,
    val reviewId: String,
    val appealedBy: String,
    val reason: String,
    val status: String,
    val appealedAt: Long,
    val resolvedAt: Long? = null,
    val moderatorNotes: String? = null
)
