package org.barter.features.chat.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Database table for storing offline messages
 * Messages are stored here when the recipient is not connected
 */
object OfflineMessagesTable : Table("offline_messages") {
    val id = varchar("id", 36).uniqueIndex() // UUID
    val senderId = varchar("sender_id", 255)
    val recipientId = varchar("recipient_id", 255).index()
    val encryptedPayload = text("encrypted_payload")
    val timestamp = timestamp("timestamp").default(Instant.now())
    val delivered = bool("delivered").default(false)
    val createdAt = timestamp("created_at").default(Instant.now())

    override val primaryKey = PrimaryKey(id)
}
