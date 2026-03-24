package app.bartering.features.analytics.service

import app.bartering.features.analytics.dao.UserDailyActivityStatsDao
import app.bartering.features.analytics.model.UserDailyActivityStats
import app.bartering.features.profile.dao.UserProfileDao
import app.bartering.utils.SecurityUtils
import java.time.LocalDate

class UserDailyActivityStatsService(
    private val statsDao: UserDailyActivityStatsDao,
    private val userProfileDao: UserProfileDao
) {

    suspend fun recordActivity(userId: String, activityType: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        if (!userProfileDao.hasAnalyticsConsent(userId)) return false

        val base = statsDao.incrementApiRequest(userId)

        val specific = when (activityType) {
            "searching" -> statsDao.incrementSearch(userId)
            "browsing" -> statsDao.incrementNearbySearch(userId)
            "editing_profile" -> statsDao.incrementProfileUpdate(userId)
            else -> true
        }

        return base && specific
    }

    suspend fun recordProfileUpdate(userId: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        if (!userProfileDao.hasAnalyticsConsent(userId)) return false
        return statsDao.incrementProfileUpdate(userId)
    }

    suspend fun recordChatMessageSent(userId: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        if (!userProfileDao.hasAnalyticsConsent(userId)) return false
        return statsDao.incrementChatMessagesSent(userId)
    }

    suspend fun recordChatMessageReceived(userId: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        if (!userProfileDao.hasAnalyticsConsent(userId)) return false
        return statsDao.incrementChatMessagesReceived(userId)
    }

    suspend fun recordReviewSubmitted(userId: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        if (!userProfileDao.hasAnalyticsConsent(userId)) return false
        return statsDao.incrementReviewsSubmitted(userId)
    }

    suspend fun recordTransactionCreated(userId: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        if (!userProfileDao.hasAnalyticsConsent(userId)) return false
        return statsDao.incrementTransactionsCreated(userId)
    }

    suspend fun recordSuccessfulAction(userId: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        if (!userProfileDao.hasAnalyticsConsent(userId)) return false
        return statsDao.incrementSuccessfulActions(userId)
    }

    suspend fun recordFailedAction(userId: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        if (!userProfileDao.hasAnalyticsConsent(userId)) return false
        return statsDao.incrementFailedActions(userId)
    }

    suspend fun getUserStats(userId: String, days: Long = 30): List<UserDailyActivityStats> {
        if (!SecurityUtils.isValidUUID(userId)) return emptyList()
        if (!userProfileDao.hasAnalyticsConsent(userId)) return emptyList()

        val toDate = LocalDate.now()
        val fromDate = toDate.minusDays(days)
        return statsDao.getUserStats(userId, fromDate, toDate)
    }
}
