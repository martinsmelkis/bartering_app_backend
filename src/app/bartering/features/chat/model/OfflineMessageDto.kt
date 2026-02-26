package app.bartering.features.chat.model

import kotlinx.serialization.Serializable

/**
 * Data transfer object for offline messages
 */
@Serializable
data class OfflineMessageDto(
    val id: String,
    val senderId: String,
    val recipientId: String,
    val senderName: String,
    val encryptedPayload: String,
    val timestamp: Long,
    val delivered: Boolean = false,
    val senderPublicKey: String? = null // For federated messages, include sender's public key
)
