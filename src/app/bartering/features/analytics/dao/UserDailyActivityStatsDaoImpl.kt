package app.bartering.features.analytics.dao

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.analytics.db.UserDailyActivityStatsTable
import app.bartering.features.analytics.model.UserDailyActivityStats
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import java.sql.Date
import java.time.LocalDate

class UserDailyActivityStatsDaoImpl : UserDailyActivityStatsDao {
    private val log = LoggerFactory.getLogger(this::class.java)

    override suspend fun incrementApiRequest(userId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(userId, date, "api_request_count", amount)

    override suspend fun incrementSearch(userId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(userId, date, "search_count", amount)

    override suspend fun incrementNearbySearch(userId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(userId, date, "nearby_search_count", amount)

    override suspend fun incrementProfileUpdate(userId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(userId, date, "profile_update_count", amount)

    override suspend fun incrementChatMessagesSent(userId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(userId, date, "chat_messages_sent_count", amount)

    override suspend fun incrementChatMessagesReceived(userId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(userId, date, "chat_messages_received_count", amount)

    override suspend fun incrementTransactionsCreated(userId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(userId, date, "transactions_created_count", amount)

    override suspend fun incrementReviewsSubmitted(userId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(userId, date, "reviews_submitted_count", amount)

    override suspend fun incrementSuccessfulActions(userId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(userId, date, "successful_actions_count", amount)

    override suspend fun incrementFailedActions(userId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(userId, date, "failed_actions_count", amount)

    override suspend fun getUserStats(
        userId: String,
        fromDate: LocalDate,
        toDate: LocalDate
    ): List<UserDailyActivityStats> = dbQuery {
        UserDailyActivityStatsTable
            .selectAll()
            .where {
                (UserDailyActivityStatsTable.userId eq userId) and
                    (UserDailyActivityStatsTable.activityDate.between(fromDate, toDate))
            }
            .orderBy(UserDailyActivityStatsTable.activityDate to SortOrder.ASC)
            .map { row ->
                UserDailyActivityStats(
                    userId = row[UserDailyActivityStatsTable.userId],
                    activityDate = row[UserDailyActivityStatsTable.activityDate].toString(),
                    activeMinutes = row[UserDailyActivityStatsTable.activeMinutes],
                    sessionCount = row[UserDailyActivityStatsTable.sessionCount],
                    apiRequestCount = row[UserDailyActivityStatsTable.apiRequestCount],
                    searchCount = row[UserDailyActivityStatsTable.searchCount],
                    nearbySearchCount = row[UserDailyActivityStatsTable.nearbySearchCount],
                    profileUpdateCount = row[UserDailyActivityStatsTable.profileUpdateCount],
                    chatMessagesSentCount = row[UserDailyActivityStatsTable.chatMessagesSentCount],
                    chatMessagesReceivedCount = row[UserDailyActivityStatsTable.chatMessagesReceivedCount],
                    transactionsCreatedCount = row[UserDailyActivityStatsTable.transactionsCreatedCount],
                    reviewsSubmittedCount = row[UserDailyActivityStatsTable.reviewsSubmittedCount],
                    successfulActionsCount = row[UserDailyActivityStatsTable.successfulActionsCount],
                    failedActionsCount = row[UserDailyActivityStatsTable.failedActionsCount],
                    analyticsConsent = row[UserDailyActivityStatsTable.analyticsConsent],
                    consentVersion = row[UserDailyActivityStatsTable.consentVersion],
                    updatedAt = row[UserDailyActivityStatsTable.updatedAt].toString()
                )
            }
    }

    private suspend fun incrementCounter(
        userId: String,
        date: LocalDate,
        counterColumn: String,
        amount: Int
    ): Boolean = dbQuery {
        if (amount <= 0) return@dbQuery true

        val allowedColumns = setOf(
            "api_request_count",
            "search_count",
            "nearby_search_count",
            "profile_update_count",
            "chat_messages_sent_count",
            "chat_messages_received_count",
            "transactions_created_count",
            "reviews_submitted_count",
            "successful_actions_count",
            "failed_actions_count"
        )

        if (counterColumn !in allowedColumns) {
            log.warn("Rejected unknown analytics counter column: {}", counterColumn)
            return@dbQuery false
        }

        try {
            val sql = """
                INSERT INTO user_daily_activity_stats (
                    user_id,
                    activity_date,
                    $counterColumn,
                    analytics_consent,
                    updated_at
                )
                VALUES (?, ?, ?, TRUE, NOW())
                ON CONFLICT (user_id, activity_date)
                DO UPDATE SET
                    $counterColumn = user_daily_activity_stats.$counterColumn + EXCLUDED.$counterColumn,
                    analytics_consent = TRUE,
                    updated_at = NOW();
            """.trimIndent()

            (TransactionManager.current().connection.connection as java.sql.Connection)
                .prepareStatement(sql)
                .use { statement ->
                    statement.setString(1, userId)
                    statement.setDate(2, Date.valueOf(date))
                    statement.setInt(3, amount)
                    statement.executeUpdate()
                }
            true
        } catch (e: Exception) {
            log.warn(
                "Failed incrementing daily stats counter={} for userId={} date={}: {}",
                counterColumn,
                userId,
                date,
                e.message
            )
            false
        }
    }
}
