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
import app.bartering.features.chat.utils.ChatUtils
import app.bartering.features.profile.dao.UserProfileDaoImpl
import app.bartering.features.reviews.dao.BarterTransactionDao
import app.bartering.features.notifications.service.PushNotificationService
import app.bartering.localization.Localization
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject
import java.util.Locale
import java.util.UUID
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
    
    // Read receipt DAO for tracking message status
    val readReceiptDao = app.bartering.features.chat.dao.ReadReceiptDaoImpl()
    
    // Relationships DAO for checking blocked status
    val relationshipsDao: app.bartering.features.relationships.dao.UserRelationshipsDaoImpl by
        inject(app.bartering.features.relationships.dao.UserRelationshipsDaoImpl::class.java)
    
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

    // Background task for cleaning up old read receipts
    val readReceiptCleanupTask = app.bartering.features.chat.tasks.ReadReceiptCleanupTask(
        readReceiptDao, 
        intervalHours = 24, 
        retentionDays = 30  // Keep read receipts for 30 days
    )
    readReceiptCleanupTask.start(cleanupScope)

    val socketSerializer: KSerializer<SocketMessage> = SocketMessage.serializer()
    
    // Json instance that ignores unknown keys (for backward compatibility)
    val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

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
                            if (!ChatUtils.isValidTimestamp(authRequest.timestamp)) {
                                ChatUtils.sendErrorAndClose(
                                    session = this,
                                    serializer = socketSerializer,
                                    errorMessage = "Authentication request has expired",
                                    closeReason = CloseReason.Codes.VIOLATED_POLICY,
                                    closeMessage = "Expired timestamp"
                                )
                                return@webSocket
                            }

                            // 2. Get the user's registered public key from the database
                            val registeredPublicKey = try {
                                usersDao.getUserPublicKeyById(authRequest.userId)
                            } catch (e: Exception) {
                                log.error("Error fetching public key for userId={}: {}", authRequest.userId, e.message)
                                ChatUtils.sendErrorAndClose(
                                    session = this,
                                    serializer = socketSerializer,
                                    errorMessage = "User not found",
                                    closeReason = CloseReason.Codes.VIOLATED_POLICY,
                                    closeMessage = "User not found"
                                )
                                return@webSocket
                            }

                            if (registeredPublicKey == null) {
                                ChatUtils.sendErrorAndClose(
                                    session = this,
                                    serializer = socketSerializer,
                                    errorMessage = "User not found",
                                    closeReason = CloseReason.Codes.VIOLATED_POLICY,
                                    closeMessage = "User not found"
                                )
                                return@webSocket
                            }

                            // 3. Verify that the provided public key matches the registered one
                            if (authRequest.publicKey != registeredPublicKey) {
                                log.warn("Public key mismatch for userId={}", authRequest.userId)
                                ChatUtils.sendErrorAndClose(
                                    session = this,
                                    serializer = socketSerializer,
                                    errorMessage = "Invalid public key",
                                    closeReason = CloseReason.Codes.VIOLATED_POLICY,
                                    closeMessage = "Invalid public key"
                                )
                                return@webSocket
                            }

                            // 4. Verify the signature
                            val challenge = ChatUtils.buildAuthChallenge(
                                authRequest.timestamp,
                                authRequest.userId,
                                authRequest.peerUserId
                            )

                            if (!ChatUtils.verifySignature(challenge, authRequest.signature, registeredPublicKey)) {
                                log.warn("Invalid signature for userId={}", authRequest.userId)
                                ChatUtils.sendErrorAndClose(
                                    session = this,
                                    serializer = socketSerializer,
                                    errorMessage = "Invalid signature",
                                    closeReason = CloseReason.Codes.VIOLATED_POLICY,
                                    closeMessage = "Invalid signature"
                                )
                                return@webSocket
                            }

                            // 5. Check if users have blocked each other
                            val isBlockedByPeer = relationshipsDao.isBlocked(authRequest.peerUserId, authRequest.userId)
                            val hasBlockedPeer = relationshipsDao.isBlocked(authRequest.userId, authRequest.peerUserId)
                            
                            if (isBlockedByPeer || hasBlockedPeer) {
                                log.warn("Blocked relationship detected between {} and {}", authRequest.userId, authRequest.peerUserId)
                                ChatUtils.sendErrorAndClose(
                                    session = this,
                                    serializer = socketSerializer,
                                    errorMessage = "Cannot establish chat connection",
                                    closeReason = CloseReason.Codes.VIOLATED_POLICY,
                                    closeMessage = "Blocked relationship"
                                )
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
                ChatUtils.deliverOfflineMessages(
                    userId = currentUserId,
                    session = this,
                    serializer = socketSerializer,
                    offlineMessageDao = offlineMessageDao,
                    conversationState = conversationState,
                    readReceiptDao = readReceiptDao,
                    connectionManager = connectionManager,
                    scope = cleanupScope,
                    log = log
                )

                // Notify user about pending encrypted files
                ChatUtils.notifyPendingFiles(
                    userId = currentUserId,
                    session = this,
                    serializer = socketSerializer,
                    encryptedFileDao = encryptedFileDao,
                    log = log
                )

                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val receivedText = frame.readText()
                        log.debug("Received from userId={}: {}", currentUserId, receivedText)
                        try {
                            // Check if this is a read receipt request
                            if (receivedText.contains("\"messageId\"") && 
                                receivedText.contains("\"senderId\"") && 
                                !receivedText.contains("\"data\"")) {
                                // This is a ReadReceiptRequest
                                val readReceiptRequest = jsonParser.decodeFromString<ReadReceiptRequest>(receivedText)
                                log.debug("Read receipt from userId={} for message {}", currentUserId, readReceiptRequest.messageId)
                                
                                // Store receipt and notify sender
                                val senderConnection = connectionManager.getConnection(readReceiptRequest.senderId)
                                ChatUtils.handleReadReceipt(
                                    messageId = readReceiptRequest.messageId,
                                    senderId = readReceiptRequest.senderId,
                                    recipientId = currentUserId,
                                    status = MessageStatus.READ,
                                    senderConnection = senderConnection,
                                    readReceiptDao = readReceiptDao,
                                    serializer = socketSerializer,
                                    log = log
                                )
                                continue // Skip to next frame
                            }
                            
                            // Otherwise, it's a standard chat message
                            val clientMessage = Json.decodeFromString<ClientChatMessage>(receivedText)

                            log.debug("Client message recipient: {}", clientMessage.data.recipientId)
                            val recipientConnection = connectionManager.getConnection(clientMessage.data.recipientId)

                            if (recipientConnection != null && recipientConnection.session.isActive) {

                                // If recipientConnection does not have auth flag then
                                // set sender public key and set as true, then send P2PAuthMessage message first
                                if (recipientConnection.recipientPublicKey != null) {

                                    val messageId = UUID.randomUUID().toString()
                                    val serverMessage = ServerChatMessage(
                                        senderId = currentUserId,
                                        text = clientMessage.data.encryptedPayload ?: "",
                                        timestamp = System.currentTimeMillis(),
                                        recipientId = clientMessage.data.recipientId,
                                        serverMessageId = messageId,
                                        senderName = clientMessage.data.senderName
                                    )
                                    log.debug("Relaying message from {} to {}", currentUserId, clientMessage.data.recipientId)
                                    
                                    // Send the message to recipient
                                    recipientConnection.session.send(
                                        Frame.Text(
                                            Json.encodeToString(
                                                socketSerializer,
                                                serverMessage
                                            )
                                        )
                                    )
                                    
                                    // Send SENT status to sender
                                    ChatUtils.sendMessageStatusUpdate(
                                        messageId = messageId,
                                        status = MessageStatus.SENT,
                                        session = currentConnection.session,
                                        serializer = socketSerializer,
                                        log = log
                                    )
                                    
                                    // Store DELIVERED receipt and notify sender (async)
                                    cleanupScope.launch {
                                        ChatUtils.handleReadReceipt(
                                            messageId = messageId,
                                            senderId = currentUserId,
                                            recipientId = clientMessage.data.recipientId,
                                            status = MessageStatus.DELIVERED,
                                            senderConnection = currentConnection,
                                            readReceiptDao = readReceiptDao,
                                            serializer = socketSerializer,
                                            log = log
                                        )
                                    }
                                    
                                    // Track when recipient receives this message (for their response time)
                                    ChatUtils.trackMessageReceived(
                                        userId = clientMessage.data.recipientId,
                                        senderId = currentUserId,
                                        conversationState = conversationState,
                                        log = log
                                    )
                                    
                                    // Track message count and create transaction if applicable
                                    ChatUtils.trackMessageAndCreateTransaction(
                                        senderId = currentUserId,
                                        recipientId = clientMessage.data.recipientId,
                                        senderName = clientMessage.data.senderName,
                                        senderConnection = currentConnection,
                                        recipientConnection = recipientConnection,
                                        conversationMessageCounts = conversationMessageCounts,
                                        transactionDao = transactionDao,
                                        serializer = socketSerializer,
                                        scope = cleanupScope,
                                        log = log
                                    )
                                    
                                    // Check if current user is responding to a previous message from recipient
                                    ChatUtils.recordResponseTimeIfApplicable(
                                        userId = currentUserId,
                                        recipientId = clientMessage.data.recipientId,
                                        conversationState = conversationState,
                                        chatAnalyticsDao = chatAnalyticsDao,
                                        scope = cleanupScope,
                                        log = log
                                    )
                                }
                            } else {
                                log.debug("Recipient {} not found or inactive. Message from {} stored for offline delivery", 
                                    clientMessage.data.recipientId, currentUserId)

                                // Store message for offline delivery
                                val offlineMessage = ChatUtils.storeOfflineMessage(
                                    senderId = currentUserId,
                                    recipientId = clientMessage.data.recipientId,
                                    senderName = clientMessage.data.senderName,
                                    encryptedPayload = clientMessage.data.encryptedPayload ?: "",
                                    offlineMessageDao = offlineMessageDao
                                )

                                if (offlineMessage != null) {
                                    log.info("Message stored for offline delivery to userId={}", clientMessage.data.recipientId)
                                    
                                    // Send SENT status to sender (not yet delivered)
                                    ChatUtils.sendMessageStatusUpdate(
                                        messageId = offlineMessage.id,
                                        status = MessageStatus.SENT,
                                        session = currentConnection.session,
                                        serializer = socketSerializer,
                                        log = log
                                    )
                                    
                                    // Send push notification to offline recipient
                                    ChatUtils.sendOfflineMessageNotification(
                                        senderId = currentUserId,
                                        senderName = clientMessage.data.senderName,
                                        recipientId = clientMessage.data.recipientId,
                                        messageId = offlineMessage.id,
                                        pushNotificationService = pushNotificationService,
                                        scope = cleanupScope,
                                        log = log
                                    )
                                    
                                    ChatUtils.sendMessage(
                                        session = currentConnection.session,
                                        serializer = socketSerializer,
                                        message = ErrorMessage("Recipient ${clientMessage.data.recipientId} " +
                                                "is offline. Message will be delivered when they come online.")
                                    )
                                } else {
                                    log.error("Failed to store offline message for userId={}", clientMessage.data.recipientId)
                                    ChatUtils.sendMessage(
                                        session = currentConnection.session,
                                        serializer = socketSerializer,
                                        message = ErrorMessage("Recipient ${clientMessage.data.recipientId} " +
                                                "is offline and message storage failed.")
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
                    ChatUtils.cleanupUserState(
                        userId = userId,
                        conversationState = conversationState,
                        conversationMessageCounts = conversationMessageCounts,
                        log = log
                    )
                }
                log.info("Connection ID {} terminated", currentConnection.id)
                // No need to explicitly close session here if it's already closed by client or an error
            }
        }
    }

}