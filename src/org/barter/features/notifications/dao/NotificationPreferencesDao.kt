package org.barter.features.notifications.dao

import org.barter.features.notifications.model.*
import java.time.Instant

/**
 * DAO for managing notification preferences and match history
 */
interface NotificationPreferencesDao {
    
    // User Notification Contacts
    suspend fun getUserContacts(userId: String): UserNotificationContacts?
    suspend fun saveUserContacts(contacts: UserNotificationContacts): UserNotificationContacts
    suspend fun updateUserContacts(userId: String, request: UpdateUserNotificationContactsRequest): UserNotificationContacts?
    suspend fun addPushToken(userId: String, tokenInfo: PushTokenInfo): Boolean
    suspend fun removePushToken(userId: String, token: String): Boolean
    
    // Attribute Notification Preferences
    suspend fun getAttributePreference(userId: String, attributeId: String): AttributeNotificationPreference?
    suspend fun getAllAttributePreferences(userId: String): List<AttributeNotificationPreference>
    suspend fun saveAttributePreference(userId: String, request: CreateAttributeNotificationPreferenceRequest): AttributeNotificationPreference
    suspend fun updateAttributePreference(userId: String, attributeId: String, request: UpdateAttributeNotificationPreferenceRequest): AttributeNotificationPreference?
    suspend fun deleteAttributePreference(userId: String, attributeId: String): Boolean
    suspend fun getActiveAttributePreferences(): List<AttributeNotificationPreference> // For matching service
    
    // Posting Notification Preferences
    suspend fun getPostingPreference(postingId: String): PostingNotificationPreference?
    suspend fun savePostingPreference(postingId: String, request: UpdatePostingNotificationPreferenceRequest): PostingNotificationPreference
    suspend fun updatePostingPreference(postingId: String, request: UpdatePostingNotificationPreferenceRequest): PostingNotificationPreference?
    suspend fun deletePostingPreference(postingId: String): Boolean
    suspend fun getActivePostingPreferences(): List<PostingNotificationPreference> // For matching service
    
    // Match History
    suspend fun createMatch(match: MatchHistoryEntry): MatchHistoryEntry
    suspend fun getMatch(matchId: String): MatchHistoryEntry?
    suspend fun getUserMatches(userId: String, unviewedOnly: Boolean = false, limit: Int = 50): List<MatchHistoryEntry>
    suspend fun findExistingMatch(userId: String, sourceType: SourceType, sourceId: String, targetType: TargetType, targetId: String): MatchHistoryEntry?
    suspend fun markMatchViewed(matchId: String): Boolean
    suspend fun markMatchDismissed(matchId: String): Boolean
    suspend fun markMatchNotificationSent(matchId: String): Boolean
    suspend fun getUnnotifiedMatches(userId: String, since: Instant?  = null): List<MatchHistoryEntry>
    suspend fun deleteOldMatches(olderThan: Instant): Int
}
