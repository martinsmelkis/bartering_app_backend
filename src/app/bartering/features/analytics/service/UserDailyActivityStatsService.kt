package app.bartering.features.analytics.service

import app.bartering.features.analytics.dao.UserDailyActivityStatsDao
import app.bartering.features.analytics.model.UserDailyActivityStats
import app.bartering.features.profile.dao.UserProfileDao
import app.bartering.utils.HashUtils
import app.bartering.utils.SecurityUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

class UserDailyActivityStatsService(
    private val statsDao: UserDailyActivityStatsDao,
    private val userProfileDao: UserProfileDao
) {

    private val analyticsSalt: String = System.getenv("ANALYTICS_HASH_SALT") ?: "barter-analytics-default-salt"
    private val log = LoggerFactory.getLogger(this::class.java)

    private companion object {
        private const val BATCH_FLUSH_INTERVAL_MS = 3_000L
        private const val BATCH_FLUSH_THRESHOLD = 20
        private const val API_REQUEST = "api_request_count"
        private const val ACTIVE_MINUTES = "active_minutes"
        private const val SESSION_COUNT = "session_count"
    }

    private data class StatsKey(val anonymizedUserId: String, val date: LocalDate)

    private val bufferedCounters = ConcurrentHashMap<StatsKey, ConcurrentHashMap<String, Int>>()
    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        flushScope.launch {
            while (isActive) {
                delay(BATCH_FLUSH_INTERVAL_MS)
                flushBufferedCounters()
            }
        }
    }

    private fun anonymizeUserId(userId: String): String {
        return HashUtils.sha256("$analyticsSalt:$userId")
    }

    private suspend fun canTrackAnalytics(userId: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        return userProfileDao.hasAnalyticsConsent(userId)
    }

    @Suppress("unused")
    suspend fun recordActivity(userId: String, activityType: String): Boolean {
        if (!canTrackAnalytics(userId)) return false
        return recordActivityForConsentedUser(userId, activityType)
    }

    suspend fun recordActivityForConsentedUser(userId: String, @Suppress("UNUSED_PARAMETER") activityType: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false

        val anonId = anonymizeUserId(userId)
        return bufferCounterIncrement(anonId, API_REQUEST, 1)
    }

    @Suppress("unused")
    suspend fun recordSessionStart(userId: String): Boolean {
        if (!canTrackAnalytics(userId)) return false
        return recordSessionStartForConsentedUser(userId)
    }

    suspend fun recordSessionStartForConsentedUser(userId: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        return bufferCounterIncrement(anonymizeUserId(userId), SESSION_COUNT, 1)
    }

    @Suppress("unused")
    suspend fun recordActiveMinute(userId: String): Boolean {
        if (!canTrackAnalytics(userId)) return false
        return recordActiveMinuteForConsentedUser(userId)
    }

    suspend fun recordActiveMinuteForConsentedUser(userId: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        return bufferCounterIncrement(anonymizeUserId(userId), ACTIVE_MINUTES, 1)
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

    @Suppress("unused")
    suspend fun recordResponseTime(userId: String, responseTimeMs: Long): Boolean {
        if (!canTrackAnalytics(userId)) return false
        return recordResponseTimeForConsentedUser(userId, responseTimeMs)
    }

    suspend fun recordResponseTimeForConsentedUser(userId: String, responseTimeMs: Long): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false

        val anonId = anonymizeUserId(userId)
        return statsDao.recordResponseTime(anonId, responseTimeMs)
    }

    suspend fun recordProfileUpdate(userId: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        if (!userProfileDao.hasAnalyticsConsent(userId)) return false
        return statsDao.incrementProfileUpdate(anonymizeUserId(userId))
    }

    suspend fun recordNearbySearch(userId: String): Boolean {
        if (!SecurityUtils.isValidUUID(userId)) return false
        if (!userProfileDao.hasAnalyticsConsent(userId)) return false
        return statsDao.incrementNearbySearch(anonymizeUserId(userId))
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

    suspend fun getUserStats(userId: String, days: Long = 30): List<UserDailyActivityStats> {
        if (!SecurityUtils.isValidUUID(userId)) return emptyList()
        if (!userProfileDao.hasAnalyticsConsent(userId)) return emptyList()

        val toDate = LocalDate.now()
        val fromDate = toDate.minusDays(days)
        return statsDao.getUserStats(anonymizeUserId(userId), fromDate, toDate)
    }

    private suspend fun bufferCounterIncrement(anonymizedUserId: String, counter: String, amount: Int): Boolean {
        if (amount <= 0) return true

        val key = StatsKey(anonymizedUserId, LocalDate.now())
        val perRowMap = bufferedCounters.computeIfAbsent(key) { ConcurrentHashMap() }
        val updatedValue = perRowMap.merge(counter, amount) { current, delta -> current + delta } ?: amount

        if (updatedValue >= BATCH_FLUSH_THRESHOLD) {
            return flushKey(key)
        }

        return true
    }

    private suspend fun flushBufferedCounters() {
        if (bufferedCounters.isEmpty()) return

        val keys = bufferedCounters.keys.toList()
        keys.forEach { key ->
            try {
                flushKey(key)
            } catch (e: Exception) {
                log.warn("Failed flushing analytics batch for key={}: {}", key, e.message)
            }
        }
    }

    private suspend fun flushKey(key: StatsKey): Boolean {
        val currentMap = bufferedCounters[key] ?: return true
        if (currentMap.isEmpty()) return true

        val snapshot = currentMap.entries.associate { it.key to it.value }
        if (snapshot.isEmpty()) return true

        if (!statsDao.incrementCountersBatch(key.anonymizedUserId, key.date, snapshot)) {
            return false
        }

        snapshot.forEach { (counter, flushedAmount) ->
            currentMap.computeIfPresent(counter) { _, current ->
                val remaining = current - flushedAmount
                if (remaining > 0) remaining else null
            }
        }

        if (currentMap.isEmpty()) {
            bufferedCounters.remove(key, currentMap)
        }

        return true
    }
}
