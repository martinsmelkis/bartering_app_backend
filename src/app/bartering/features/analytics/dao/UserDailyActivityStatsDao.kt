package app.bartering.features.analytics.dao

import app.bartering.features.analytics.model.UserDailyActivityStats
import java.time.LocalDate

interface UserDailyActivityStatsDao {
    suspend fun incrementApiRequest(userId: String, date: LocalDate = LocalDate.now(), amount: Int = 1): Boolean
    suspend fun incrementSearch(userId: String, date: LocalDate = LocalDate.now(), amount: Int = 1): Boolean
    suspend fun incrementNearbySearch(userId: String, date: LocalDate = LocalDate.now(), amount: Int = 1): Boolean
    suspend fun incrementProfileUpdate(userId: String, date: LocalDate = LocalDate.now(), amount: Int = 1): Boolean
    suspend fun incrementChatMessagesSent(userId: String, date: LocalDate = LocalDate.now(), amount: Int = 1): Boolean
    suspend fun incrementChatMessagesReceived(userId: String, date: LocalDate = LocalDate.now(), amount: Int = 1): Boolean
    suspend fun incrementTransactionsCreated(userId: String, date: LocalDate = LocalDate.now(), amount: Int = 1): Boolean
    suspend fun incrementReviewsSubmitted(userId: String, date: LocalDate = LocalDate.now(), amount: Int = 1): Boolean
    suspend fun incrementSuccessfulActions(userId: String, date: LocalDate = LocalDate.now(), amount: Int = 1): Boolean
    suspend fun incrementFailedActions(userId: String, date: LocalDate = LocalDate.now(), amount: Int = 1): Boolean

    suspend fun getUserStats(userId: String, fromDate: LocalDate, toDate: LocalDate): List<UserDailyActivityStats>
}
