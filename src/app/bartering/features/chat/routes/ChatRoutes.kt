package app.bartering.features.chat.routes

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.isActive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import app.bartering.features.chat.cache.PublicKeyCache
import app.bartering.features.chat.dao.ChatAnalyticsDao
import app.bartering.features.encryptedfiles.dao.EncryptedFileDaoImpl
import app.bartering.features.chat.dao.OfflineMessageDaoImpl
import app.bartering.features.chat.manager.ConnectionManager
import app.bartering.features.chat.model.*
import app.bartering.features.encryptedfiles.tasks.FileCleanupTask
import app.bartering.features.chat.tasks.ChatAnalyticsCleanupTask
import app.bartering.features.chat.tasks.MessageCleanupTask
import app.bartering.features.profile.dao.UserProfileDaoImpl
import app.bartering.features.notifications.service.PushNotificationService
import app.bartering.features.reviews.dao.BarterTransactionDao
import app.bartering.features.notifications.model.PushNotification
import app.bartering.features.notifications.model.NotificationPriority
import app.bartering.features.notifications.utils.NotificationDataBuilder
import app.bartering.localization.Localization
import app.bartering.utils.CryptoUtils
import org.koin.java.KoinJavaComponent.inject
import kotlinx.coroutines.launch
import java.security.Signature
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import org.slf4j.LoggerFactory

