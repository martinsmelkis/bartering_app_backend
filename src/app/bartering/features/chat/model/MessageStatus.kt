package app.bartering.features.chat.model

import kotlinx.serialization.Serializable

/**
 * Enum representing the status of a chat message
 */
@Serializable
enum class MessageStatus {
    /**
     * Message has been sent by the sender but not yet delivered to recipient
     */
    SENT,
    
    /**
     * Message has been delivered to the recipient's device
     */
    DELIVERED,
    
    /**
     * Message has been read/viewed by the recipient
     */
    READ
}

/**
 * Client sends this when they read a message
 */
@Serializable
data class ReadReceiptRequest(
    val messageId: String,
    val senderId: String, // The original sender of the message
    val timestamp: Long = System.currentTimeMillis()
) : SocketMessage()

/**
 * Server sends this to notify sender that their message was read
 */
@Serializable
data class ReadReceiptNotification(
    val messageId: String,
    val readerId: String, // Who read the message
    val timestamp: Long,
    val status: MessageStatus // DELIVERED or READ
) : SocketMessage()

/**
 * Server sends this to confirm message was sent/delivered
 */
@Serializable
data class MessageStatusUpdate(
    val messageId: String,
    val status: MessageStatus,
    val timestamp: Long
) : SocketMessage()

/**
 * Database model for storing read receipts
 */
data class ReadReceiptDto(
    val messageId: String,
    val senderId: String,
    val recipientId: String,
    val status: MessageStatus,
    val timestamp: Long,
    val createdAt: java.time.Instant = java.time.Instant.now()
)
