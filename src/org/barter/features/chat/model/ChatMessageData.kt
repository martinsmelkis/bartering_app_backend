package org.barter.features.chat.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessageData(val id: String, val senderId: String, val senderName: String, val recipientId: String,
                           val encryptedPayload: String?, val timestamp: String)