fun Application.chatRoutes(connectionManager: ConnectionManager) {
    val log = LoggerFactory.getLogger("app.bartering.features.chat.routes.ChatRoutes")
    // Connection manager to handle WebSocket connections
    // In production with multiple servers, replace with Redis-based implementation
    // for distributed connection management and cross-server message routing

    // Cache for public keys to reduce database lookups
    // In production with multiple servers, use Redis for shared cache
    val publicKeyCache = PublicKeyCache(expirationTimeMinutes = 60)

    // DAO for storing and retrieving offline messages
    val offlineMessageDao = OfflineMessageDaoImpl()

    // DAO for storing and retrieving encrypted files
    val encryptedFileDao = EncryptedFileDaoImpl()

    // Push notification service for offline message notifications
    val pushNotificationService: PushNotificationService by inject(PushNotificationService::class.java)

    // Chat analytics DAO for tracking response times
    val chatAnalyticsDao: ChatAnalyticsDao by inject(ChatAnalyticsDao::class.java)
    
    // Transaction DAO for creating trades when users chat
    val transactionDao: BarterTransactionDao by inject(BarterTransactionDao::class.java)
    
    // Relationships DAO for checking blocked status
    val relationshipsDao: app.bartering.features.relationships.dao.UserRelationshipsDaoImpl by inject(app.bartering.features.relationships.dao.UserRelationshipsDaoImpl::class.java)
    
    // Shared conversation state for tracking when users receive messages
    // Maps "userId:partnerId" -> timestamp when message was received
    // In production with multiple servers, use Redis for distributed state
    val conversationState = java.util.concurrent.ConcurrentHashMap<String, java.time.Instant>()
    
    // Track conversation message counts for transaction creation
    // Maps "userId:partnerId" -> message count
    val conversationMessageCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // Background task for cleaning up old delivered messages
    val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val messageCleanupTask =
        MessageCleanupTask(offlineMessageDao, intervalHours = 24, retentionDays = 7)
    messageCleanupTask.start(cleanupScope)

    // Background task for cleaning up expired/downloaded files
    val fileCleanupTask = FileCleanupTask(encryptedFileDao, intervalHours = 1)
    fileCleanupTask.start(cleanupScope)

    // Background task for cleaning up old chat analytics data
    val analyticsCleanupTask = ChatAnalyticsCleanupTask(chatAnalyticsDao, intervalHours = 24, retentionDays = 90)
    analyticsCleanupTask.start(cleanupScope)

    val socketSerializer: KSerializer<SocketMessage> = SocketMessage.serializer()

    routing {
        webSocket("/chat") { // The WebSocket endpoint
            val currentConnection = ChatConnection(this)
            log.info("New client connected! Connection ID: {}", currentConnection.id)
            val usersDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)

            // Example of getting locale from header
            val locale = call.request.headers["Accept-Language"]?.let {
                Locale.forLanguageTag(it.split(",").firstOrNull() ?: "en")
            } ?: Locale.ENGLISH

            try {
                // 1. Authentication Phase
                var isAuthenticated = false
                for (frame in incoming) { // Expecting an AuthRequest first
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        try {
                            log.debug("WebSocket attempting to decode auth request: {}", text)
                            val authRequest = Json.decodeFromString<AuthRequest>(text)

                            // --- SIGNATURE-BASED AUTHENTICATION ---
                            // Verify that the user owns the private key corresponding to their public key

                            if (authRequest.userId.isBlank()) {
                                outgoing.send(
                                    Frame.Text(
                                        Json.encodeToString<SocketMessage>(
                                            socketSerializer,
                                            AuthResponse(false, "Invalid userId")
                                        )
                                    )
                                )
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY,
                                    "Invalid userId"))
                                return@webSocket
                            }

                            // 1. Prevent replay attacks by checking timestamp
                            val currentTime = System.currentTimeMillis()
                            if (abs(currentTime - authRequest.timestamp) > 300000) { // 5 minute window
                                outgoing.send(
                                    Frame.Text(
                                        Json.encodeToString<SocketMessage>(
                                            socketSerializer,
                                            AuthResponse(
                                                false,
                                                "Authentication request has expired"
                                            )
                                        )
                                    )
                                )
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY,
                                    "Expired timestamp"))
                                return@webSocket
                            }

                            // 2. Get the user's registered public key from the database
                            val registeredPublicKey = try {
                                usersDao.getUserPublicKeyById(authRequest.userId)
                            } catch (e: Exception) {
                                log.error("Error fetching public key for userId={}: {}", authRequest.userId, e.message)
                                outgoing.send(
                                    Frame.Text(
                                        Json.encodeToString<SocketMessage>(
                                            socketSerializer,
                                            AuthResponse(false, "User not found")
                                        )
                                    )
                                )
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY,
                                        "User not found"))
                                return@webSocket
                            }

                            if (registeredPublicKey == null) {
                                outgoing.send(
                                    Frame.Text(
                                        Json.encodeToString<SocketMessage>(
                                            socketSerializer,
                                            AuthResponse(false, "User not found")
                                        )
                                    )
                                )
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY,
                                    "User not found"))
                                return@webSocket
                            }

                            // 3. Verify that the provided public key matches the registered one
                            if (authRequest.publicKey != registeredPublicKey) {
                                log.warn("Public key mismatch for userId={}", authRequest.userId)
                                outgoing.send(
                                    Frame.Text(
                                        Json.encodeToString<SocketMessage>(
                                            socketSerializer,
                                            AuthResponse(false, "Invalid public key")
                                        )
                                    )
                                )
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY,
                                    "Invalid public key"))
                                return@webSocket
                            }

                            // 4. Verify the signature
                            // Challenge format: "timestamp.userId.peerUserId"
                            val challenge =
                                "${authRequest.timestamp}.${authRequest.userId}.${authRequest.peerUserId}"

                            try {
                                val publicKey =
                                    CryptoUtils.convertRawB64KeyToECPublicKey(registeredPublicKey)
                                val signature = Signature.getInstance("SHA256withECDSA")
                                signature.initVerify(publicKey)
                                signature.update(challenge.toByteArray())

                                val signatureBytes =
                                    Base64.getDecoder().decode(authRequest.signature)

                                if (!signature.verify(signatureBytes)) {
                                    log.warn("Invalid signature for userId={}", authRequest.userId)
                                    outgoing.send(
                                        Frame.Text(
                                            Json.encodeToString<SocketMessage>(
                                                socketSerializer,
                                                AuthResponse(false, "Invalid signature")
                                            )
                                        )
                                    )
                                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY,
                                        "Invalid signature"))
                                    return@webSocket
                                }
                            } catch (e: Exception) {
                                log.error("Signature verification error for userId={}", authRequest.userId, e)
                                outgoing.send(
                                    Frame.Text(
                                        Json.encodeToString<SocketMessage>(
                                            socketSerializer,
                                            AuthResponse(false, "Signature verification failed")
                                        )
                                    )
                                )
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY,
                                    "Signature verification failed"))
                                return@webSocket
                            }

                            // 5. Check if users have blocked each other
                            val isBlockedByPeer = relationshipsDao.isBlocked(authRequest.peerUserId, authRequest.userId)
                            val hasBlockedPeer = relationshipsDao.isBlocked(authRequest.userId, authRequest.peerUserId)
                            
                            if (isBlockedByPeer || hasBlockedPeer) {
                                log.warn("Blocked relationship detected between {} and {}", authRequest.userId, authRequest.peerUserId)
                                outgoing.send(
                                    Frame.Text(
                                        Json.encodeToString<SocketMessage>(
                                            socketSerializer,
                                            AuthResponse(false, "Cannot establish chat connection")
                                        )
                                    )
                                )
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY,
                                    "Blocked relationship"))
                                return@webSocket
                            }

                            // 6. Authentication successful!
                            currentConnection.userId = authRequest.userId
                            currentConnection.userName = authRequest.userName
                            currentConnection.userPublicKey = authRequest.publicKey

                            val isNewConnection = connectionManager.getConnection(authRequest.userId) == null
                            log.debug("New connection check for userId={}: isNew={}", authRequest.userId, isNewConnection)
                            // Add connection to manager (handles closing old sessions automatically)
                            connectionManager.addConnection(
                                authRequest.userId,
                                currentConnection
                            )
                            isAuthenticated = true

                            val welcomeMessage = Localization.getString("chat.welcome", locale)

                            outgoing.send(
                                Frame.Text(
                                    Json.encodeToString<SocketMessage>(
                                        socketSerializer,
                                        AuthResponse(
                                            true,
                                            "$welcomeMessage (Authenticated as ${authRequest.userId})"
                                        )
                                    )
                                )
                            )
                            log.info("User {} with peer {} authenticated for connection {}", 
                                authRequest.userId, authRequest.peerUserId, currentConnection.id)

                            // Send my Public key to peer, if he/she is already online
                            if (isNewConnection && connectionManager.getConnection(authRequest.peerUserId) != null) {
                                val serverMessage = P2PAuthMessage(
                                    senderId = authRequest.userId,
                                    publicKey = authRequest.publicKey,
                                )
                                connectionManager.getConnection(authRequest.peerUserId)?.session?.send(
                                    Frame.Text(Json.encodeToString<SocketMessage>(socketSerializer, serverMessage)))
                            }

                            // Get recipient public key with caching to prevent excessive DB lookups
                            val recipientKey = publicKeyCache.get(authRequest.peerUserId)
                            ?: connectionManager.getConnection(authRequest.peerUserId)?.userPublicKey
                            ?: usersDao.getUserPublicKeyById(authRequest.peerUserId)
                                ?.also { key ->
                                    // Cache the key from database for future use
                                    publicKeyCache.put(authRequest.peerUserId, key)
                                }

                            if (currentConnection.recipientPublicKey == null) {
                                currentConnection.recipientPublicKey = recipientKey
                                val serverMessage = P2PAuthMessage(
                                    senderId = authRequest.peerUserId,
                                    publicKey = recipientKey ?: "",
                                )
                                currentConnection.session.send(
                                    Frame.Text(Json.encodeToString<SocketMessage>(socketSerializer, serverMessage)))
                            }

                            break // Exit auth loop
                        } catch (e: Exception) {
                            log.error("Authentication error", e)
                            outgoing.send(Frame.Text(Json.encodeToString<SocketMessage>(socketSerializer,
                                ErrorMessage("Invalid auth format: ${e.localizedMessage}"))))
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid auth format"))
                            return@webSocket
                        }
                    }
                }

                if (!isAuthenticated || currentConnection.userId == null) {
                    log.warn("Client failed to authenticate. Connection ID: {}", currentConnection.id)
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
                    return@webSocket
                }

                // 2. Messaging Phase
                val currentUserId = currentConnection.userId!!
                log.info("User {} (connection {}) entered messaging phase", currentUserId, currentConnection.id)

                // Deliver any pending offline messages to the newly connected user
                val pendingMessages =
                    offlineMessageDao.getPendingMessages(currentUserId)
                if (pendingMessages.isNotEmpty()) {
                    log.info("Delivering {} offline messages to userId={}", pendingMessages.size, currentUserId)
                    pendingMessages.forEach { offlineMsg ->
                        try {
                            val serverMessage = ServerChatMessage(
                                senderId = offlineMsg.senderId,
                                text = offlineMsg.encryptedPayload,
                                timestamp = offlineMsg.timestamp,
                                recipientId = currentUserId,
                                serverMessageId = offlineMsg.id,
                                senderName = offlineMsg.senderName
                            )
                            outgoing.send(
                                Frame.Text(
                                    Json.encodeToString<SocketMessage>(
                                        socketSerializer,
                                        serverMessage
                                    )
                                )
                            )
                            // Mark as delivered
                            offlineMessageDao.markAsDelivered(offlineMsg.id)
                            
                            // Track message received for response time analytics
                            // Use the original message timestamp (when it was sent to us while offline)
                            val stateKey = "$currentUserId:${offlineMsg.senderId}"
                            conversationState[stateKey] = java.time.Instant.ofEpochMilli(offlineMsg.timestamp)
                            log.debug("📊 Tracked offline message from {} to {}", offlineMsg.senderId, currentUserId)
                        } catch (e: Exception) {
                            log.error("Failed to deliver offline message id={}", offlineMsg.id, e)
                        }
                    }
                }

                // Notify user about pending encrypted files
                val pendingFiles = encryptedFileDao.getPendingFiles(currentUserId)
                if (pendingFiles.isNotEmpty()) {
                    log.info("Notifying userId={} about {} pending files", currentUserId, pendingFiles.size)
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
                            outgoing.send(
                                Frame.Text(
                                    Json.encodeToString<SocketMessage>(
                                        socketSerializer,
                                        notification
                                    )
                                )
                            )
                        } catch (e: Exception) {
                            log.error("Failed to send file notification id={}", fileDto.id, e)
                        }
                    }
                }

                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val receivedText = frame.readText()
                        log.debug("Received from userId={}: {}", currentUserId, receivedText)
                        try {
                            val clientMessage = Json.decodeFromString<ClientChatMessage>(receivedText)

                            log.debug("Client message recipient: {}", clientMessage.data.recipientId)
                            val recipientConnection = connectionManager.getConnection(clientMessage.data.recipientId)

                            if (recipientConnection != null && recipientConnection.session.isActive) {

                                // If recipientConnection does not have auth flag then
                                // set sender public key and set as true, then send P2PAuthMessage message first
                                if (recipientConnection.recipientPublicKey != null) {

                                    val serverMessage = ServerChatMessage(
                                        senderId = currentUserId,
                                        text = clientMessage.data.encryptedPayload ?: "",
                                        timestamp = System.currentTimeMillis(),
                                        recipientId = clientMessage.data.recipientId,
                                        serverMessageId = UUID.randomUUID().toString(),
                                        senderName = clientMessage.data.senderName
                                    )
                                    log.debug("Relaying message from {} to {}", currentUserId, clientMessage.data.recipientId)
                                    recipientConnection.session.send(
                                        Frame.Text(
                                            Json.encodeToString(
                                                socketSerializer,
                                                serverMessage
                                            )
                                        )
                                    )
                                    
                                    // Track when recipient receives this message (for their response time)
                                    val recipientStateKey = "${clientMessage.data.recipientId}:$currentUserId"
                                    conversationState[recipientStateKey] = java.time.Instant.now()
                                    log.debug("📊 Tracked message from {} to {}", currentUserId, clientMessage.data.recipientId)
                                    
                                    // Track message count for transaction creation
                                    val senderKey = "$currentUserId:${clientMessage.data.recipientId}"
                                    val currentSenderCount = conversationMessageCounts.getOrDefault(senderKey, 0) + 1
                                    conversationMessageCounts[senderKey] = currentSenderCount
                                    
                                    // Check if both users have sent at least 1 message (2-way conversation)
                                    val recipientKey = "${clientMessage.data.recipientId}:$currentUserId"
                                    val recipientCount = conversationMessageCounts.getOrDefault(recipientKey, 0)
                                    
                                    if (currentSenderCount >= 1 && recipientCount >= 1) {
                                        // Both users have participated - create transaction if it doesn't exist
                                        cleanupScope.launch {
                                            try {
                                                // Check if transaction already exists
                                                val existingTransactions = transactionDao.getTransactionsBetweenUsers(
                                                    currentUserId,
                                                    clientMessage.data.recipientId
                                                )
                                                
                                                if (existingTransactions.isEmpty()) {
                                                    // Create new transaction
                                                    val transactionId = transactionDao.createTransaction(
                                                        user1Id = currentUserId,
                                                        user2Id = clientMessage.data.recipientId,
                                                        estimatedValue = null
                                                    )
                                                    log.info("✅ Created transaction {} for conversation between {} and {}", 
                                                        transactionId, currentUserId, clientMessage.data.recipientId)
                                                    
                                                    // Notify both users about the transaction
                                                    val timestamp = System.currentTimeMillis()
                                                    // Notify current user
                                                    try {
                                                        val notificationToSender = TransactionCreatedMessage(
                                                            transactionId = transactionId,
                                                            partnerId = clientMessage.data.recipientId,
                                                            partnerName = clientMessage.data.senderName, // Will be recipient's name
                                                            initiatedAt = timestamp
                                                        )
                                                        currentConnection.session.send(
                                                            Frame.Text(Json.encodeToString(socketSerializer, notificationToSender))
                                                        )
                                                    } catch (e: Exception) {
                                                        log.warn("⚠️ Failed to notify sender", e)
                                                    }
                                                    // Notify recipient (if online)
                                                    try {
                                                        val notificationToRecipient = TransactionCreatedMessage(
                                                            transactionId = transactionId,
                                                            partnerId = currentUserId,
                                                            partnerName = currentConnection.userName ?: currentUserId,
                                                            initiatedAt = timestamp
                                                        )
                                                        recipientConnection.session.send(
                                                            Frame.Text(Json.encodeToString(socketSerializer, notificationToRecipient))
                                                        )
                                                    } catch (e: Exception) {
                                                        log.warn("⚠️ Failed to notify recipient", e)
                                                    }
                                                    
                                                    // Reset message counts for this conversation
                                                    conversationMessageCounts.remove(senderKey)
                                                    conversationMessageCounts.remove(recipientKey)
                                                }
                                            } catch (e: Exception) {
                                                log.error("⚠️ Failed to create transaction", e)
                                            }
                                        }
                                    }
                                    
                                    // Check if current user is responding to a previous message from recipient
                                    val senderStateKey = "$currentUserId:${clientMessage.data.recipientId}"
                                    val lastReceivedAt = conversationState[senderStateKey]
                                    if (lastReceivedAt != null) {
                                        // This is a response - record the response time
                                        cleanupScope.launch {
                                            try {
                                                chatAnalyticsDao.recordResponseTime(
                                                    userId = currentUserId,
                                                    conversationPartnerId = clientMessage.data.recipientId,
                                                    messageReceivedAt = lastReceivedAt,
                                                    responseSentAt = java.time.Instant.now()
                                                )
                                                log.debug("📊 Recorded response time for {} to {}", currentUserId, clientMessage.data.recipientId)
                                            } catch (e: Exception) {
                                                log.warn("⚠️ Failed to record response time", e)
                                            }
                                        }
                                        // Clear the tracked timestamp after recording
                                        conversationState.remove(senderStateKey)
                                    }
                                }
                            } else {
                                log.debug("Recipient {} not found or inactive. Message from {} stored for offline delivery", 
                                    clientMessage.data.recipientId, currentUserId)

                                // Store message for offline delivery in database
                                val offlineMessage = OfflineMessageDto(
                                    id = UUID.randomUUID().toString(),
                                    senderId = currentUserId,
                                    recipientId = clientMessage.data.recipientId,
                                    senderName = clientMessage.data.senderName,
                                    encryptedPayload = clientMessage.data.encryptedPayload ?: "",
                                    timestamp = System.currentTimeMillis()
                                )

                                val stored = offlineMessageDao.storeOfflineMessage(offlineMessage)
                                if (stored) {
                                    log.info("Message stored for offline delivery to userId={}", clientMessage.data.recipientId)
                                    
                                    // Send push notification to offline recipient
                                    cleanupScope.launch {
                                        try {
                                            val notificationData = NotificationDataBuilder.newMessage(
                                                senderId = currentUserId,
                                                senderName = clientMessage.data.senderName,
                                                messageId = offlineMessage.id,
                                                timestamp = offlineMessage.timestamp
                                            )
                                            
                                            pushNotificationService.sendToUser(
                                                userId = clientMessage.data.recipientId,
                                                notification = PushNotification(
                                                    tokens = emptyList(), // Will be populated by sendToUser
                                                    notification = notificationData,
                                                    priority = NotificationPriority.HIGH,
                                                    sound = "default",
                                                    channelId = "chat_messages",
                                                    data = mapOf("type" to "new_message",
                                                        "senderId" to clientMessage.data.senderId)
                                                )
                                            )
                                            log.info("✅ Push notification sent to offline user {}", clientMessage.data.recipientId)
                                        } catch (e: Exception) {
                                            log.warn("⚠️ Failed to send push notification", e)
                                        }
                                    }
                                    
                                    currentConnection.session.send(
                                        Frame.Text(
                                            Json.encodeToString<SocketMessage>(
                                                socketSerializer,
                                                ErrorMessage("Recipient ${clientMessage.data.recipientId} " +
                                                        "is offline. Message will be delivered when they come online.")
                                            )
                                        )
                                    )
                                } else {
                                    log.error("Failed to store offline message for userId={}", clientMessage.data.recipientId)
                                    currentConnection.session.send(
                                        Frame.Text(
                                            Json.encodeToString<SocketMessage>(
                                                socketSerializer,
                                                ErrorMessage("Recipient ${clientMessage.data.recipientId} " +
                                                        "is offline and message storage failed.")
                                            )
                                        )
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            log.error("Error processing message from userId={}", currentUserId, e)
                            currentConnection.session.send(Frame.Text(
                                Json.encodeToString<SocketMessage>(socketSerializer,
                                ErrorMessage("Error processing message: ${e.localizedMessage}"))))
                        }
                    }
                }
            } catch (_: ClosedReceiveChannelException) {
                log.info("Client {} disconnected (channel closed)", currentConnection.userId ?: "ID: ${currentConnection.id}")
            } catch (e: Throwable) {
                log.error("Error for {}", currentConnection.userId ?: "ID: ${currentConnection.id}", e)
            } finally {
                currentConnection.userId?.let { userId ->
                    // Remove connection from manager (only if it's the current connection)
                    connectionManager.removeConnection(userId, currentConnection.id)
                    
                    // Clean up conversation state for this user to prevent memory leaks
                    val keysToRemove = conversationState.keys.filter { it.startsWith("$userId:") }
                    keysToRemove.forEach { conversationState.remove(it) }
                    
                    // Clean up message counts for this user
                    val messageCountKeys = conversationMessageCounts.keys.filter { it.startsWith("$userId:") }
                    messageCountKeys.forEach { conversationMessageCounts.remove(it) }
                    
                    val totalKeysRemoved = keysToRemove.size + messageCountKeys.size
                    if (totalKeysRemoved > 0) {
                        log.debug("🧹 Cleaned up {} conversation entries for userId={}", totalKeysRemoved, userId)
                    }
                }
                log.info("Connection ID {} terminated", currentConnection.id)
                // No need to explicitly close session here if it's already closed by client or an error
            }
        }
    }

}