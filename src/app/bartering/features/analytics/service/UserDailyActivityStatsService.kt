package app.bartering.features.analytics.service

import app.bartering.features.analytics.dao.UserDailyActivityStatsDao
import app.bartering.features.analytics.model.UserDailyActivityStats
import app.bartering.features.profile.dao.UserProfileDao
import app.bartering.utils.HashUtils
import app.bartering.utils.SecurityUtils
import java.time.LocalDate

class UserDailyActivityStatsService(
    private val statsDao: UserDailyActivityStatsDao,
    private val userProfileDao: UserProfileDao
) {

    private val analyticsSalt: String = System.getenv("ANALYTICS_HASH_SALT") ?: "barter-analytics-default-salt"

    private fun anonymizeUserId(userId: String): String {
        return HashUtils.sha256("$analyticsSalt:$userId")
    }

    suspend fun recordActivity(userId: String, activityType: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        if (!userProfileDao.hasAnalyticsConsent(userId)) return false

        val anonId = anonymizeUserId(userId)
        val base = statsDao.incrementApiRequest(anonId)

        val specific = when (activityType) {
            "searching" -> statsDao.incrementSearch(anonId)
            "browsing" -> statsDao.incrementNearbySearch(anonId)
            "editing_profile" -> statsDao.incrementProfileUpdate(anonId)
            else -> true
        }

        return base && specific
    }

    @Suppress("unused")
    suspend fun recordSearchedKeyword(userId: String, keyword: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        if (!userProfileDao.hasAnalyticsConsent(userId)) return false
        if (keyword.isBlank()) return false

        val anonId = anonymizeUserId(userId)
        return statsDao.incrementSearchKeyword(anonId, keyword)
    }

    suspend fun recordSearchedKeywordWithResponseTime(
        userId: String,
        keyword: String,
        responseTimeMs: Long
    ): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        if (!userProfileDao.hasAnalyticsConsent(userId)) return false
        if (keyword.isBlank()) return false

        val anonId = anonymizeUserId(userId)
        return statsDao.recordSearchKeywordWithResponseTime(anonId, keyword, responseTimeMs)
    }

    suspend fun recordResponseTime(userId: String, responseTimeMs: Long): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        if (!userProfileDao.hasAnalyticsConsent(userId)) return false

        val anonId = anonymizeUserId(userId)
        return statsDao.recordResponseTime(anonId, responseTimeMs)
    }

    suspend fun recordProfileUpdate(userId: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        if (!userProfileDao.hasAnalyticsConsent(userId)) return false
        return statsDao.incrementProfileUpdate(anonymizeUserId(userId))
    }

    suspend fun recordChatMessageSent(userId: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        if (!userProfileDao.hasAnalyticsConsent(userId)) return false
        return statsDao.incrementChatMessagesSent(anonymizeUserId(userId))
    }

    suspend fun recordChatMessageReceived(userId: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        if (!userProfileDao.hasAnalyticsConsent(userId)) return false
        return statsDao.incrementChatMessagesReceived(anonymizeUserId(userId))
    }

    suspend fun recordReviewSubmitted(userId: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        if (!userProfileDao.hasAnalyticsConsent(userId)) return false
        return statsDao.incrementReviewsSubmitted(anonymizeUserId(userId))
    }

    suspend fun recordTransactionCreated(userId: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        if (!userProfileDao.hasAnalyticsConsent(userId)) return false
        return statsDao.incrementTransactionsCreated(anonymizeUserId(userId))
    }

    suspend fun recordSuccessfulAction(userId: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        if (!userProfileDao.hasAnalyticsConsent(userId)) return false
        return statsDao.incrementSuccessfulActions(anonymizeUserId(userId))
    }

    suspend fun recordFailedAction(userId: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        if (!userProfileDao.hasAnalyticsConsent(userId)) return false
        return statsDao.incrementFailedActions(anonymizeUserId(userId))
    }

    suspend fun getUserStats(userId: String, days: Long = 30): List<UserDailyActivityStats> {
        if (!SecurityUtils.isValidUUID(userId)) return emptyList()
        if (!userProfileDao.hasAnalyticsConsent(userId)) return emptyList()

        val toDate = LocalDate.now()
        val fromDate = toDate.minusDays(days)
        return statsDao.getUserStats(anonymizeUserId(userId), fromDate, toDate)
    }
}
