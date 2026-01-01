package org.barter.features.notifications.model

import kotlinx.serialization.Serializable

/**
 * Base notification data
 */
@Serializable
data class NotificationData(
    val title: String,
    val body: String,
    val imageUrl: String? = null,
    val actionUrl: String? = null,
    val data: Map<String, String> = emptyMap()
)

/**
 * Email notification data
 */
@Serializable
data class EmailNotification(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val from: String,
    val fromName: String? = null,
    val replyTo: String? = null,
    val subject: String,
    val htmlBody: String? = null,
    val textBody: String? = null,
    val templateId: String? = null,
    val templateData: Map<String, String> = emptyMap(), // Changed from Any to String
    val attachments: List<EmailAttachment> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Email attachment
 */
@Serializable
data class EmailAttachment(
    val filename: String,
    val content: String, // Base64 encoded
    val contentType: String,
    val disposition: AttachmentDisposition = AttachmentDisposition.ATTACHMENT
)

@Serializable
enum class AttachmentDisposition {
    ATTACHMENT,
    INLINE
}

/**
 * Push notification data
 */
@Serializable
data class PushNotification(
    val tokens: List<String>, // FCM tokens, APNs device tokens, etc.
    val notification: NotificationData,
    val platform: PushPlatform? = null,
    val priority: NotificationPriority = NotificationPriority.NORMAL,
    val ttl: Int? = null, // Time to live in seconds
    val collapseKey: String? = null, // For message grouping
    val badge: Int? = null,
    val sound: String? = null,
    val channelId: String? = null, // Android notification channel
    val category: String? = null, // iOS notification category
    val mutableContent: Boolean = false,
    val contentAvailable: Boolean = false,
    val data: Map<String, String> = emptyMap()
)

@Serializable
enum class PushPlatform {
    ANDROID,
    IOS,
    WEB
}

@Serializable
enum class NotificationPriority {
    HIGH,
    NORMAL,
    LOW
}

/**
 * Notification result
 */
@Serializable
data class NotificationResult(
    val success: Boolean,
    val messageId: String? = null,
    val failedRecipients: List<String> = emptyList(),
    val errorMessage: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Batch notification result
 */
@Serializable
data class BatchNotificationResult(
    val totalSent: Int,
    val totalFailed: Int,
    val results: List<NotificationResult>,
    val failureReasons: Map<String, String> = emptyMap()
)

/**
 * Notification template
 */
@Serializable
data class NotificationTemplate(
    val id: String,
    val name: String,
    val type: NotificationType,
    val subject: String? = null,
    val htmlTemplate: String? = null,
    val textTemplate: String? = null,
    val pushTitle: String? = null,
    val pushBody: String? = null,
    val variables: List<String> = emptyList()
)

@Serializable
enum class NotificationType {
    EMAIL,
    PUSH,
    BOTH
}

/**
 * User notification preferences
 */
@Serializable
data class UserNotificationPreferences(
    val userId: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val emailEnabled: Boolean = true,
    val pushTokens: List<PushToken> = emptyList(),
    val pushEnabled: Boolean = true,
    val categories: Map<String, CategoryPreference> = emptyMap()
)

@Serializable
data class PushToken(
    val token: String,
    val platform: PushPlatform,
    val deviceId: String? = null,
    val isActive: Boolean = true
)

@Serializable
data class CategoryPreference(
    val enabled: Boolean,
    val emailEnabled: Boolean = true,
    val pushEnabled: Boolean = true
)

/**
 * Notification categories for user preferences
 */
object NotificationCategory {
    const val WISHLIST_MATCH = "wishlist_match"
    const val NEW_MESSAGE = "new_message"
    const val CONNECTION_REQUEST = "connection_request"
    const val POSTING_COMMENT = "posting_comment"
    const val SYSTEM_UPDATE = "system_update"
    const val MARKETING = "marketing"
}
