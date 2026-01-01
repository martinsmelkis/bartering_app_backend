package org.barter.features.chat.routes

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
import org.barter.features.chat.cache.PublicKeyCache
import org.barter.features.encryptedfiles.dao.EncryptedFileDaoImpl
import org.barter.features.chat.dao.OfflineMessageDaoImpl
import org.barter.features.chat.manager.ConnectionManager
import org.barter.features.chat.model.*
import org.barter.features.encryptedfiles.tasks.FileCleanupTask
import org.barter.features.chat.tasks.MessageCleanupTask
import org.barter.features.profile.dao.UserProfileDaoImpl
import org.barter.features.notifications.service.PushNotificationService
import org.barter.features.notifications.model.PushNotification
import org.barter.features.notifications.model.NotificationPriority
import org.barter.features.notifications.utils.NotificationDataBuilder
import org.barter.localization.Localization
import org.barter.utils.CryptoUtils
import org.koin.java.KoinJavaComponent.inject
import kotlinx.coroutines.launch
import java.security.Signature
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

fun Application.chatRoutes(connectionManager: ConnectionManager) {
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

    // Background task for cleaning up old delivered messages
    val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val messageCleanupTask =
        MessageCleanupTask(offlineMessageDao, intervalHours = 24, retentionDays = 7)
    messageCleanupTask.start(cleanupScope)

    // Background task for cleaning up expired/downloaded files
    val fileCleanupTask = FileCleanupTask(encryptedFileDao, intervalHours = 1)
    fileCleanupTask.start(cleanupScope)

    val socketSerializer: KSerializer<SocketMessage> = SocketMessage.serializer()

    routing {
        webSocket("/chat") { // The WebSocket endpoint
            val currentConnection = ChatConnection(this)
            println("New client connected! Connection ID: ${currentConnection.id}")
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
                            println("@@@@@@@@@@@@ ws try to decode auth request: $text")
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
                                println("Error fetching public key for ${authRequest.userId}: ${e.message}")
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
                                println("Public key mismatch for ${authRequest.userId}")
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
                                    println("Invalid signature for ${authRequest.userId}")
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
                                println("Signature verification error for ${authRequest.userId}: ${e.message}")
                                e.printStackTrace()
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

                            // 5. Authentication successful!
                            currentConnection.userId = authRequest.userId
                            currentConnection.userPublicKey = authRequest.publicKey

                            val isNewConnection = connectionManager.getConnection(authRequest.userId) == null
                            println("@@@@@@@@@ isNewConnection: $isNewConnection")
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
                            println("User ${authRequest.userId} with peer ${authRequest.peerUserId} " +
                                    "authenticated for connection ${currentConnection.id}.")

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
                            println("Authentication error: ${e.localizedMessage}")
                            outgoing.send(Frame.Text(Json.encodeToString<SocketMessage>(socketSerializer,
                                ErrorMessage("Invalid auth format: ${e.localizedMessage}"))))
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid auth format"))
                            return@webSocket
                        }
                    }
                }

                if (!isAuthenticated || currentConnection.userId == null) {
                    println("Client failed to authenticate. Connection ID: ${currentConnection.id}")
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
                    return@webSocket
                }

                // 2. Messaging Phase
                val currentUserId = currentConnection.userId!!
                println("User $currentUserId (${currentConnection.id}) entered messaging phase.")

                // Deliver any pending offline messages to the newly connected user
                val pendingMessages =
                    offlineMessageDao.getPendingMessages(currentUserId)
                if (pendingMessages.isNotEmpty()) {
                    println("Delivering ${pendingMessages.size} offline messages to ${currentUserId}")
                    pendingMessages.forEach { offlineMsg ->
                        try {
                            val serverMessage = ServerChatMessage(
                                senderId = offlineMsg.senderId,
                                text = offlineMsg.encryptedPayload,
                                timestamp = offlineMsg.timestamp,
                                recipientId = currentUserId,
                                serverMessageId = offlineMsg.id
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
                        } catch (e: Exception) {
                            println("Failed to deliver offline message ${offlineMsg.id}: ${e.message}")
                        }
                    }
                }

                // Notify user about pending encrypted files
                val pendingFiles = encryptedFileDao.getPendingFiles(currentUserId)
                if (pendingFiles.isNotEmpty()) {
                    println("Notifying ${currentUserId} about ${pendingFiles.size} pending files")
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
                            println("Failed to send file notification ${fileDto.id}: ${e.message}")
                        }
                    }
                }

                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val receivedText = frame.readText()
                        println("Received from $currentUserId: $receivedText")
                        try {
                            val clientMessage = Json.decodeFromString<ClientChatMessage>(receivedText)

                            println("@@@@@@@@@ clientMessage recipient: ${clientMessage.data.recipientId}")
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
                                        serverMessageId = UUID.randomUUID().toString()
                                    )
                                    println("Relaying message from $currentUserId to ${clientMessage.data.recipientId}")
                                    recipientConnection.session.send(
                                        Frame.Text(
                                            Json.encodeToString(
                                                socketSerializer,
                                                serverMessage
                                            )
                                        )
                                    )
                                }
                            } else {
                                println("Recipient ${clientMessage.data.recipientId} not found or inactive. " +
                                        "Message from $currentUserId not delivered.")

                                // Store message for offline delivery in database
                                val offlineMessage = OfflineMessageDto(
                                    id = UUID.randomUUID().toString(),
                                    senderId = currentUserId,
                                    recipientId = clientMessage.data.recipientId,
                                    encryptedPayload = clientMessage.data.encryptedPayload ?: "",
                                    timestamp = System.currentTimeMillis()
                                )

                                val stored = offlineMessageDao.storeOfflineMessage(offlineMessage)
                                if (stored) {
                                    println("Message stored for offline delivery to ${clientMessage.data.recipientId}")
                                    
                                    // Send push notification to offline recipient
                                    cleanupScope.launch {
                                        try {
                                            val senderName = usersDao.getProfile(currentUserId)?.name ?: "Someone"
                                            
                                            val notificationData = NotificationDataBuilder.newMessage(
                                                senderId = currentUserId,
                                                senderName = senderName,
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
                                            println("✅ Push notification sent to offline user ${clientMessage.data.recipientId}")
                                        } catch (e: Exception) {
                                            println("⚠️ Failed to send push notification: ${e.message}")
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
                                    println("Failed to store offline message for ${clientMessage.data.recipientId}")
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
                            e.printStackTrace()
                            println("Error processing message from $currentUserId: ${e.localizedMessage}")
                            currentConnection.session.send(Frame.Text(
                                Json.encodeToString<SocketMessage>(socketSerializer,
                                ErrorMessage("Error processing message: ${e.localizedMessage}"))))
                        }
                    }
                }
            } catch (_: ClosedReceiveChannelException) {
                println("Client ${currentConnection.userId ?: "ID: ${currentConnection.id}"} disconnected (channel closed).")
            } catch (e: Throwable) {
                println("Error for ${currentConnection.userId ?: "ID: ${currentConnection.id}"}: ${e.localizedMessage}")
                e.printStackTrace()
            } finally {
                currentConnection.userId?.let { userId ->
                    // Remove connection from manager (only if it's the current connection)
                    connectionManager.removeConnection(userId, currentConnection.id)
                }
                println("Connection ID ${currentConnection.id} terminated.")
                // No need to explicitly close session here if it's already closed by client or an error
            }
        }
    }

}