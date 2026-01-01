package org.barter.features.notifications.model

import kotlinx.serialization.Serializable
import org.barter.features.postings.model.InstantSerializer
import java.time.Instant

/**
 * User's notification contact information
 */
@Serializable
data class UserNotificationContacts(
    val userId: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val pushTokens: List<PushTokenInfo> = emptyList(),
    val notificationsEnabled: Boolean = true,
    val quietHoursStart: Int? = null,
    val quietHoursEnd: Int? = null
)

@Serializable
data class PushTokenInfo(
    val token: String,
    val platform: String, // ANDROID, IOS, WEB
    val deviceId: String? = null,
    val isActive: Boolean = true,
    @Serializable(with = InstantSerializer::class)
    val addedAt: Instant = Instant.now()
)

/**
 * Notification preferences for a specific attribute
 */
@Serializable
data class AttributeNotificationPreference(
    val id: String,
    val userId: String,
    val attributeId: String,
    val notificationsEnabled: Boolean = true,
    val notificationFrequency: NotificationFrequency = NotificationFrequency.INSTANT,
    val minMatchScore: Double = 0.7,
    val notifyOnNewPostings: Boolean = true,
    val notifyOnNewUsers: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant
)

/**
 * Notification preferences for a specific posting
 */
@Serializable
data class PostingNotificationPreference(
    val postingId: String,
    val notificationsEnabled: Boolean = true,
    val notificationFrequency: NotificationFrequency = NotificationFrequency.INSTANT,
    val minMatchScore: Double = 0.7,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant
)

@Serializable
enum class NotificationFrequency {
    INSTANT,  // Notify immediately
    DAILY,    // Daily digest
    WEEKLY,   // Weekly summary
    MANUAL    // No automatic notifications
}

/**
 * Match history entry
 */
@Serializable
data class MatchHistoryEntry(
    val id: String,
    val userId: String,
    val matchType: MatchType,
    val sourceType: SourceType,
    val sourceId: String,
    val targetType: TargetType,
    val targetId: String,
    val matchScore: Double,
    val matchReason: String? = null,
    val notificationSent: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val notificationSentAt: Instant? = null,
    val viewed: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val viewedAt: Instant? = null,
    val dismissed: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val dismissedAt: Instant? = null,
    @Serializable(with = InstantSerializer::class)
    val matchedAt: Instant
)

@Serializable
enum class MatchType {
    POSTING_MATCH,    // New posting matches my interests
    ATTRIBUTE_MATCH,  // New user attribute matches
    USER_MATCH        // New user profile matches
}

@Serializable
enum class SourceType {
    ATTRIBUTE,  // User's attribute caused the match
    POSTING     // User's posting caused the match
}

@Serializable
enum class TargetType {
    POSTING,  // Matched against a posting
    USER      // Matched against a user profile
}

/**
 * Request DTOs
 */

@Serializable
data class CreateAttributeNotificationPreferenceRequest(
    val attributeId: String,
    val notificationsEnabled: Boolean = true,
    val notificationFrequency: NotificationFrequency = NotificationFrequency.INSTANT,
    val minMatchScore: Double = 0.7,
    val notifyOnNewPostings: Boolean = true,
    val notifyOnNewUsers: Boolean = false
)

@Serializable
data class UpdateAttributeNotificationPreferenceRequest(
    val notificationsEnabled: Boolean? = null,
    val notificationFrequency: NotificationFrequency? = null,
    val minMatchScore: Double? = null,
    val notifyOnNewPostings: Boolean? = null,
    val notifyOnNewUsers: Boolean? = null
)

@Serializable
data class UpdatePostingNotificationPreferenceRequest(
    val notificationsEnabled: Boolean? = null,
    val notificationFrequency: NotificationFrequency? = null,
    val minMatchScore: Double? = null
)

@Serializable
data class UpdateUserNotificationContactsRequest(
    val email: String? = null,
    val notificationsEnabled: Boolean? = null,
    val quietHoursStart: Int? = null,
    val quietHoursEnd: Int? = null
)

@Serializable
data class AddPushTokenRequest(
    val token: String,
    val platform: String,
    val deviceId: String? = null
)

/**
 * Response DTOs
 */

@Serializable
data class NotificationPreferencesResponse(
    val success: Boolean,
    val message: String? = null
)

@Serializable
data class MatchHistoryResponse(
    val matches: List<MatchHistoryEntry>,
    val totalCount: Int,
    val unviewedCount: Int
)

/**
 * Enriched match history with posting/user details
 */
@Serializable
data class EnrichedMatchHistoryEntry(
    val match: MatchHistoryEntry,
    val postingUserId: String? = null,      // User ID of the posting owner
    val postingTitle: String? = null,       // Title of the matched posting
    val postingDescription: String? = null, // Description snippet
    val postingImageUrl: String? = null,    // First image if available
    val targetUserId: String? = null        // For USER_MATCH type
)

@Serializable
data class EnrichedMatchHistoryResponse(
    val matches: List<EnrichedMatchHistoryEntry>,
    val totalCount: Int,
    val unviewedCount: Int
)

@Serializable
data class BatchAttributePreferencesResponse(
    val success: Boolean,
    val created: Int,
    val skipped: Int,
    val preferences: List<AttributeNotificationPreference>
)

@Serializable
data class AttributePreferenceResponse(
    val preference: AttributeNotificationPreference
)

@Serializable
data class AttributePreferencesListResponse(
    val preferences: List<AttributeNotificationPreference>,
    val totalCount: Int
)

@Serializable
data class PostingPreferenceResponse(
    val preference: PostingNotificationPreference
)

@Serializable
data class UserContactsResponse(
    val contacts: UserNotificationContacts
)
