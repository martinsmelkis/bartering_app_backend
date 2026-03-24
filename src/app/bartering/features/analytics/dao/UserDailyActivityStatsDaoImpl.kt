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

    override suspend fun incrementApiRequest(anonymizedUserId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(anonymizedUserId, date, "api_request_count", amount)

    override suspend fun incrementSearch(anonymizedUserId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(anonymizedUserId, date, "search_count", amount)

    override suspend fun incrementNearbySearch(anonymizedUserId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(anonymizedUserId, date, "nearby_search_count", amount)

    override suspend fun incrementProfileUpdate(anonymizedUserId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(anonymizedUserId, date, "profile_update_count", amount)

    override suspend fun incrementChatMessagesSent(anonymizedUserId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(anonymizedUserId, date, "chat_messages_sent_count", amount)

    override suspend fun incrementChatMessagesReceived(anonymizedUserId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(anonymizedUserId, date, "chat_messages_received_count", amount)

    override suspend fun incrementTransactionsCreated(anonymizedUserId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(anonymizedUserId, date, "transactions_created_count", amount)

    override suspend fun incrementReviewsSubmitted(anonymizedUserId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(anonymizedUserId, date, "reviews_submitted_count", amount)

    override suspend fun incrementSuccessfulActions(anonymizedUserId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(anonymizedUserId, date, "successful_actions_count", amount)

    override suspend fun incrementFailedActions(anonymizedUserId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(anonymizedUserId, date, "failed_actions_count", amount)

    override suspend fun incrementSearchKeyword(anonymizedUserId: String, keyword: String, date: LocalDate): Boolean = dbQuery {
        val normalizedKeyword = keyword.trim().lowercase().take(100)
        if (normalizedKeyword.isBlank()) return@dbQuery false

        try {
            val sql = """
                INSERT INTO user_daily_activity_stats (
                    anonymized_user_id,
                    activity_date,
                    search_count,
                    searched_keywords,
                    analytics_consent,
                    updated_at
                )
                VALUES (?, ?, 1, jsonb_build_object(?, 1), TRUE, NOW())
                ON CONFLICT (anonymized_user_id)
                DO UPDATE SET
                    activity_date = EXCLUDED.activity_date,
                    search_count = user_daily_activity_stats.search_count + 1,
                    searched_keywords = COALESCE(user_daily_activity_stats.searched_keywords, '{}'::jsonb) ||
                        jsonb_build_object(
                            ?,
                            COALESCE((user_daily_activity_stats.searched_keywords->>?)::int, 0) + 1
                        ),
                    analytics_consent = TRUE,
                    updated_at = NOW();
            """.trimIndent()

            (TransactionManager.current().connection.connection as java.sql.Connection)
                .prepareStatement(sql)
                .use { statement ->
                    statement.setString(1, anonymizedUserId)
                    statement.setDate(2, Date.valueOf(date))
                    statement.setString(3, normalizedKeyword)
                    statement.setString(4, normalizedKeyword)
                    statement.setString(5, normalizedKeyword)
                    statement.executeUpdate()
                }
            true
        } catch (e: Exception) {
            log.warn("Failed recording search keyword for anonymized user: {}", e.message)
            false
        }
    }

    override suspend fun recordSearchKeywordWithResponseTime(
        anonymizedUserId: String,
        keyword: String,
        responseTimeMs: Long,
        date: LocalDate
    ): Boolean = dbQuery {
        val normalizedKeyword = keyword.trim().lowercase().take(100)
        if (normalizedKeyword.isBlank()) return@dbQuery false
        if (responseTimeMs < 0) return@dbQuery false

        val boundedMs = responseTimeMs.coerceAtMost(60_000L).toInt()

        try {
            val sql = """
                INSERT INTO user_daily_activity_stats (
                    anonymized_user_id,
                    activity_date,
                    search_count,
                    searched_keywords,
                    keyword_response_times,
                    response_time_count,
                    total_response_time_ms,
                    min_response_time_ms,
                    max_response_time_ms,
                    analytics_consent,
                    updated_at
                )
                VALUES (
                    ?,
                    ?,
                    1,
                    jsonb_build_object(?, 1),
                    jsonb_build_object(?, jsonb_build_object('count', 1, 'totalMs', ?, 'minMs', ?, 'maxMs', ?)),
                    1,
                    ?,
                    ?,
                    ?,
                    TRUE,
                    NOW()
                )
                ON CONFLICT (anonymized_user_id)
                DO UPDATE SET
                    activity_date = EXCLUDED.activity_date,
                    search_count = user_daily_activity_stats.search_count + 1,
                    searched_keywords = COALESCE(user_daily_activity_stats.searched_keywords, '{}'::jsonb) ||
                        jsonb_build_object(
                            ?,
                            COALESCE((user_daily_activity_stats.searched_keywords->>?)::int, 0) + 1
                        ),
                    keyword_response_times = jsonb_set(
                        COALESCE(user_daily_activity_stats.keyword_response_times, '{}'::jsonb),
                        ARRAY[?],
                        jsonb_build_object(
                            'count', COALESCE((user_daily_activity_stats.keyword_response_times->?->>'count')::int, 0) + 1,
                            'totalMs', COALESCE((user_daily_activity_stats.keyword_response_times->?->>'totalMs')::bigint, 0) + ?,
                            'minMs', LEAST(COALESCE((user_daily_activity_stats.keyword_response_times->?->>'minMs')::int, ?), ?),
                            'maxMs', GREATEST(COALESCE((user_daily_activity_stats.keyword_response_times->?->>'maxMs')::int, ?), ?)
                        ),
                        true
                    ),
                    response_time_count = user_daily_activity_stats.response_time_count + 1,
                    total_response_time_ms = user_daily_activity_stats.total_response_time_ms + ?,
                    min_response_time_ms = CASE
                        WHEN user_daily_activity_stats.min_response_time_ms IS NULL THEN ?
                        ELSE LEAST(user_daily_activity_stats.min_response_time_ms, ?)
                    END,
                    max_response_time_ms = CASE
                        WHEN user_daily_activity_stats.max_response_time_ms IS NULL THEN ?
                        ELSE GREATEST(user_daily_activity_stats.max_response_time_ms, ?)
                    END,
                    analytics_consent = TRUE,
                    updated_at = NOW();
            """.trimIndent()

            (TransactionManager.current().connection.connection as java.sql.Connection)
                .prepareStatement(sql)
                .use { statement ->
                    var i = 1
                    statement.setString(i++, anonymizedUserId)
                    statement.setDate(i++, Date.valueOf(date))
                    statement.setString(i++, normalizedKeyword)
                    statement.setString(i++, normalizedKeyword)
                    statement.setInt(i++, boundedMs)
                    statement.setInt(i++, boundedMs)
                    statement.setInt(i++, boundedMs)
                    statement.setLong(i++, boundedMs.toLong())
                    statement.setInt(i++, boundedMs)
                    statement.setInt(i++, boundedMs)

                    statement.setString(i++, normalizedKeyword)
                    statement.setString(i++, normalizedKeyword)

                    statement.setString(i++, normalizedKeyword)
                    statement.setString(i++, normalizedKeyword)
                    statement.setString(i++, normalizedKeyword)
                    statement.setLong(i++, boundedMs.toLong())
                    statement.setString(i++, normalizedKeyword)
                    statement.setInt(i++, boundedMs)
                    statement.setInt(i++, boundedMs)
                    statement.setString(i++, normalizedKeyword)
                    statement.setInt(i++, boundedMs)
                    statement.setInt(i++, boundedMs)

                    statement.setLong(i++, boundedMs.toLong())
                    statement.setInt(i++, boundedMs)
                    statement.setInt(i++, boundedMs)
                    statement.setInt(i++, boundedMs)
                    statement.setInt(i, boundedMs)

                    statement.executeUpdate()
                }
            true
        } catch (e: Exception) {
            log.warn("Failed recording keyword+response time for anonymized user: {}", e.message)
            false
        }
    }

    override suspend fun recordResponseTime(anonymizedUserId: String, responseTimeMs: Long, date: LocalDate): Boolean = dbQuery {
        if (responseTimeMs < 0) return@dbQuery false

        val boundedMs = responseTimeMs.coerceAtMost(60_000L).toInt()

        try {
            val sql = """
                INSERT INTO user_daily_activity_stats (
                    anonymized_user_id,
                    activity_date,
                    response_time_count,
                    total_response_time_ms,
                    min_response_time_ms,
                    max_response_time_ms,
                    analytics_consent,
                    updated_at
                )
                VALUES (?, ?, 1, ?, ?, ?, TRUE, NOW())
                ON CONFLICT (anonymized_user_id)
                DO UPDATE SET
                    activity_date = EXCLUDED.activity_date,
                    response_time_count = user_daily_activity_stats.response_time_count + 1,
                    total_response_time_ms = user_daily_activity_stats.total_response_time_ms + EXCLUDED.total_response_time_ms,
                    min_response_time_ms = CASE
                        WHEN user_daily_activity_stats.min_response_time_ms IS NULL THEN EXCLUDED.min_response_time_ms
                        ELSE LEAST(user_daily_activity_stats.min_response_time_ms, EXCLUDED.min_response_time_ms)
                    END,
                    max_response_time_ms = CASE
                        WHEN user_daily_activity_stats.max_response_time_ms IS NULL THEN EXCLUDED.max_response_time_ms
                        ELSE GREATEST(user_daily_activity_stats.max_response_time_ms, EXCLUDED.max_response_time_ms)
                    END,
                    analytics_consent = TRUE,
                    updated_at = NOW();
            """.trimIndent()

            (TransactionManager.current().connection.connection as java.sql.Connection)
                .prepareStatement(sql)
                .use { statement ->
                    statement.setString(1, anonymizedUserId)
                    statement.setDate(2, Date.valueOf(date))
                    statement.setLong(3, boundedMs.toLong())
                    statement.setInt(4, boundedMs)
                    statement.setInt(5, boundedMs)
                    statement.executeUpdate()
                }
            true
        } catch (e: Exception) {
            log.warn("Failed recording response time for anonymized user: {}", e.message)
            false
        }
    }

    override suspend fun getUserStats(
        anonymizedUserId: String,
        fromDate: LocalDate,
        toDate: LocalDate
    ): List<UserDailyActivityStats> = dbQuery {
        UserDailyActivityStatsTable
            .selectAll()
            .where {
                (UserDailyActivityStatsTable.anonymizedUserId eq anonymizedUserId) and
                    (UserDailyActivityStatsTable.activityDate.between(fromDate, toDate))
            }
            .orderBy(UserDailyActivityStatsTable.activityDate to SortOrder.ASC)
            .map { row ->
                UserDailyActivityStats(
                    userId = row[UserDailyActivityStatsTable.anonymizedUserId],
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
        anonymizedUserId: String,
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
                    anonymized_user_id,
                    activity_date,
                    $counterColumn,
                    analytics_consent,
                    updated_at
                )
                VALUES (?, ?, ?, TRUE, NOW())
                ON CONFLICT (anonymized_user_id)
                DO UPDATE SET
                    activity_date = EXCLUDED.activity_date,
                    $counterColumn = user_daily_activity_stats.$counterColumn + EXCLUDED.$counterColumn,
                    analytics_consent = TRUE,
                    updated_at = NOW();
            """.trimIndent()

            (TransactionManager.current().connection.connection as java.sql.Connection)
                .prepareStatement(sql)
                .use { statement ->
                    statement.setString(1, anonymizedUserId)
                    statement.setDate(2, Date.valueOf(date))
                    statement.setInt(3, amount)
                    statement.executeUpdate()
                }
            true
        } catch (e: Exception) {
            log.warn(
                "Failed incrementing daily stats counter={} for anonymizedUserId={} date={}: {}",
                counterColumn,
                anonymizedUserId,
                date,
                e.message
            )
            false
        }
    }
}
