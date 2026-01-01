package org.barter.features.notifications.db

import org.barter.features.profile.db.UserRegistrationDataTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Notification preferences for user attributes
 */
object AttributeNotificationPreferencesTable : Table("attribute_notification_preferences") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 255).references(UserRegistrationDataTable.id)
    val attributeId = varchar("attribute_id", 100)
    val notificationsEnabled = bool("notifications_enabled").default(true)
    val notificationFrequency = varchar("notification_frequency", 20).default("INSTANT")
    val minMatchScore = double("min_match_score").default(0.7)
    val notifyOnNewPostings = bool("notify_on_new_postings").default(true)
    val notifyOnNewUsers = bool("notify_on_new_users").default(false)
    val createdAt = timestamp("created_at").default(Instant.now())
    val updatedAt = timestamp("updated_at").default(Instant.now())
    
    override val primaryKey = PrimaryKey(id)
}