package app.bartering.features.chat.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Database table for storing message read receipts
 * Tracks when messages are delivered and read by recipients
 */
object ReadReceiptsTable : Table("chat_read_receipts") {
    val messageId = varchar("message_id", 100).index() // Server message ID (supports UUIDs and prefixed system messages like "system_transaction_<uuid>")
    val senderId = varchar("sender_id", 255).index() // Original message sender
    val recipientId = varchar("recipient_id", 255).index() // Message recipient
    val status = varchar("status", 20) // SENT, DELIVERED, READ
    val timestamp = long("timestamp") // When the status changed (client timestamp)
    val createdAt = timestamp("created_at").default(Instant.now()) // Server timestamp
    
    override val primaryKey = PrimaryKey(messageId, recipientId)
    
    init {
        index(isUnique = false, senderId, messageId) // For querying receipts by sender
    }
}
