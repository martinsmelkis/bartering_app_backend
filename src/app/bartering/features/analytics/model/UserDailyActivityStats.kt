package app.bartering.features.analytics.model

import kotlinx.serialization.Serializable

@Serializable
data class UserDailyActivityStats(
    val userId: String,
    val activityDate: String,
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
    val successfulActionsCount: Int,
    val failedActionsCount: Int,
    val analyticsConsent: Boolean,
    val consentVersion: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class UserDailyActivityStatsResponse(
    val success: Boolean,
    val stats: List<UserDailyActivityStats>
)
