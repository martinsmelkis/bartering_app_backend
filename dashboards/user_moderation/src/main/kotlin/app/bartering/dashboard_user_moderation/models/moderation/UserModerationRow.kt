package app.bartering.dashboard_user_moderation.models.moderation

data class UserModerationRow(
    val userId: String,
    val totalReportsReceived: Int,
    val pendingReports: Int,
    val actionsTaken: Int,
    val lastReportedAt: String?,
    val totalReviews: Int,
    val pendingReviewModerationCount: Int,
    val disputedTransactionCount: Int,
    val scamFlagCount: Int
)
