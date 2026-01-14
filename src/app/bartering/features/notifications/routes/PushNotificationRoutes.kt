package app.bartering.features.notifications.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import app.bartering.features.notifications.model.*
import app.bartering.features.notifications.service.PushNotificationService
import org.koin.ktor.ext.inject

/**
 * Routes for push notification management and testing
 * 
 * Note: In production, these routes should be protected with admin authentication
 */
fun Application.pushNotificationRoutes() {
    val pushService: PushNotificationService by inject()
    
    routing {
        route("/api/v1/push") {
            
            /**
             * POST /api/v1/push/send
             * Send push notification to specific tokens
             * 
             * Request body:
             * {
             *   "tokens": ["token1", "token2"],
             *   "notification": {
             *     "title": "Test",
             *     "body": "This is a test"
             *   },
             *   "priority": "HIGH",
             *   "sound": "default"
             * }
             */
            post("/send") {
                try {
                    val request = call.receive<PushNotificationRequest>()
                    
                    val notification = PushNotification(
                        tokens = request.tokens,
                        notification = request.notification,
                        priority = request.priority ?: NotificationPriority.NORMAL,
                        sound = request.sound,
                        channelId = request.channelId,
                        badge = request.badge,
                        data = request.data ?: emptyMap()
                    )
                    
                    val result = pushService.sendPushNotification(notification)
                    
                    if (result.success) {
                        call.respond(HttpStatusCode.OK, PushNotificationResponse(
                            success = true,
                            message = "Push notification sent successfully",
                            messageId = result.messageId,
                            metadata = result.metadata
                        ))
                    } else {
                        call.respond(HttpStatusCode.BadRequest, PushNotificationResponse(
                            success = false,
                            message = result.errorMessage ?: "Failed to send push notification",
                            failedTokens = result.failedRecipients
                        ))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                        "error" to "Failed to send push notification",
                        "message" to e.message
                    ))
                }
            }
            
            /**
             * POST /api/v1/push/send-to-user
             * Send push notification to a specific user (looks up tokens)
             * 
             * Request body:
             * {
             *   "userId": "user123",
             *   "notification": {
             *     "title": "Hello",
             *     "body": "You have a new notification"
             *   }
             * }
             */
            post("/send-to-user") {
                try {
                    val request = call.receive<SendToUserRequest>()
                    
                    val notification = PushNotification(
                        tokens = emptyList(), // Will be populated by service
                        notification = request.notification,
                        priority = request.priority ?: NotificationPriority.NORMAL,
                        sound = request.sound,
                        channelId = request.channelId
                    )
                    
                    val result = pushService.sendToUser(request.userId, notification)
                    
                    if (result.success) {
                        call.respond(HttpStatusCode.OK, PushNotificationResponse(
                            success = true,
                            message = "Push notification sent to user",
                            messageId = result.messageId
                        ))
                    } else {
                        call.respond(HttpStatusCode.BadRequest, PushNotificationResponse(
                            success = false,
                            message = result.errorMessage ?: "Failed to send to user"
                        ))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                        "error" to "Failed to send push notification",
                        "message" to e.message
                    ))
                }
            }
            
            /**
             * POST /api/v1/push/topic/subscribe
             * Subscribe tokens to a topic
             * 
             * Request body:
             * {
             *   "tokens": ["token1", "token2"],
             *   "topic": "new_postings"
             * }
             */
            post("/topic/subscribe") {
                try {
                    val request = call.receive<TopicSubscriptionRequest>()
                    val result = pushService.subscribeToTopic(request.tokens, request.topic)
                    
                    call.respond(HttpStatusCode.OK, TopicSubscriptionResponse(
                        success = result.success,
                        message = "Subscribed to topic: ${request.topic}",
                        successCount = result.metadata["successCount"]?.toIntOrNull() ?: 0,
                        failureCount = result.metadata["failureCount"]?.toIntOrNull() ?: 0,
                        failedTokens = result.failedRecipients
                    ))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                        "error" to "Failed to subscribe to topic",
                        "message" to e.message
                    ))
                }
            }
            
            /**
             * POST /api/v1/push/topic/unsubscribe
             * Unsubscribe tokens from a topic
             */
            post("/topic/unsubscribe") {
                try {
                    val request = call.receive<TopicSubscriptionRequest>()
                    val result = pushService.unsubscribeFromTopic(request.tokens, request.topic)
                    
                    call.respond(HttpStatusCode.OK, TopicSubscriptionResponse(
                        success = result.success,
                        message = "Unsubscribed from topic: ${request.topic}",
                        successCount = result.metadata["successCount"]?.toIntOrNull() ?: 0,
                        failureCount = result.metadata["failureCount"]?.toIntOrNull() ?: 0,
                        failedTokens = result.failedRecipients
                    ))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                        "error" to "Failed to unsubscribe from topic",
                        "message" to e.message
                    ))
                }
            }
            
            /**
             * POST /api/v1/push/topic/send
             * Send notification to all subscribers of a topic
             * 
             * Request body:
             * {
             *   "topic": "new_postings",
             *   "notification": {
             *     "title": "New Posting",
             *     "body": "Check out the latest item"
             *   }
             * }
             */
            post("/topic/send") {
                try {
                    val request = call.receive<TopicMessageRequest>()
                    
                    val notification = PushNotification(
                        tokens = emptyList(),
                        notification = request.notification,
                        priority = request.priority ?: NotificationPriority.NORMAL,
                        sound = request.sound
                    )
                    
                    val result = pushService.sendToTopic(request.topic, notification)
                    
                    call.respond(HttpStatusCode.OK, PushNotificationResponse(
                        success = result.success,
                        message = "Notification sent to topic: ${request.topic}",
                        messageId = result.messageId
                    ))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                        "error" to "Failed to send to topic",
                        "message" to e.message
                    ))
                }
            }
            
            /**
             * POST /api/v1/push/validate-token
             * Validate if a push token is valid
             * 
             * Request body:
             * {
             *   "token": "fcm-token-here",
             *   "platform": "ANDROID"
             * }
             */
            post("/validate-token") {
                try {
                    val request = call.receive<ValidateTokenRequest>()
                    val isValid = pushService.validateToken(request.token, request.platform)
                    
                    call.respond(HttpStatusCode.OK, mapOf(
                        "valid" to isValid,
                        "token" to request.token,
                        "platform" to request.platform
                    ))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                        "error" to "Failed to validate token",
                        "message" to e.message
                    ))
                }
            }
            
            /**
             * POST /api/v1/push/cleanup-tokens/{userId}
             * Clean up invalid tokens for a user
             */
            post("/cleanup-tokens/{userId}") {
                try {
                    val userId = call.parameters["userId"] 
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf(
                            "error" to "userId is required"
                        ))
                    
                    val removedCount = pushService.cleanupInvalidTokens(userId)
                    
                    call.respond(HttpStatusCode.OK, mapOf(
                        "success" to true,
                        "userId" to userId,
                        "removedTokens" to removedCount,
                        "message" to "Cleaned up $removedCount invalid token(s)"
                    ))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                        "error" to "Failed to cleanup tokens",
                        "message" to e.message
                    ))
                }
            }
            
            /**
             * GET /api/v1/push/health
             * Check Firebase service health
             */
            get("/health") {
                try {
                    val isHealthy = pushService.healthCheck()
                    
                    if (isHealthy) {
                        call.respond(HttpStatusCode.OK, mapOf(
                            "status" to "healthy",
                            "service" to "Firebase Cloud Messaging",
                            "timestamp" to System.currentTimeMillis()
                        ))
                    } else {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf(
                            "status" to "unhealthy",
                            "service" to "Firebase Cloud Messaging",
                            "timestamp" to System.currentTimeMillis()
                        ))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf(
                        "status" to "error",
                        "message" to e.message
                    ))
                }
            }
        }
    }
}

// Request/Response models

@Serializable
data class PushNotificationRequest(
    val tokens: List<String>,
    val notification: NotificationData,
    val priority: NotificationPriority? = null,
    val sound: String? = null,
    val channelId: String? = null,
    val badge: Int? = null,
    val data: Map<String, String>? = null
)

@Serializable
data class SendToUserRequest(
    val userId: String,
    val notification: NotificationData,
    val priority: NotificationPriority? = null,
    val sound: String? = null,
    val channelId: String? = null
)

@Serializable
data class TopicSubscriptionRequest(
    val tokens: List<String>,
    val topic: String
)

@Serializable
data class TopicMessageRequest(
    val topic: String,
    val notification: NotificationData,
    val priority: NotificationPriority? = null,
    val sound: String? = null
)

@Serializable
data class ValidateTokenRequest(
    val token: String,
    val platform: PushPlatform
)

@Serializable
data class PushNotificationResponse(
    val success: Boolean,
    val message: String,
    val messageId: String? = null,
    val failedTokens: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class TopicSubscriptionResponse(
    val success: Boolean,
    val message: String,
    val successCount: Int,
    val failureCount: Int,
    val failedTokens: List<String> = emptyList()
)
