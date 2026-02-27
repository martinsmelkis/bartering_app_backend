package app.bartering.features.chat.utils

import app.bartering.features.chat.dao.ChatAnalyticsDao
import app.bartering.features.chat.dao.OfflineMessageDaoImpl
import app.bartering.features.chat.dao.ReadReceiptDao
import app.bartering.features.chat.manager.ConnectionManager
import app.bartering.features.chat.model.AuthResponse
import app.bartering.features.chat.model.ChatConnection
import app.bartering.features.chat.model.ErrorMessage
import app.bartering.features.chat.model.FileNotificationMessage
import app.bartering.features.chat.model.MessageStatus
import app.bartering.features.chat.model.MessageStatusUpdate
import app.bartering.features.chat.model.OfflineMessageDto
import app.bartering.features.chat.model.P2PAuthMessage
import app.bartering.features.chat.model.ReadReceiptDto
import app.bartering.features.chat.model.ReadReceiptNotification
import app.bartering.features.chat.model.ServerChatMessage
import app.bartering.features.chat.model.SocketMessage
import app.bartering.features.chat.model.TransactionCreatedMessage
import app.bartering.features.encryptedfiles.dao.EncryptedFileDaoImpl
import app.bartering.features.notifications.model.NotificationPriority
import app.bartering.features.notifications.model.PushNotification
import app.bartering.features.notifications.service.PushNotificationService
import app.bartering.features.notifications.utils.NotificationDataBuilder
import app.bartering.features.reviews.dao.BarterTransactionDao
import app.bartering.features.reviews.model.TransactionStatus
import app.bartering.utils.CryptoUtils
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.math.abs

/**
 * Utility class for chat route operations
 */
object ChatUtils {

    // ==================== Constants ====================

    private const val AUTH_TIMESTAMP_WINDOW_MS = 300000L // 5 minutes
    private const val MIN_MESSAGES_FOR_TRANSACTION = 1

    // ==================== Authentication Utils ====================

    /**
     * Validate authentication timestamp to prevent replay attacks
     * @param timestamp Timestamp from auth request
     * @return true if valid, false if expired
     */
    fun isValidTimestamp(timestamp: Long): Boolean {
        val currentTime = System.currentTimeMillis()
        return abs(currentTime - timestamp) <= AUTH_TIMESTAMP_WINDOW_MS
    }

