package app.bartering.dashboard_user_moderation.models.moderation

import kotlinx.serialization.Serializable

@Serializable
data class DashboardDailyStats(
    val date: String,
    val activeUsers: Int,
    val newRegistrations: Int,
    val activeMinutes: Int,
    val sessionCount: Int,
    val apiRequestCount: Int,
    val searchCount: Int,
    val nearbySearchCount: Int,
    val profileUpdateCount: Int,
    val chatMessagesSentCount: Int,
    val chatMessagesReceivedCount: Int,
    val transactionsCreatedCount: Int,
    val reviewsSubmittedCount: Int,
    val successfulActionsCount: Int
)

@Serializable
data class DashboardStatsSummary(
    val totalUsers: Int,
    val totalActiveUsers: Int,
    val totalNewRegistrations: Int,
    val totalApiRequests: Int,
    val totalSessions: Int,
    val totalActiveMinutes: Int
)

@Serializable
data class DashboardStatsResponse(
    val success: Boolean,
    val days: Int,
    val summary: DashboardStatsSummary,
    val daily: List<DashboardDailyStats>
)

data class DashboardStatsSnapshot(
    val connected: Boolean,
    val backendStatus: String,
    val stats: DashboardStatsResponse?,
    val connectionError: String? = null
)
