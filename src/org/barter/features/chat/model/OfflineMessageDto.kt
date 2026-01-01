package org.barter.features.chat.model

import kotlinx.serialization.Serializable

/**
 * Data transfer object for offline messages
 */
@Serializable
data class OfflineMessageDto(
    val id: String,
    val senderId: String,
    val recipientId: String,
    val encryptedPayload: String,
    val timestamp: Long,
    val delivered: Boolean = false
)
