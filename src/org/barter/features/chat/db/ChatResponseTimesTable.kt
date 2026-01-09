package org.barter.features.chat.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Database table for tracking chat response times
 * Used to calculate average response time for QUICK_RESPONDER badge
 */
object ChatResponseTimesTable : Table("chat_response_times") {
    val id = varchar("id", 36) // UUID
    val userId = varchar("user_id", 255).index()
    val conversationPartnerId = varchar("conversation_partner_id", 255)
    val messageReceivedAt = timestamp("message_received_at")
    val responseSentAt = timestamp("response_sent_at")
    val responseTimeHours = decimal("response_time_hours", 10, 2)
    val createdAt = timestamp("created_at").default(Instant.now())

    override val primaryKey = PrimaryKey(id)
}
