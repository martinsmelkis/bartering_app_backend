package app.bartering.features.notifications.service.impl

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.bartering.features.notifications.dao.NotificationPreferencesDao
import app.bartering.features.notifications.model.NotificationResult
import app.bartering.features.notifications.model.PushNotification
import app.bartering.features.notifications.model.PushPlatform
import app.bartering.features.notifications.service.PushNotificationService
import org.koin.java.KoinJavaComponent.inject
import java.io.FileInputStream
import java.nio.file.Paths

/**
 * Firebase Cloud Messaging (FCM) push notification implementation
 * 
 * Configuration:
 * - FIREBASE_CREDENTIALS_PATH: Path to Firebase Admin SDK JSON file (optional, defaults to root)
 * - FIREBASE_CREDENTIALS_FILE: Firebase Admin SDK JSON filename
 * 
 * Features:
 * - Cross-platform (Android, iOS, Web)
 * - Topic messaging
 * - Device group messaging
 * - Analytics integration
 * - Token validation and cleanup
 * 
 * SDK: com.google.firebase:firebase-admin:9.3.0
 */
class FirebasePushService : PushNotificationService {
    
    private val preferencesDao: NotificationPreferencesDao by inject(NotificationPreferencesDao::class.java)
    private var isInitialized = false
    
    init {
        isInitialized = initializeFirebase()
    }
    
