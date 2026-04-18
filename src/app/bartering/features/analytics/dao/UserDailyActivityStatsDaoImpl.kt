package app.bartering.features.analytics.dao

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.analytics.db.UserDailyActivityStatsTable
import app.bartering.features.analytics.model.UserDailyActivityStats
import kotlinx.coroutines.delay
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import java.sql.Date
import java.sql.SQLException
import java.time.LocalDate

class UserDailyActivityStatsDaoImpl : UserDailyActivityStatsDao {
    private val log = LoggerFactory.getLogger(this::class.java)

    private companion object {
        private const val MAX_SERIALIZATION_RETRIES = 3
    }

    override suspend fun incrementApiRequest(anonymizedUserId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(anonymizedUserId, date, "api_request_count", amount)

    override suspend fun incrementActiveMinutes(anonymizedUserId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(anonymizedUserId, date, "active_minutes", amount)

    override suspend fun incrementSessionCount(anonymizedUserId: String, date: LocalDate, amount: Int): Boolean =
        incrementCounter(anonymizedUserId, date, "session_count", amount)

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

    override suspend fun incrementCountersBatch(
        anonymizedUserId: String,
        date: LocalDate,
        counters: Map<String, Int>
    ): Boolean {
        if (counters.isEmpty()) return true

        val allowedColumns = setOf(
            "api_request_count",
            "active_minutes",
            "session_count",
            "search_count",
            "nearby_search_count",
            "profile_update_count",
            "chat_messages_sent_count",
            "chat_messages_received_count",
            "transactions_created_count",
            "reviews_submitted_count",
            "successful_actions_count"
        )

        val normalized = counters
            .filterValues { it > 0 }
            .filterKeys { it in allowedColumns }

        if (normalized.isEmpty()) return true

        val columnsSql = normalized.keys.joinToString(", ")
        val valuesSql = normalized.keys.joinToString(", ") { "?" }
        val updatesSql = normalized.keys.joinToString(",\n                ") {
            "$it = user_daily_activity_stats.$it + EXCLUDED.$it"
        }

        val sql = """
            INSERT INTO user_daily_activity_stats (
                anonymized_user_id,
                activity_date,
                $columnsSql,
                analytics_consent,
                updated_at
            )
            VALUES (?, ?, $valuesSql, TRUE, NOW())
            ON CONFLICT (anonymized_user_id, activity_date)
            DO UPDATE SET
                activity_date = EXCLUDED.activity_date,
                $updatesSql,
                analytics_consent = TRUE,
                updated_at = NOW();
        """.trimIndent()

        repeat(MAX_SERIALIZATION_RETRIES + 1) { attempt ->
            try {
                return dbQuery {
                    (TransactionManager.current().connection.connection as java.sql.Connection)
                        .prepareStatement(sql)
                        .use { statement ->
                            var index = 1
                            statement.setString(index++, anonymizedUserId)
                            statement.setDate(index++, Date.valueOf(date))
                            normalized.values.forEach { amount ->
                                statement.setInt(index++, amount)
                            }
                            statement.executeUpdate()
                        }
                    true
                }
            } catch (e: Exception) {
                val sqlState = (e as? SQLException)?.sqlState ?: (e.cause as? SQLException)?.sqlState
                val isSerializationConflict = sqlState == "40001" ||
                    e.message?.contains("could not serialize access due to concurrent update", ignoreCase = true) == true

                if (isSerializationConflict && attempt < MAX_SERIALIZATION_RETRIES) {
                    val backoffMs = 10L * (attempt + 1)
                    delay(backoffMs)
                    return@repeat
                }

                log.warn(
                    "Failed batch increment for anonymizedUserId={} date={} counters={} after {} attempt(s): {}",
                    anonymizedUserId,
                    date,
                    normalized,
                    attempt + 1,
                    e.message
                )
                return false
            }
        }

        return false
    }

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
                ON CONFLICT (anonymized_user_id, activity_date)
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
                    average_response_time,
                    total_response_time_ms,
                    analytics_consent,
                    updated_at
                )
                VALUES (
                    ?,
                    ?,
                    1,
                    jsonb_build_object(?, 1),
                    jsonb_build_object(?, jsonb_build_object('count', 1, 'totalMs', ?, 'minMs', ?, 'maxMs', ?)),
                    ?,
                    ?,
                    TRUE,
                    NOW()
                )
                ON CONFLICT (anonymized_user_id, activity_date)
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
                    average_response_time = CASE
                        WHEN user_daily_activity_stats.average_response_time <= 0 OR user_daily_activity_stats.total_response_time_ms <= 0 THEN ?
                        ELSE (
                            (user_daily_activity_stats.total_response_time_ms + ?) /
                            ((user_daily_activity_stats.total_response_time_ms / user_daily_activity_stats.average_response_time) + 1)
                        )
                    END,
                    total_response_time_ms = user_daily_activity_stats.total_response_time_ms + ?,
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
                    statement.setInt(i++, boundedMs)
                    statement.setLong(i++, boundedMs.toLong())

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
                    statement.setInt(i++, boundedMs)
                    statement.setLong(i++, boundedMs.toLong())
                    statement.setLong(i, boundedMs.toLong())

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
                    average_response_time,
                    total_response_time_ms,
                    analytics_consent,
                    updated_at
                )
                VALUES (?, ?, ?, ?, TRUE, NOW())
                ON CONFLICT (anonymized_user_id, activity_date)
                DO UPDATE SET
                    activity_date = EXCLUDED.activity_date,
                    average_response_time = CASE
                        WHEN user_daily_activity_stats.average_response_time <= 0 OR user_daily_activity_stats.total_response_time_ms <= 0 THEN EXCLUDED.average_response_time
                        ELSE (
                            (user_daily_activity_stats.total_response_time_ms + EXCLUDED.total_response_time_ms) /
                            ((user_daily_activity_stats.total_response_time_ms / user_daily_activity_stats.average_response_time) + 1)
                        )
                    END,
                    total_response_time_ms = user_daily_activity_stats.total_response_time_ms + EXCLUDED.total_response_time_ms,
                    analytics_consent = TRUE,
                    updated_at = NOW();
            """.trimIndent()

            (TransactionManager.current().connection.connection as java.sql.Connection)
                .prepareStatement(sql)
                .use { statement ->
                    statement.setString(1, anonymizedUserId)
                    statement.setDate(2, Date.valueOf(date))
                    statement.setLong(3, boundedMs.toLong())
                    statement.setLong(4, boundedMs.toLong())
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
    ): Boolean {
        if (amount <= 0) return true

        val allowedColumns = setOf(
            "api_request_count",
            "active_minutes",
            "session_count",
            "search_count",
            "nearby_search_count",
            "profile_update_count",
            "chat_messages_sent_count",
            "chat_messages_received_count",
            "transactions_created_count",
            "reviews_submitted_count",
            "successful_actions_count"
        )

        if (counterColumn !in allowedColumns) {
            log.warn("Rejected unknown analytics counter column: {}", counterColumn)
            return false
        }

        val sql = """
            INSERT INTO user_daily_activity_stats (
                anonymized_user_id,
                activity_date,
                $counterColumn,
                analytics_consent,
                updated_at
            )
            VALUES (?, ?, ?, TRUE, NOW())
            ON CONFLICT (anonymized_user_id, activity_date)
            DO UPDATE SET
                activity_date = EXCLUDED.activity_date,
                $counterColumn = user_daily_activity_stats.$counterColumn + EXCLUDED.$counterColumn,
                analytics_consent = TRUE,
                updated_at = NOW();
        """.trimIndent()

        repeat(MAX_SERIALIZATION_RETRIES + 1) { attempt ->
            try {
                return dbQuery {
                    (TransactionManager.current().connection.connection as java.sql.Connection)
                        .prepareStatement(sql)
                        .use { statement ->
                            statement.setString(1, anonymizedUserId)
                            statement.setDate(2, Date.valueOf(date))
                            statement.setInt(3, amount)
                            statement.executeUpdate()
                        }
                    true
                }
            } catch (e: Exception) {
                val sqlState = (e as? SQLException)?.sqlState ?: (e.cause as? SQLException)?.sqlState
                val isSerializationConflict = sqlState == "40001" ||
                    e.message?.contains("could not serialize access due to concurrent update", ignoreCase = true) == true

                if (isSerializationConflict && attempt < MAX_SERIALIZATION_RETRIES) {
                    val backoffMs = 10L * (attempt + 1)
                    delay(backoffMs)
                    return@repeat
                }

                log.warn(
                    "Failed incrementing daily stats counter={} for anonymizedUserId={} date={} after {} attempt(s): {}",
                    counterColumn,
                    anonymizedUserId,
                    date,
                    attempt + 1,
                    e.message
                )
                return false
            }
        }

        return false
    }
}
