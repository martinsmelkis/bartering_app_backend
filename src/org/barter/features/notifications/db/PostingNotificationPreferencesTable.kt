package org.barter.features.notifications.db

import org.barter.features.postings.db.UserPostingsTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Notification preferences for user postings
 */
object PostingNotificationPreferencesTable : Table("posting_notification_preferences") {
    val postingId = varchar("posting_id", 36).references(UserPostingsTable.id)
    val notificationsEnabled = bool("notifications_enabled").default(true)
    val notificationFrequency = varchar("notification_frequency", 20).default("INSTANT")
    val minMatchScore = double("min_match_score").default(0.7)
    val createdAt = timestamp("created_at").default(Instant.now())
    val updatedAt = timestamp("updated_at").default(Instant.now())
    
    override val primaryKey = PrimaryKey(postingId)
}