    /**
     * Initialize Firebase Admin SDK with service account credentials
     * Returns true if successful, false otherwise
     */
    private fun initializeFirebase(): Boolean {
        try {
            // Check if Firebase is already initialized
            if (FirebaseApp.getApps().isEmpty()) {
                val credentialsPath = System.getenv("FIREBASE_CREDENTIALS_PATH") ?: "."
                val credentialsFile = System.getenv("FIREBASE_CREDENTIALS_FILE") 
                    ?: "barter-app-backend-dev-firebase-adminsdk-fbsvc-393197c88a.json"
                
                val serviceAccountPath = Paths.get(credentialsPath, credentialsFile).toString()
                val serviceAccountFile = java.io.File(serviceAccountPath)
                
                if (!serviceAccountFile.exists()) {
                    println("⚠️ Firebase credentials file not found at: $serviceAccountPath")
                    println("⚠️ Push notifications will be disabled. Place the credentials file or set environment variables:")
                    println("   - FIREBASE_CREDENTIALS_PATH (default: .)")
                    println("   - FIREBASE_CREDENTIALS_FILE (default: barter-app-backend-dev-firebase-adminsdk-fbsvc-393197c88a.json)")
                    return false
                }
                
                val serviceAccount = FileInputStream(serviceAccountFile)
                
                val options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build()
                
                FirebaseApp.initializeApp(options)
                println("✅ Firebase Admin SDK initialized successfully from: $serviceAccountPath")
                return true
            } else {
                println("✅ Firebase Admin SDK already initialized")
                return true
            }
        } catch (e: Exception) {
            println("❌ Failed to initialize Firebase Admin SDK: ${e.message}")
            println("⚠️ Push notifications will be disabled")
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * Check if Firebase is initialized before performing operations
     */
    private fun checkInitialized(): Boolean {
        if (!isInitialized) {
            println("⚠️ Firebase not initialized - operation skipped")
        }
        return isInitialized
    }
    
    override suspend fun sendPushNotification(notification: PushNotification): NotificationResult = withContext(Dispatchers.IO) {
        if (!checkInitialized()) {
            return@withContext NotificationResult(
                success = false,
                errorMessage = "Firebase not initialized"
            )
        }
        
        try {
            if (notification.tokens.isEmpty()) {
                return@withContext NotificationResult(
                    success = false,
                    errorMessage = "No tokens provided"
                )
            }
            
            // If multiple tokens, use batch send
            if (notification.tokens.size > 1) {
                return@withContext sendBatch(notification)
            }
            
            // Single token send
            val message = buildMessage(notification.tokens.first(), notification)
            val response = FirebaseMessaging.getInstance().send(message)
            
            NotificationResult(
                success = true,
                messageId = response,
                metadata = mapOf("platform" to "fcm", "tokens" to notification.tokens.size.toString())
            )
        } catch (e: FirebaseMessagingException) {
            handleFirebaseException(e, notification.tokens)
        } catch (e: Exception) {
            NotificationResult(
                success = false,
                errorMessage = "Failed to send push notification: ${e.message}",
                failedRecipients = notification.tokens
            )
        }
    }
    
    override suspend fun sendBulkPushNotifications(
        notifications: List<PushNotification>
    ): List<NotificationResult> = withContext(Dispatchers.IO) {
        notifications.map { notification ->
            sendPushNotification(notification)
        }
    }
    
    override suspend fun sendToTopic(
        topic: String,
        notification: PushNotification
    ): NotificationResult = withContext(Dispatchers.IO) {
        if (!checkInitialized()) {
            return@withContext NotificationResult(
                success = false,
                errorMessage = "Firebase not initialized"
            )
        }
        
        try {
            // Add click_action to data payload for Flutter
            val dataWithClickAction = notification.notification.data.toMutableMap()
            dataWithClickAction["click_action"] = "FLUTTER_NOTIFICATION_CLICK"
            
            val message = Message.builder()
                .setTopic(topic)
                .setNotification(buildNotification(notification))
                .putAllData(dataWithClickAction)
                .setAndroidConfig(buildAndroidConfig(notification))
                .setApnsConfig(buildApnsConfig(notification))
                .build()
            
            val response = FirebaseMessaging.getInstance().send(message)
            
            NotificationResult(
                success = true,
                messageId = response,
                metadata = mapOf("platform" to "fcm", "topic" to topic)
            )
        } catch (e: FirebaseMessagingException) {
            handleFirebaseException(e, emptyList())
        } catch (e: Exception) {
            NotificationResult(
                success = false,
                errorMessage = "Failed to send to topic: ${e.message}"
            )
        }
    }
    
    override suspend fun sendToUser(
        userId: String,
        notification: PushNotification
    ): NotificationResult = withContext(Dispatchers.IO) {
        if (!checkInitialized()) {
            return@withContext NotificationResult(
                success = false,
                errorMessage = "Firebase not initialized"
            )
        }
        
        try {
            // Get user's push tokens from database
            val contacts = preferencesDao.getUserContacts(userId)
            if (contacts == null || contacts.pushTokens.isEmpty()) {
                return@withContext NotificationResult(
                    success = false,
                    errorMessage = "No push tokens found for user: $userId"
                )
            }
            
            val activeTokens = contacts.pushTokens
                .filter { it.isActive }
                .map { it.token }
            
            if (activeTokens.isEmpty()) {
                return@withContext NotificationResult(
                    success = false,
                    errorMessage = "No active push tokens for user: $userId"
                )
            }
            
            // Send to all user's active tokens
            val updatedNotification = notification.copy(tokens = activeTokens)
            sendPushNotification(updatedNotification)
        } catch (e: Exception) {
            NotificationResult(
                success = false,
                errorMessage = "Failed to send to user: ${e.message}"
            )
        }
    }
    
    override suspend fun subscribeToTopic(tokens: List<String>, topic: String): NotificationResult = withContext(Dispatchers.IO) {
        if (!checkInitialized()) {
            return@withContext NotificationResult(
                success = false,
                errorMessage = "Firebase not initialized"
            )
        }
        
        try {
            val response = FirebaseMessaging.getInstance().subscribeToTopic(tokens, topic)
            
            val failedTokens = mutableListOf<String>()
            response.errors.forEach { error ->
                if (error.index < tokens.size) {
                    failedTokens.add(tokens[error.index])
                }
            }
            
            NotificationResult(
                success = response.successCount > 0,
                messageId = "subscribed-$topic",
                failedRecipients = failedTokens,
                metadata = mapOf(
                    "successCount" to response.successCount.toString(),
                    "failureCount" to response.failureCount.toString()
                )
            )
        } catch (e: Exception) {
            NotificationResult(
                success = false,
                errorMessage = "Failed to subscribe to topic: ${e.message}",
                failedRecipients = tokens
            )
        }
    }
    
    override suspend fun unsubscribeFromTopic(tokens: List<String>, topic: String): NotificationResult = withContext(Dispatchers.IO) {
        if (!checkInitialized()) {
            return@withContext NotificationResult(
                success = false,
                errorMessage = "Firebase not initialized"
            )
        }
        
        try {
            val response = FirebaseMessaging.getInstance().unsubscribeFromTopic(tokens, topic)
            
            val failedTokens = mutableListOf<String>()
            response.errors.forEach { error ->
                if (error.index < tokens.size) {
                    failedTokens.add(tokens[error.index])
                }
            }
            
            NotificationResult(
                success = response.successCount > 0,
                messageId = "unsubscribed-$topic",
                failedRecipients = failedTokens,
                metadata = mapOf(
                    "successCount" to response.successCount.toString(),
                    "failureCount" to response.failureCount.toString()
                )
            )
        } catch (e: Exception) {
            NotificationResult(
                success = false,
                errorMessage = "Failed to unsubscribe from topic: ${e.message}",
                failedRecipients = tokens
            )
        }
    }
    
    override suspend fun validateToken(token: String, platform: PushPlatform): Boolean = withContext(Dispatchers.IO) {
        if (!checkInitialized()) return@withContext false
        
        try {
            // Try to send a silent test notification
            val message = Message.builder()
                .setToken(token)
                .putData("test", "true")
                .setAndroidConfig(
                    AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .build()
                )
                .setApnsConfig(
                    ApnsConfig.builder()
                        .setAps(Aps.builder().setContentAvailable(true).build())
                        .build()
                )
                .build()
            
            FirebaseMessaging.getInstance().send(message, true) // dry run
            true
        } catch (e: FirebaseMessagingException) {
            // Token is invalid if we get specific error codes
            when (e.messagingErrorCode) {
                MessagingErrorCode.INVALID_ARGUMENT,
                MessagingErrorCode.UNREGISTERED -> false
                else -> true // Other errors might be temporary
            }
        } catch (e: Exception) {
            println("❌ Token validation error: ${e.message}")
            false
        }
    }
    
    override suspend fun cleanupInvalidTokens(userId: String): Int = withContext(Dispatchers.IO) {
        if (!checkInitialized()) return@withContext 0
        
        try {
            val contacts = preferencesDao.getUserContacts(userId) ?: return@withContext 0
            
            var removedCount = 0
            contacts.pushTokens.forEach { tokenInfo ->
                val isValid = validateToken(tokenInfo.token, PushPlatform.valueOf(tokenInfo.platform))
                if (!isValid) {
                    preferencesDao.removePushToken(userId, tokenInfo.token)
                    removedCount++
                }
            }
            
            removedCount
        } catch (e: Exception) {
            println("❌ Failed to cleanup invalid tokens for user $userId: ${e.message}")
            0
        }
    }
    
    override suspend fun healthCheck(): Boolean = withContext(Dispatchers.IO) {
        if (!checkInitialized()) return@withContext false
        
        try {
            // Check if Firebase is initialized and accessible
            FirebaseApp.getInstance()
            FirebaseMessaging.getInstance()
            true
        } catch (e: Exception) {
            println("❌ Firebase health check failed: ${e.message}")
            false
        }
    }
    
    // Private helper methods
    
    /**
     * Build Firebase message for a single token
     */
    private fun buildMessage(token: String, notification: PushNotification): Message {
        // Add click_action to data payload for Flutter
        val dataWithClickAction = notification.notification.data.toMutableMap()
        dataWithClickAction["click_action"] = "FLUTTER_NOTIFICATION_CLICK"
        
        return Message.builder()
            .setToken(token)
            .setNotification(buildNotification(notification))
            .putAllData(dataWithClickAction)
            .setAndroidConfig(buildAndroidConfig(notification))
            .setApnsConfig(buildApnsConfig(notification))
            .build()
    }
    
    /**
     * Build FCM notification object
     */
    private fun buildNotification(notification: PushNotification): Notification {
        val builder = Notification.builder()
            .setTitle(notification.notification.title)
            .setBody(notification.notification.body)
        
        notification.notification.imageUrl?.let { builder.setImage(it) }
        
        return builder.build()
    }
    
    /**
     * Build Android-specific configuration
     */
    private fun buildAndroidConfig(notification: PushNotification): AndroidConfig {
        val priority = when (notification.priority) {
            app.bartering.features.notifications.model.NotificationPriority.HIGH -> AndroidConfig.Priority.HIGH
            app.bartering.features.notifications.model.NotificationPriority.NORMAL -> AndroidConfig.Priority.NORMAL
            app.bartering.features.notifications.model.NotificationPriority.LOW -> AndroidConfig.Priority.NORMAL
        }
        
        val androidNotification = AndroidNotification.builder()
            .setSound(notification.sound ?: "default")
            .setChannelId(notification.channelId ?: "default")
            .setClickAction("FLUTTER_NOTIFICATION_CLICK") // Required for Flutter tap handling
        
        notification.notification.imageUrl?.let { androidNotification.setImage(it) }
        
        val builder = AndroidConfig.builder()
            .setPriority(priority)
            .setNotification(androidNotification.build())
        
        notification.ttl?.let { builder.setTtl(it.toLong() * 1000) } // Convert to milliseconds
        notification.collapseKey?.let { builder.setCollapseKey(it) }
        
        return builder.build()
    }
    
    /**
     * Build iOS-specific configuration (APNs)
     */
    private fun buildApnsConfig(notification: PushNotification): ApnsConfig {
        val apsBuilder = Aps.builder()
            .setSound(notification.sound ?: "default")
        
        notification.badge?.let { apsBuilder.setBadge(it) }
        notification.category?.let { apsBuilder.setCategory(it) }
        
        if (notification.mutableContent) {
            apsBuilder.setMutableContent(true)
        }
        
        if (notification.contentAvailable) {
            apsBuilder.setContentAvailable(true)
        }
        
        return ApnsConfig.builder()
            .setAps(apsBuilder.build())
            .build()
    }
    
    /**
     * Send to multiple tokens using batch API
     */
    private suspend fun sendBatch(notification: PushNotification): NotificationResult = withContext(Dispatchers.IO) {
        try {
            // FCM allows max 500 tokens per batch
            val batchSize = 500
            val batches = notification.tokens.chunked(batchSize)
            
            var totalSuccess = 0
            var totalFailed = 0
            val allFailedTokens = mutableListOf<String>()
            
            batches.forEach { batch ->
                val messages = batch.map { token ->
                    buildMessage(token, notification)
                }
                
                val response = FirebaseMessaging.getInstance().sendEach(messages)
                totalSuccess += response.successCount
                totalFailed += response.failureCount
                
                // Collect failed tokens
                response.responses.forEachIndexed { index, sendResponse ->
                    if (!sendResponse.isSuccessful && index < batch.size) {
                        allFailedTokens.add(batch[index])
                    }
                }
            }
            
            NotificationResult(
                success = totalSuccess > 0,
                messageId = "batch-${System.currentTimeMillis()}",
                failedRecipients = allFailedTokens,
                metadata = mapOf(
                    "totalSuccess" to totalSuccess.toString(),
                    "totalFailed" to totalFailed.toString(),
                    "batches" to batches.size.toString()
                )
            )
        } catch (e: Exception) {
            NotificationResult(
                success = false,
                errorMessage = "Batch send failed: ${e.message}",
                failedRecipients = notification.tokens
            )
        }
    }
    
    /**
     * Handle Firebase-specific exceptions
     */
    private fun handleFirebaseException(
        e: FirebaseMessagingException,
        tokens: List<String>
    ): NotificationResult {
        val errorMessage = when (e.messagingErrorCode) {
            MessagingErrorCode.INVALID_ARGUMENT -> "Invalid message parameters"
            MessagingErrorCode.UNREGISTERED -> "Token is unregistered or invalid"
            MessagingErrorCode.SENDER_ID_MISMATCH -> "Token does not match sender ID"
            MessagingErrorCode.QUOTA_EXCEEDED -> "Message quota exceeded"
            MessagingErrorCode.UNAVAILABLE -> "FCM service unavailable"
            MessagingErrorCode.INTERNAL -> "Internal FCM error"
            MessagingErrorCode.THIRD_PARTY_AUTH_ERROR -> "APNs certificate or auth key error"
            else -> "FCM error: ${e.message}"
        }
        
        return NotificationResult(
            success = false,
            errorMessage = errorMessage,
            failedRecipients = tokens,
            metadata = mapOf(
                "errorCode" to (e.messagingErrorCode?.name ?: "UNKNOWN"),
                "httpResponse" to (e.httpResponse?.toString() ?: "N/A")
            )
        )
    }
}
