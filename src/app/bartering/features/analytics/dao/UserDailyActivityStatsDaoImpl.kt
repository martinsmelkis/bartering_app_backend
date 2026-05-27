package app.bartering.features.analytics.dao

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.analytics.db.UserDailyActivityStatsTable
import app.bartering.features.analytics.model.DashboardDailyStats
import app.bartering.features.analytics.model.DashboardStatsResponse
import app.bartering.features.analytics.model.DashboardStatsSummary
import app.bartering.features.analytics.model.UserDailyActivityStats
import app.bartering.features.profile.db.UserRegistrationDataTable
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

    override suspend fun getDashboardStats(days: Int): DashboardStatsResponse = dbQuery {
        val boundedDays = days.coerceIn(1, 365)
        val sql = """
            WITH date_series AS (
                SELECT generate_series(
                    CURRENT_DATE - (?::int - 1),
                    CURRENT_DATE,
                    INTERVAL '1 day'
                )::date AS activity_date
            ), activity AS (
                SELECT
                    activity_date,
                    COUNT(DISTINCT anonymized_user_id)::int AS active_users,
                    COALESCE(SUM(active_minutes), 0)::int AS active_minutes,
                    COALESCE(SUM(session_count), 0)::int AS session_count,
                    COALESCE(SUM(api_request_count), 0)::int AS api_request_count,
                    COALESCE(SUM(search_count), 0)::int AS search_count,
                    COALESCE(SUM(nearby_search_count), 0)::int AS nearby_search_count,
                    COALESCE(SUM(profile_update_count), 0)::int AS profile_update_count,
                    COALESCE(SUM(chat_messages_sent_count), 0)::int AS chat_messages_sent_count,
                    COALESCE(SUM(chat_messages_received_count), 0)::int AS chat_messages_received_count,
                    COALESCE(SUM(transactions_created_count), 0)::int AS transactions_created_count,
                    COALESCE(SUM(reviews_submitted_count), 0)::int AS reviews_submitted_count,
                    COALESCE(SUM(successful_actions_count), 0)::int AS successful_actions_count
                FROM user_daily_activity_stats
                WHERE activity_date BETWEEN CURRENT_DATE - (?::int - 1) AND CURRENT_DATE
                GROUP BY activity_date
            ), registrations AS (
                SELECT
                    created_at::date AS activity_date,
                    COUNT(*)::int AS new_registrations
                FROM user_registration_data
                WHERE created_at::date BETWEEN CURRENT_DATE - (?::int - 1) AND CURRENT_DATE
                GROUP BY created_at::date
            )
            SELECT
                ds.activity_date,
                COALESCE(a.active_users, 0) AS active_users,
                COALESCE(r.new_registrations, 0) AS new_registrations,
                COALESCE(a.active_minutes, 0) AS active_minutes,
                COALESCE(a.session_count, 0) AS session_count,
                COALESCE(a.api_request_count, 0) AS api_request_count,
                COALESCE(a.search_count, 0) AS search_count,
                COALESCE(a.nearby_search_count, 0) AS nearby_search_count,
                COALESCE(a.profile_update_count, 0) AS profile_update_count,
                COALESCE(a.chat_messages_sent_count, 0) AS chat_messages_sent_count,
                COALESCE(a.chat_messages_received_count, 0) AS chat_messages_received_count,
                COALESCE(a.transactions_created_count, 0) AS transactions_created_count,
                COALESCE(a.reviews_submitted_count, 0) AS reviews_submitted_count,
                COALESCE(a.successful_actions_count, 0) AS successful_actions_count
            FROM date_series ds
            LEFT JOIN activity a ON a.activity_date = ds.activity_date
            LEFT JOIN registrations r ON r.activity_date = ds.activity_date
            ORDER BY ds.activity_date DESC;
        """.trimIndent()

        val daily = mutableListOf<DashboardDailyStats>()
        (TransactionManager.current().connection.connection as java.sql.Connection)
            .prepareStatement(sql)
            .use { statement ->
                statement.setInt(1, boundedDays)
                statement.setInt(2, boundedDays)
                statement.setInt(3, boundedDays)
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        daily.add(
                            DashboardDailyStats(
                                date = rs.getDate("activity_date").toLocalDate().toString(),
                                activeUsers = rs.getInt("active_users"),
                                newRegistrations = rs.getInt("new_registrations"),
                                activeMinutes = rs.getInt("active_minutes"),
                                sessionCount = rs.getInt("session_count"),
                                apiRequestCount = rs.getInt("api_request_count"),
                                searchCount = rs.getInt("search_count"),
                                nearbySearchCount = rs.getInt("nearby_search_count"),
                                profileUpdateCount = rs.getInt("profile_update_count"),
                                chatMessagesSentCount = rs.getInt("chat_messages_sent_count"),
                                chatMessagesReceivedCount = rs.getInt("chat_messages_received_count"),
                                transactionsCreatedCount = rs.getInt("transactions_created_count"),
                                reviewsSubmittedCount = rs.getInt("reviews_submitted_count"),
                                successfulActionsCount = rs.getInt("successful_actions_count")
                            )
                        )
                    }
                }
            }

        val totalUsers = UserRegistrationDataTable.selectAll().count().toInt()
        val summary = DashboardStatsSummary(
            totalUsers = totalUsers,
            totalActiveUsers = daily.sumOf { it.activeUsers },
            totalNewRegistrations = daily.sumOf { it.newRegistrations },
            totalApiRequests = daily.sumOf { it.apiRequestCount },
            totalSessions = daily.sumOf { it.sessionCount },
            totalActiveMinutes = daily.sumOf { it.activeMinutes }
        )

        DashboardStatsResponse(
            success = true,
            days = boundedDays,
            summary = summary,
            daily = daily
        )
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
