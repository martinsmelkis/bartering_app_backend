package app.bartering.features.analytics.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant
import java.time.LocalDate

object UserDailyActivityStatsTable : Table("user_daily_activity_stats") {
    val anonymizedUserId = varchar("anonymized_user_id", 64)
    val activityDate = date("activity_date").default(LocalDate.now())

    val activeMinutes = integer("active_minutes").default(0)
    val sessionCount = integer("session_count").default(0)
    val apiRequestCount = integer("api_request_count").default(0)

    val searchCount = integer("search_count").default(0)
    val nearbySearchCount = integer("nearby_search_count").default(0)
    val profileUpdateCount = integer("profile_update_count").default(0)
    val chatMessagesSentCount = integer("chat_messages_sent_count").default(0)
    val chatMessagesReceivedCount = integer("chat_messages_received_count").default(0)
    val transactionsCreatedCount = integer("transactions_created_count").default(0)
    val reviewsSubmittedCount = integer("reviews_submitted_count").default(0)

    val successfulActionsCount = integer("successful_actions_count").default(0)

    val analyticsConsent = bool("analytics_consent").default(false)
    val consentVersion = varchar("consent_version", 50).nullable()

    // searched_keywords is currently updated via raw SQL in DAO (JSONB), not mapped in Exposed model.
    @Suppress("unused")
    val averageResponseTime = long("average_response_time").default(0)
    @Suppress("unused")
    val totalResponseTimeMs = long("total_response_time_ms").default(0)

    val createdAt = timestamp("created_at").default(Instant.now())
    val updatedAt = timestamp("updated_at").default(Instant.now())

    override val primaryKey = PrimaryKey(anonymizedUserId, activityDate)
}
