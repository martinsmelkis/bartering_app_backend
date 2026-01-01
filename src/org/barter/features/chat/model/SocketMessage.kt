package org.barter.features.chat.model

import kotlinx.serialization.Serializable

@Serializable
sealed class SocketMessage // Base class for different message types over WebSocket

@Serializable
data class ClientChatMessage(
    val type: String = "chat_message",
    val data: ChatMessageData
) : SocketMessage()

@Serializable
data class ServerChatMessage(
    val senderId: String,
    val text: String, // In a real E2EE app, this would be encryptedPayload
    val timestamp: Long,
    val recipientId: String,
    val serverMessageId: String
) : SocketMessage()

@Serializable
data class ErrorMessage(
    val error: String
) : SocketMessage()

@Serializable
data class AuthRequest( // Client sends this as first message after connecting
    val userId: String,
    val peerUserId: String,
    val publicKey: String,
    val timestamp: Long, // Unix timestamp in milliseconds
    val signature: String // ECDSA signature of: "$timestamp.$userId.$peerUserId"
) : SocketMessage()

@Serializable
data class P2PAuthMessage( // Client sends this as first message after connecting
    val senderId: String,
    val publicKey: String,
) : SocketMessage()

@Serializable
data class AuthResponse(
    val success: Boolean,
    val message: String
) : SocketMessage()

@Serializable
data class FileNotificationMessage(
    val fileId: String,
    val senderId: String,
    val filename: String,
    val mimeType: String,
    val fileSize: Long,
    val expiresAt: Long,
    val timestamp: Long
) : SocketMessage()

@Serializable
data class FileUploadResponse(
    val success: Boolean,
    val fileId: String? = null,
    val message: String
) : SocketMessage()