    /**
     * Verify signature for authentication
     * @param challenge Challenge string to verify
     * @param signatureBase64 Base64-encoded signature
     * @param publicKeyBase64 Base64-encoded public key
     * @return true if signature is valid
     */
    fun verifySignature(
        challenge: String,
        signatureBase64: String,
        publicKeyBase64: String
    ): Boolean {
        return try {
            val publicKey = CryptoUtils.convertRawB64KeyToECPublicKey(publicKeyBase64)
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(publicKey)
            signature.update(challenge.toByteArray())
            val signatureBytes = Base64.getDecoder().decode(signatureBase64)
            signature.verify(signatureBytes)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Build authentication challenge string
     * @param timestamp Request timestamp
     * @param userId User ID
     * @param peerUserId Peer user ID
     * @return Challenge string
     */
    fun buildAuthChallenge(timestamp: Long, userId: String, peerUserId: String): String {
        return "$timestamp.$userId.$peerUserId"
    }

    // ==================== WebSocket Response Utils ====================

    /**
     * Send error response and close connection
     * @param session WebSocket session
     * @param serializer Message serializer
     * @param errorMessage Error message to send
     * @param closeReason Close reason code
     * @param closeMessage Close message
     */
    suspend fun sendErrorAndClose(
        session: WebSocketSession,
        serializer: KSerializer<SocketMessage>,
        errorMessage: String,
        closeReason: CloseReason.Codes,
        closeMessage: String
    ) {
        session.outgoing.send(
            Frame.Text(
                Json.encodeToString(
                    serializer,
                    AuthResponse(false, errorMessage)
                )
            )
        )
        session.close(CloseReason(closeReason, closeMessage))
    }

    /**
     * Send socket message to session
     * @param session WebSocket session
     * @param serializer Message serializer
     * @param message Message to send
     */
    suspend fun sendMessage(
        session: WebSocketSession,
        serializer: KSerializer<SocketMessage>,
        message: SocketMessage
    ) {
        session.send(Frame.Text(Json.encodeToString(serializer, message)))
    }

    // ==================== Offline Message Utils ====================

    /**
     * Deliver pending offline messages to user
     * @param userId User ID to deliver messages to
     * @param session WebSocket session
     * @param serializer Message serializer
     * @param offlineMessageDao DAO for offline messages
     * @param conversationState Conversation state map for tracking
     * @param readReceiptDao DAO for read receipts
     * @param connectionManager Connection manager to get sender connections
     * @param scope Coroutine scope for async operations
     * @param log Logger instance
     */
    suspend fun deliverOfflineMessages(
        userId: String,
        session: WebSocketSession,
        serializer: KSerializer<SocketMessage>,
        offlineMessageDao: OfflineMessageDaoImpl,
        conversationState: MutableMap<String, Instant>,
        readReceiptDao: ReadReceiptDao,
        connectionManager: ConnectionManager,
        scope: CoroutineScope,
        log: Logger
    ) {
        val pendingMessages = offlineMessageDao.getPendingMessages(userId)
        if (pendingMessages.isEmpty()) return

        log.info("Delivering {} offline messages to userId={}", pendingMessages.size, userId)

        pendingMessages.forEach { offlineMsg ->
            try {
                // If it's a federated message with a sender public key, send that first
                if (offlineMsg.senderPublicKey != null) {
                    val senderKeyMessage = P2PAuthMessage(
                        senderId = offlineMsg.senderId,
                        publicKey = offlineMsg.senderPublicKey
                    )
                    session.outgoing.send(
                        Frame.Text(Json.encodeToString(serializer, senderKeyMessage))
                    )
                    log.debug("Sent sender public key for offline message from {}", offlineMsg.senderId)
                }
                
                val serverMessage = ServerChatMessage(
                    senderId = offlineMsg.senderId,
                    text = offlineMsg.encryptedPayload,
                    timestamp = offlineMsg.timestamp,
                    recipientId = userId,
                    serverMessageId = offlineMsg.id,
                    senderName = offlineMsg.senderName
                )
                session.outgoing.send(
                    Frame.Text(Json.encodeToString(serializer, serverMessage))
                )
                offlineMessageDao.markAsDelivered(offlineMsg.id)

                // Track message received for response time analytics
                val stateKey = "$userId:${offlineMsg.senderId}"
                conversationState[stateKey] = Instant.ofEpochMilli(offlineMsg.timestamp)
                log.debug("📊 Tracked offline message from {} to {}", offlineMsg.senderId, userId)

                // Send DELIVERED receipt to original sender (if online)
                scope.launch {
                    val senderConnection = connectionManager.getConnection(offlineMsg.senderId)
                    handleReadReceipt(
                        messageId = offlineMsg.id,
                        senderId = offlineMsg.senderId,
                        recipientId = userId,
                        status = MessageStatus.DELIVERED,
                        senderConnection = senderConnection,
                        readReceiptDao = readReceiptDao,
                        serializer = serializer,
                        log = log
                    )
                }
            } catch (e: Exception) {
                log.error("Failed to deliver offline message id={}", offlineMsg.id, e)
            }
        }
    }

    /**
     * Store message for offline delivery
     * @param senderId Sender user ID
     * @param recipientId Recipient user ID
     * @param senderName Sender name
     * @param encryptedPayload Encrypted message payload
     * @param offlineMessageDao DAO for offline messages
     * @return Stored offline message or null if storage failed
     */
    suspend fun storeOfflineMessage(
        senderId: String,
        recipientId: String,
        senderName: String,
        encryptedPayload: String,
        offlineMessageDao: OfflineMessageDaoImpl
    ): OfflineMessageDto? {
        val offlineMessage = OfflineMessageDto(
            id = UUID.randomUUID().toString(),
            senderId = senderId,
            recipientId = recipientId,
            senderName = senderName,
            encryptedPayload = encryptedPayload,
            timestamp = System.currentTimeMillis()
        )

        return if (offlineMessageDao.storeOfflineMessage(offlineMessage)) {
            offlineMessage
        } else {
            null
        }
    }

    // ==================== File Notification Utils ====================

    /**
     * Notify user about pending encrypted files
     * @param userId User ID to notify
     * @param session WebSocket session
     * @param serializer Message serializer
     * @param encryptedFileDao DAO for encrypted files
     * @param log Logger instance
     */
    suspend fun notifyPendingFiles(
        userId: String,
        session: WebSocketSession,
        serializer: KSerializer<SocketMessage>,
        encryptedFileDao: EncryptedFileDaoImpl,
        log: Logger
    ) {
        val pendingFiles = encryptedFileDao.getPendingFiles(userId)
        if (pendingFiles.isEmpty()) return

        log.info("Notifying userId={} about {} pending files", userId, pendingFiles.size)

        pendingFiles.forEach { fileDto ->
            try {
                val notification = FileNotificationMessage(
                    fileId = fileDto.id,
                    senderId = fileDto.senderId,
                    filename = fileDto.filename,
                    mimeType = fileDto.mimeType,
                    fileSize = fileDto.fileSize,
                    expiresAt = fileDto.expiresAt,
                    timestamp = System.currentTimeMillis()
                )
                session.outgoing.send(
                    Frame.Text(Json.encodeToString(serializer, notification))
                )
            } catch (e: Exception) {
                log.error("Failed to send file notification id={}", fileDto.id, e)
            }
        }
    }

    // ==================== Transaction Utils ====================

    /**
     * Track message count and potentially create transaction
     * @param senderId Sender user ID
     * @param recipientId Recipient user ID
     * @param senderName Sender display name
     * @param senderConnection Sender's connection
     * @param recipientConnection Recipient's connection
     * @param conversationMessageCounts Message count map
     * @param transactionDao Transaction DAO
     * @param serializer Message serializer
     * @param scope Coroutine scope for async operations
     * @param log Logger instance
     */
    fun trackMessageAndCreateTransaction(
        senderId: String,
        recipientId: String,
        senderName: String?,
        senderConnection: ChatConnection,
        recipientConnection: ChatConnection,
        conversationMessageCounts: MutableMap<String, Int>,
        transactionDao: BarterTransactionDao,
        serializer: KSerializer<SocketMessage>,
        scope: CoroutineScope,
        log: Logger
    ) {
        // Track message count
        val senderKey = "$senderId:$recipientId"
        val currentSenderCount = conversationMessageCounts.getOrDefault(senderKey, 0) + 1
        conversationMessageCounts[senderKey] = currentSenderCount

        // Check if both users have sent at least 1 message (2-way conversation)
        val recipientKey = "$recipientId:$senderId"
        val recipientCount = conversationMessageCounts.getOrDefault(recipientKey, 0)

        if (currentSenderCount >= MIN_MESSAGES_FOR_TRANSACTION &&
            recipientCount >= MIN_MESSAGES_FOR_TRANSACTION) {

            scope.launch {
                try {
                    // Check if there's an active (non-completed) transaction between users
                    val existingTransactions = transactionDao.getTransactionsBetweenUsers(
                        senderId,
                        recipientId
                    )

                    // Only block creation if there's an active transaction (PENDING or DISPUTED)
                    // Allow new transactions if all existing ones are completed (DONE, CANCELLED, etc.)
                    val hasActiveTransaction = existingTransactions.any { transaction ->
                        transaction.status == TransactionStatus.PENDING ||
                        transaction.status == TransactionStatus.DISPUTED
                    }

                    if (!hasActiveTransaction) {
                        // Create new transaction
                        val transactionId = transactionDao.createTransaction(
                            user1Id = senderId,
                            user2Id = recipientId,
                            estimatedValue = null
                        )
                        log.info("✅ Created transaction {} for conversation between {} and {}",
                            transactionId, senderId, recipientId)

                        // Notify both users
                        notifyTransactionCreated(
                            transactionId = transactionId,
                            senderId = senderId,
                            recipientId = recipientId,
                            senderName = senderName,
                            senderConnection = senderConnection,
                            recipientConnection = recipientConnection,
                            serializer = serializer,
                            log = log
                        )

                        // Reset message counts
                        conversationMessageCounts.remove(senderKey)
                        conversationMessageCounts.remove(recipientKey)
                    }
                } catch (e: Exception) {
                    log.error("⚠️ Failed to create transaction", e)
                }
            }
        }
    }

    /**
     * Notify users that a transaction was created
     */
    private suspend fun notifyTransactionCreated(
        transactionId: String,
        senderId: String,
        recipientId: String,
        senderName: String?,
        senderConnection: ChatConnection,
        recipientConnection: ChatConnection,
        serializer: KSerializer<SocketMessage>,
        log: Logger
    ) {
        val timestamp = System.currentTimeMillis()

        // Notify sender
        try {
            val notificationToSender = TransactionCreatedMessage(
                transactionId = transactionId,
                partnerId = recipientId,
                partnerName = recipientConnection.userName ?: recipientId,
                initiatedAt = timestamp
            )
            senderConnection.session.send(
                Frame.Text(Json.encodeToString(serializer, notificationToSender))
            )
        } catch (e: Exception) {
            log.warn("⚠️ Failed to notify sender", e)
        }

        // Notify recipient
        try {
            val notificationToRecipient = TransactionCreatedMessage(
                transactionId = transactionId,
                partnerId = senderId,
                partnerName = senderConnection.userName ?: senderId,
                initiatedAt = timestamp
            )
            recipientConnection.session.send(
                Frame.Text(Json.encodeToString(serializer, notificationToRecipient))
            )
        } catch (e: Exception) {
            log.warn("⚠️ Failed to notify recipient", e)
        }
    }

    // ==================== Response Time Analytics Utils ====================

    /**
     * Track when a message is received for response time analytics
     * @param userId User who received the message
     * @param senderId User who sent the message
     * @param conversationState Conversation state map
     * @param log Logger instance
     */
    fun trackMessageReceived(
        userId: String,
        senderId: String,
        conversationState: MutableMap<String, Instant>,
        log: Logger
    ) {
        val stateKey = "$userId:$senderId"
        conversationState[stateKey] = Instant.now()
        log.debug("📊 Tracked message from {} to {}", senderId, userId)
    }

    /**
     * Record response time if user is responding to a previous message
     * @param userId User who is responding
     * @param recipientId User being responded to
     * @param conversationState Conversation state map
     * @param chatAnalyticsDao Analytics DAO
     * @param scope Coroutine scope for async operations
     * @param log Logger instance
     */
    fun recordResponseTimeIfApplicable(
        userId: String,
        recipientId: String,
        conversationState: MutableMap<String, Instant>,
        chatAnalyticsDao: ChatAnalyticsDao,
        scope: CoroutineScope,
        log: Logger
    ) {
        val senderStateKey = "$userId:$recipientId"
        val lastReceivedAt = conversationState[senderStateKey]

        if (lastReceivedAt != null) {
            scope.launch {
                try {
                    chatAnalyticsDao.recordResponseTime(
                        userId = userId,
                        conversationPartnerId = recipientId,
                        messageReceivedAt = lastReceivedAt,
                        responseSentAt = Instant.now()
                    )
                    log.debug("📊 Recorded response time for {} to {}", userId, recipientId)
                } catch (e: Exception) {
                    log.warn("⚠️ Failed to record response time", e)
                }
            }
            conversationState.remove(senderStateKey)
        }
    }

    // ==================== Push Notification Utils ====================

    /**
     * Send push notification for offline message
     * @param senderId Sender user ID
     * @param senderName Sender display name
     * @param recipientId Recipient user ID
     * @param messageId Message ID
     * @param pushNotificationService Push notification service
     * @param scope Coroutine scope for async operations
     * @param log Logger instance
     */
    fun sendOfflineMessageNotification(
        senderId: String,
        senderName: String,
        recipientId: String,
        messageId: String,
        pushNotificationService: PushNotificationService,
        scope: CoroutineScope,
        log: Logger
    ) {
        scope.launch {
            try {
                val notificationData = NotificationDataBuilder.newMessage(
                    senderId = senderId,
                    senderName = senderName,
                    messageId = messageId,
                    timestamp = System.currentTimeMillis()
                )

                pushNotificationService.sendToUser(
                    userId = recipientId,
                    notification = PushNotification(
                        tokens = emptyList(),
                        notification = notificationData,
                        priority = NotificationPriority.HIGH,
                        sound = "default",
                        channelId = "chat_messages",
                        data = mapOf(
                            "type" to "new_message",
                            "senderId" to senderId
                        )
                    )
                )
                log.info("✅ Push notification sent to offline user {}", recipientId)
            } catch (e: Exception) {
                log.warn("⚠️ Failed to send push notification", e)
            }
        }
    }

    // ==================== Read Receipt Utils ====================

    /**
     * Store a read receipt and notify the original sender
     * @param messageId Message ID that was read
     * @param senderId Original sender of the message
     * @param recipientId User who read the message
     * @param status Message status (DELIVERED or READ)
     * @param senderConnection Sender's connection (if online)
     * @param readReceiptDao DAO for read receipts
     * @param serializer Message serializer
     * @param log Logger instance
     */
    suspend fun handleReadReceipt(
        messageId: String,
        senderId: String,
        recipientId: String,
        status: MessageStatus,
        senderConnection: ChatConnection?,
        readReceiptDao: ReadReceiptDao,
        serializer: KSerializer<SocketMessage>,
        log: Logger
    ) {
        // Store receipt in database
        val receipt = ReadReceiptDto(
            messageId = messageId,
            senderId = senderId,
            recipientId = recipientId,
            status = status,
            timestamp = System.currentTimeMillis()
        )

        val stored = readReceiptDao.storeReadReceipt(receipt)
        if (!stored) {
            log.warn("Failed to store read receipt for messageId={}", messageId)
            return
        }

        log.debug("📬 Stored {} receipt for message {} from {} to {}",
            status, messageId, senderId, recipientId)

        // Notify sender if online
        if (senderConnection != null && senderConnection.session.outgoing.isClosedForSend.not()) {
            try {
                val notification = ReadReceiptNotification(
                    messageId = messageId,
                    readerId = recipientId,
                    timestamp = receipt.timestamp,
                    status = status
                )
                sendMessage(senderConnection.session, serializer, notification)
                log.debug("📬 Sent {} notification to sender {}", status, senderId)
            } catch (e: Exception) {
                log.warn("Failed to send read receipt notification to sender", e)
            }
        }
    }

    /**
     * Send message status update to sender
     * @param messageId Message ID
     * @param status Message status
     * @param session WebSocket session
     * @param serializer Message serializer
     * @param log Logger instance
     */
    suspend fun sendMessageStatusUpdate(
        messageId: String,
        status: MessageStatus,
        session: WebSocketSession,
        serializer: KSerializer<SocketMessage>,
        log: Logger
    ) {
        try {
            val statusUpdate = MessageStatusUpdate(
                messageId = messageId,
                status = status,
                timestamp = System.currentTimeMillis()
            )
            sendMessage(session, serializer, statusUpdate)
            log.debug("📤 Sent status update {} for message {}", status, messageId)
        } catch (e: Exception) {
            log.warn("Failed to send message status update", e)
        }
    }

    // ==================== Cleanup Utils ====================

    /**
     * Clean up conversation state and message counts for disconnected user
     * @param userId User ID to clean up
     * @param conversationState Conversation state map
     * @param conversationMessageCounts Message count map
     * @param log Logger instance
     */
    fun cleanupUserState(
        userId: String,
        conversationState: MutableMap<String, Instant>,
        conversationMessageCounts: MutableMap<String, Int>,
        log: Logger
    ) {
        val stateKeysToRemove = conversationState.keys.filter { it.startsWith("$userId:") }
        stateKeysToRemove.forEach { conversationState.remove(it) }

        val countKeysToRemove = conversationMessageCounts.keys.filter { it.startsWith("$userId:") }
        countKeysToRemove.forEach { conversationMessageCounts.remove(it) }

        val totalKeysRemoved = stateKeysToRemove.size + countKeysToRemove.size
        if (totalKeysRemoved > 0) {
            log.debug("🧹 Cleaned up {} conversation entries for userId={}", totalKeysRemoved, userId)
        }
    }

    // ==================== Federated Message Utils ====================

    /**
     * Check if a recipient ID is a federated user (format: userId@serverId)
     */
    fun isFederatedUser(recipientId: String): Boolean {
        return recipientId.contains("@") && recipientId.lastIndexOf("@") > 0 &&
               recipientId.lastIndexOf("@") < recipientId.length - 1
    }

    /**
     * Send a message to a federated user on another server.
     * @param recipientId The federated user ID (format: userId@serverId)
     * @param senderId The local sender user ID
     * @param senderName The sender's display name
     * @param encryptedPayload The encrypted message content
     * @param senderPublicKey The sender's public key for recipient to verify/decrypt
     * @param federationService The federation service for sending the message
     * @param currentConnection The sender's connection for status updates
     * @param serializer Message serializer
     * @param log Logger instance
     * @return The message ID if successfully relayed, null otherwise
     */
    suspend fun sendFederatedMessage(
        recipientId: String,
        senderId: String,
        senderName: String,
        encryptedPayload: String,
        senderPublicKey: String,
        federationService: app.bartering.features.federation.service.FederationService,
        currentConnection: app.bartering.features.chat.model.ChatConnection,
        serializer: KSerializer<SocketMessage>,
        log: Logger
    ): String? {
        val messageId = java.util.UUID.randomUUID().toString()

        log.info("Sending federated message from {} to {}", senderId, recipientId)

        // Send the message via federation service
        val relayedMessageId = federationService.sendMessageToFederatedUser(
            recipientUserId = recipientId,
            senderUserId = senderId,
            senderName = senderName,
            encryptedPayload = encryptedPayload,
            senderPublicKey = senderPublicKey
        )

        if (relayedMessageId != null) {
            // Send SENT status to sender
            sendMessageStatusUpdate(
                messageId = relayedMessageId,
                status = MessageStatus.SENT,
                session = currentConnection.session,
                serializer = serializer,
                log = log
            )

            log.info("Federated message sent successfully: {} -> {}, messageId={}",
                senderId, recipientId, relayedMessageId)
        } else {
            // Send error to sender
            sendMessage(
                session = currentConnection.session,
                serializer = serializer,
                message = ErrorMessage("Failed to send message to federated user $recipientId")
            )

            log.warn("Failed to send federated message: {} -> {}", senderId, recipientId)
        }

        return relayedMessageId
    }
}