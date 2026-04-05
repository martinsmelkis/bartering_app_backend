package app.bartering.dashboard_user_moderation.models.moderation

import kotlinx.serialization.Serializable

@Serializable
data class UserReportStats(
    val userId: String,
    val totalReportsReceived: Int = 0,
    val pendingReports: Int = 0,
    val actionsTaken: Int = 0,
    val lastReportedAt: String? = null
)
