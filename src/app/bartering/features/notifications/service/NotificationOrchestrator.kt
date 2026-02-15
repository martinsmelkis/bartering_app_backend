package app.bartering.features.notifications.service

import app.bartering.features.notifications.model.*
import app.bartering.features.notifications.dao.NotificationPreferencesDao
import app.bartering.features.chat.manager.ConnectionManager
import app.bartering.features.chat.model.MatchNotificationMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * High-level notification orchestrator
 * 
 * Coordinates between email, push, and WebSocket services based on user preferences.
 * Handles notification routing, user preferences, and delivery tracking.
 */
class NotificationOrchestrator(
    private val emailService: EmailService?,
    private val pushService: PushNotificationService,
    private val preferencesDao: NotificationPreferencesDao,
    private val connectionManager: ConnectionManager
) {
    private val log = LoggerFactory.getLogger(this::class.java)
    
    /**
     * Send notification via all enabled channels for the user
     */
    suspend fun sendNotification(
        userId: String,
        notification: NotificationData,
        emailTemplate: EmailNotification? = null,
        pushNotification: PushNotification? = null
    ): Map<String, NotificationResult> {
        val results = mutableMapOf<String, NotificationResult>()

        log.debug("Sending notification to userId={}", userId)
        // Get user contacts
        val contacts = preferencesDao.getUserContacts(userId)
        
        // If no contacts configured, use WebSocket as fallback
        if (contacts == null) {
            log.debug("No contacts configured for user {}, attempting WebSocket fallback", userId)
            return sendWebSocketNotification(userId, notification)
        }
        
        // Check if notifications are globally enabled
        if (!contacts.notificationsEnabled) {
            return results
        }
        
        // Send email if available and verified
        if (emailService != null && contacts.email != null && contacts.emailVerified) {
            val email = emailTemplate ?: buildDefaultEmail(
                to = contacts.email,
                notification = notification
            )
            
            try {
                results["email"] = emailService.sendEmail(email)
            } catch (e: Exception) {
                log.error("Failed to send email to {}", contacts.email, e)
                results["email"] = NotificationResult(
                    success = false,
                    errorMessage = e.message
                )
            }
        }
        
        // Send push if tokens available
        if (contacts.pushTokens.isNotEmpty()) {
            val activeTokens = contacts.pushTokens
                .filter { it.isActive }
                .map { it.token }
            
            if (activeTokens.isNotEmpty()) {
                val push = pushNotification ?: PushNotification(
                    tokens = activeTokens,
                    notification = notification
                )
                
                try {
                    results["push"] = pushService.sendPushNotification(push)
                } catch (e: Exception) {
                    log.error("Failed to send push notification", e)
                    results["push"] = NotificationResult(
                        success = false,
                        errorMessage = e.message
                    )
                }
            }
        }
        
        return results
    }
    
    /**
     * Send notification via WebSocket as a fallback when no contacts are configured
     */
    private suspend fun sendWebSocketNotification(
        userId: String,
        notification: NotificationData
    ): Map<String, NotificationResult> {
        val results = mutableMapOf<String, NotificationResult>()
        
        if (connectionManager.isConnected(userId)) {
            try {
                val wsNotification = MatchNotificationMessage(
                    matchId = notification.data["matchId"] ?: "",
                    title = notification.title,
                    body = notification.body,
                    matchType = notification.data["type"] ?: "match",
                    matchScore = notification.data["matchScore"]?.toDoubleOrNull(),
                    postingId = notification.data["postingId"],
                    postingUserId = notification.data["postingUserId"],
                    postingTitle = notification.data["postingTitle"],
                    postingImageUrl = notification.data["postingImageUrl"]
                )
                
                val wsMessage = Json.encodeToString(wsNotification)
                val sentCount = connectionManager.broadcastToAllConnections(userId, wsMessage)
                
                log.info("Sent WebSocket notification (fallback) to {} connection(s) for user {}", sentCount, userId)
                results["websocket"] = NotificationResult(
                    success = sentCount > 0,
                    errorMessage = if (sentCount == 0) "No active connections" else null
                )
            } catch (e: Exception) {
                log.error("Failed to send WebSocket notification", e)
                results["websocket"] = NotificationResult(
                    success = false,
                    errorMessage = e.message
                )
            }
        } else {
            log.warn("User {} has no contacts configured and is not connected via WebSocket - notification dropped", userId)
        }
        
        return results
    }
    
    private fun buildDefaultEmail(
        to: String,
        notification: NotificationData
    ): EmailNotification {
        return EmailNotification(
            to = listOf(to),
            from = "info@bartering.app",
            fromName = "Bartering App",
            subject = notification.title,
            htmlBody = buildHtmlEmail(notification),
            textBody = notification.body
        )
    }
    
    private fun buildHtmlEmail(notification: NotificationData): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                    .header { background: #4CAF50; color: white; padding: 20px; text-align: center; }
                    .content { padding: 20px; background: #f9f9f9; }
                    .button { 
                        display: inline-block; 
                        padding: 12px 24px; 
                        background: #4CAF50; 
                        color: white; 
                        text-decoration: none; 
                        border-radius: 5px;
                        margin-top: 15px;
                    }
                    .footer { text-align: center; padding: 20px; color: #666; font-size: 12px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>${notification.title}</h1>
                    </div>
                    <div class="content">
                        ${if (notification.imageUrl != null) 
                            """<img src="${notification.imageUrl}" alt="Notification" style="max-width: 100%; height: auto;">""" 
                            else ""}
                        <p>${notification.body}</p>
                        ${if (notification.actionUrl != null)
                            """<a href="${notification.actionUrl}" class="button">View Details</a>"""
                            else ""}
                    </div>
                    <div class="footer">
                        <p>© 2026 Bartering App. All rights reserved.</p>
                        <p><a href="#">Unsubscribe</a> | <a href="#">Preferences</a></p>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}
