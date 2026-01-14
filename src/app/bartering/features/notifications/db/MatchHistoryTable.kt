package app.bartering.features.notifications.db

import app.bartering.features.profile.db.UserRegistrationDataTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Match history - tracks what users have been notified about
 */
object MatchHistoryTable : Table("match_history") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 255).references(UserRegistrationDataTable.id)
    val matchType = varchar("match_type", 30)
    val sourceType = varchar("source_type", 30)
    val sourceId = varchar("source_id", 100)
    val targetType = varchar("target_type", 30)
    val targetId = varchar("target_id", 255)
    val matchScore = double("match_score")
    val matchReason = text("match_reason").nullable()
    val notificationSent = bool("notification_sent").default(false)
    val notificationSentAt = timestamp("notification_sent_at").nullable()
    val viewed = bool("viewed").default(false)
    val viewedAt = timestamp("viewed_at").nullable()
    val dismissed = bool("dismissed").default(false)
    val dismissedAt = timestamp("dismissed_at").nullable()
    val matchedAt = timestamp("matched_at").default(Instant.now())
    
    override val primaryKey = PrimaryKey(id)
}