package app.bartering.features.notifications.service

import app.bartering.features.notifications.model.EmailNotification
import app.bartering.features.notifications.model.NotificationResult

/**
 * Email service interface
 * 
 * Implementations should support popular email providers:
 * - AWS SES
 * - Mailgun
 * - Postmark
 * - SMTP
 */
interface EmailService {
    
    /**
     * Send a single email
     */
    suspend fun sendEmail(email: EmailNotification): NotificationResult
    
    /**
     * Send bulk emails (optimized for batch sending)
     */
    suspend fun sendBulkEmails(emails: List<EmailNotification>): List<NotificationResult>
    
    /**
     * Send email using a template
     */
    suspend fun sendTemplatedEmail(
        to: List<String>,
        templateId: String,
        templateData: Map<String, String>,
        from: String? = null,
        subject: String? = null
    ): NotificationResult
    
    /**
     * Verify email address (send verification email)
     */
    suspend fun sendVerificationEmail(
        email: String,
        verificationToken: String,
        verificationUrl: String
    ): NotificationResult
    
    /**
     * Check if email provider is healthy
     */
    suspend fun healthCheck(): Boolean
}
