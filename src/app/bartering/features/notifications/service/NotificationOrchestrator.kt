package app.bartering.features.notifications.service

import app.bartering.features.notifications.model.*
import app.bartering.features.notifications.dao.NotificationPreferencesDao
import org.slf4j.LoggerFactory

/**
 * High-level notification orchestrator
 * 
 * Coordinates between email and push services based on user preferences.
 * Handles notification routing, user preferences, and delivery tracking.
 */
class NotificationOrchestrator(
    private val emailService: EmailService?,
    private val pushService: PushNotificationService,
    private val preferencesDao: NotificationPreferencesDao
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
            ?: return results // User not found or no contacts
        
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
    
    private fun buildDefaultEmail(
        to: String,
        notification: NotificationData
    ): EmailNotification {
        return EmailNotification(
            to = listOf(to),
            from = "noreply@barter.app",
            fromName = "Barter App",
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
