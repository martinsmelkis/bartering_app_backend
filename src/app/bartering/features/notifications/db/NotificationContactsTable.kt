package app.bartering.features.notifications.db

import app.bartering.features.profile.db.UserRegistrationDataTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import java.time.Instant

/**
 * User notification contact information
 */
object UserNotificationContactsTable : Table("user_notification_contacts") {
    val userId = varchar("user_id", 255).references(UserRegistrationDataTable.id)
    val email = varchar("email", 255).nullable()
    val emailVerified = bool("email_verified").default(false)
    val emailVerificationToken = varchar("email_verification_token", 100).nullable()
    val emailVerifiedAt = timestamp("email_verified_at").nullable()
    val pushTokens = jsonb<List<Map<String, String>>>("push_tokens", kotlinx.serialization.json.Json)
    val notificationsEnabled = bool("notifications_enabled").default(true)
    val quietHoursStart = integer("quiet_hours_start").nullable()
    val quietHoursEnd = integer("quiet_hours_end").nullable()
    val createdAt = timestamp("created_at").default(Instant.now())
    val updatedAt = timestamp("updated_at").default(Instant.now())
    
    override val primaryKey = PrimaryKey(userId)
}
