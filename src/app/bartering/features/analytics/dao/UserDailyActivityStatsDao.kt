package app.bartering.features.analytics.dao

import app.bartering.features.analytics.model.UserDailyActivityStats
import java.time.LocalDate

interface UserDailyActivityStatsDao {
    suspend fun incrementApiRequest(anonymizedUserId: String, date: LocalDate = LocalDate.now(), amount: Int = 1): Boolean
    suspend fun incrementActiveMinutes(anonymizedUserId: String, date: LocalDate = LocalDate.now(), amount: Int = 1): Boolean
    suspend fun incrementSessionCount(anonymizedUserId: String, date: LocalDate = LocalDate.now(), amount: Int = 1): Boolean
    suspend fun incrementSearch(anonymizedUserId: String, date: LocalDate = LocalDate.now(), amount: Int = 1): Boolean
    suspend fun incrementNearbySearch(anonymizedUserId: String, date: LocalDate = LocalDate.now(), amount: Int = 1): Boolean
    suspend fun incrementProfileUpdate(anonymizedUserId: String, date: LocalDate = LocalDate.now(), amount: Int = 1): Boolean
    suspend fun incrementChatMessagesSent(anonymizedUserId: String, date: LocalDate = LocalDate.now(), amount: Int = 1): Boolean
    suspend fun incrementChatMessagesReceived(anonymizedUserId: String, date: LocalDate = LocalDate.now(), amount: Int = 1): Boolean
    suspend fun incrementTransactionsCreated(anonymizedUserId: String, date: LocalDate = LocalDate.now(), amount: Int = 1): Boolean
    suspend fun incrementReviewsSubmitted(anonymizedUserId: String, date: LocalDate = LocalDate.now(), amount: Int = 1): Boolean
    suspend fun incrementSuccessfulActions(anonymizedUserId: String, date: LocalDate = LocalDate.now(), amount: Int = 1): Boolean
    suspend fun incrementCountersBatch(
        anonymizedUserId: String,
        date: LocalDate = LocalDate.now(),
        counters: Map<String, Int>
    ): Boolean
    suspend fun incrementSearchKeyword(anonymizedUserId: String, keyword: String, date: LocalDate = LocalDate.now()): Boolean
    suspend fun recordSearchKeywordWithResponseTime(
        anonymizedUserId: String,
        keyword: String,
        responseTimeMs: Long,
        date: LocalDate = LocalDate.now()
    ): Boolean
    suspend fun recordResponseTime(anonymizedUserId: String, responseTimeMs: Long, date: LocalDate = LocalDate.now()): Boolean

    suspend fun getUserStats(anonymizedUserId: String, fromDate: LocalDate, toDate: LocalDate): List<UserDailyActivityStats>
}
