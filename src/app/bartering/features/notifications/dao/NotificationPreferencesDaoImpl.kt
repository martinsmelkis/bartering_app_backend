package app.bartering.features.notifications.dao

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import app.bartering.features.notifications.db.*
import app.bartering.features.notifications.model.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.util.UUID

/**
 * Implementation of NotificationPreferencesDao using Exposed ORM
 */
class NotificationPreferencesDaoImpl : NotificationPreferencesDao {
    
    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
    
    // User Notification Contacts
    
    override suspend fun getUserContacts(userId: String): UserNotificationContacts? = dbQuery {
        UserNotificationContactsTable
            .selectAll()
            .where { UserNotificationContactsTable.userId eq userId }
            .mapNotNull { rowToUserContacts(it) }
            .singleOrNull()
    }
    
    override suspend fun saveUserContacts(contacts: UserNotificationContacts): UserNotificationContacts = dbQuery {
        UserNotificationContactsTable.insert {
            it[userId] = contacts.userId
            it[email] = contacts.email
            it[emailVerified] = contacts.emailVerified
            it[pushTokens] = contacts.pushTokens.map { token ->
                mapOf(
                    "token" to token.token,
                    "platform" to token.platform,
                    "deviceId" to (token.deviceId ?: ""),
                    "isActive" to token.isActive.toString(),
                    "addedAt" to token.addedAt.toString()
                )
            }
            it[notificationsEnabled] = contacts.notificationsEnabled
            it[quietHoursStart] = contacts.quietHoursStart
            it[quietHoursEnd] = contacts.quietHoursEnd
            it[createdAt] = Instant.now()
            it[updatedAt] = Instant.now()
        }
        contacts
    }
    
    override suspend fun updateUserContacts(
        userId: String,
        request: UpdateUserNotificationContactsRequest
    ): UserNotificationContacts? = dbQuery {
        val existing = getUserContacts(userId)
        if (existing == null) {
            // Create new if doesn't exist
            val newContacts = UserNotificationContacts(
                userId = userId,
                email = request.email,
                notificationsEnabled = request.notificationsEnabled ?: true,
                quietHoursStart = request.quietHoursStart,
                quietHoursEnd = request.quietHoursEnd
            )
            saveUserContacts(newContacts)
            return@dbQuery newContacts
        }
        
        UserNotificationContactsTable.update({ UserNotificationContactsTable.userId eq userId }) {
            request.email?.let { email -> it[UserNotificationContactsTable.email] = email }
            request.notificationsEnabled?.let { enabled -> it[notificationsEnabled] = enabled }
            request.quietHoursStart?.let { start -> it[quietHoursStart] = start }
            request.quietHoursEnd?.let { end -> it[quietHoursEnd] = end }
            it[updatedAt] = Instant.now()
        }
        getUserContacts(userId)
    }
    
    override suspend fun addPushToken(userId: String, tokenInfo: PushTokenInfo): Boolean = dbQuery {
        val existing = getUserContacts(userId)
        if (existing == null) {
            // Create new contacts with this token
            saveUserContacts(
                UserNotificationContacts(
                    userId = userId,
                    pushTokens = listOf(tokenInfo)
                )
            )
            return@dbQuery true
        }
        
        // Add to existing tokens (filter out duplicates)
        val updatedTokens = (existing.pushTokens.filter { it.token != tokenInfo.token } + tokenInfo)
        
        UserNotificationContactsTable.update({ UserNotificationContactsTable.userId eq userId }) {
            it[pushTokens] = updatedTokens.map { token ->
                mapOf(
                    "token" to token.token,
                    "platform" to token.platform,
                    "deviceId" to (token.deviceId ?: ""),
                    "isActive" to token.isActive.toString(),
                    "addedAt" to token.addedAt.toString()
                )
            }
            it[updatedAt] = Instant.now()
        }
        true
    }
    
    override suspend fun removePushToken(userId: String, token: String): Boolean = dbQuery {
        val existing = getUserContacts(userId) ?: return@dbQuery false
        
        val updatedTokens = existing.pushTokens.filter { it.token != token }
        
        UserNotificationContactsTable.update({ UserNotificationContactsTable.userId eq userId }) {
            it[pushTokens] = updatedTokens.map { token ->
                mapOf(
                    "token" to token.token,
                    "platform" to token.platform,
                    "deviceId" to (token.deviceId ?: ""),
                    "isActive" to token.isActive.toString(),
                    "addedAt" to token.addedAt.toString()
                )
            }
            it[updatedAt] = Instant.now()
        }
        true
    }
    
    // Attribute Notification Preferences
    
    override suspend fun getAttributePreference(userId: String, attributeId: String): AttributeNotificationPreference? = dbQuery {
        AttributeNotificationPreferencesTable
            .selectAll()
            .where {
                (AttributeNotificationPreferencesTable.userId eq userId) and
                (AttributeNotificationPreferencesTable.attributeId eq attributeId)
            }
            .mapNotNull { rowToAttributePreference(it) }
            .singleOrNull()
    }
    
    override suspend fun getAllAttributePreferences(userId: String): List<AttributeNotificationPreference> = dbQuery {
        AttributeNotificationPreferencesTable
            .selectAll()
            .where { AttributeNotificationPreferencesTable.userId eq userId }
            .map { rowToAttributePreference(it) }
    }

    override suspend fun saveAttributePreference(
        userId: String,
        request: CreateAttributeNotificationPreferenceRequest
    ): AttributeNotificationPreference = dbQuery {
        // Check if already exists
        val existing = getAttributePreference(userId, request.attributeId)
        if (existing != null) {
            return@dbQuery existing
        }
        
        val id = UUID.randomUUID().toString()
        val now = Instant.now()
        
        AttributeNotificationPreferencesTable.insert {
            it[AttributeNotificationPreferencesTable.id] = id
            it[AttributeNotificationPreferencesTable.userId] = userId
            it[attributeId] = request.attributeId
            it[notificationsEnabled] = request.notificationsEnabled
            it[notificationFrequency] = request.notificationFrequency.name
            it[minMatchScore] = request.minMatchScore
            it[notifyOnNewPostings] = request.notifyOnNewPostings
            it[notifyOnNewUsers] = request.notifyOnNewUsers
            it[createdAt] = now
            it[updatedAt] = now
        }
        
        AttributeNotificationPreference(
            id = id,
            userId = userId,
            attributeId = request.attributeId,
            notificationsEnabled = request.notificationsEnabled,
            notificationFrequency = request.notificationFrequency,
            minMatchScore = request.minMatchScore,
            notifyOnNewPostings = request.notifyOnNewPostings,
            notifyOnNewUsers = request.notifyOnNewUsers,
            createdAt = now,
            updatedAt = now
        )
    }
    
    override suspend fun updateAttributePreference(
        userId: String,
        attributeId: String,
        request: UpdateAttributeNotificationPreferenceRequest
    ): AttributeNotificationPreference? = dbQuery {
        val existing = getAttributePreference(userId, attributeId) ?: return@dbQuery null
        
        AttributeNotificationPreferencesTable.update({
            (AttributeNotificationPreferencesTable.userId eq userId) and
            (AttributeNotificationPreferencesTable.attributeId eq attributeId)
        }) {
            request.notificationsEnabled?.let { enabled -> it[notificationsEnabled] = enabled }
            request.notificationFrequency?.let { freq -> it[notificationFrequency] = freq.name }
            request.minMatchScore?.let { score -> it[minMatchScore] = score }
            request.notifyOnNewPostings?.let { notify -> it[notifyOnNewPostings] = notify }
            request.notifyOnNewUsers?.let { notify -> it[notifyOnNewUsers] = notify }
            it[updatedAt] = Instant.now()
        }
        
        getAttributePreference(userId, attributeId)
    }
    
    override suspend fun deleteAttributePreference(userId: String, attributeId: String): Boolean = dbQuery {
        AttributeNotificationPreferencesTable.deleteWhere {
            (AttributeNotificationPreferencesTable.userId eq userId) and
            (AttributeNotificationPreferencesTable.attributeId eq attributeId)
        } > 0
    }
    
    override suspend fun getActiveAttributePreferences(): List<AttributeNotificationPreference> = dbQuery {
        AttributeNotificationPreferencesTable
            .selectAll()
            .where { AttributeNotificationPreferencesTable.notificationsEnabled eq true }
            .map { rowToAttributePreference(it) }
    }
    
    // Posting Notification Preferences
    
    override suspend fun getPostingPreference(postingId: String): PostingNotificationPreference? = dbQuery {
        PostingNotificationPreferencesTable
            .selectAll()
            .where { PostingNotificationPreferencesTable.postingId eq postingId }
            .mapNotNull { rowToPostingPreference(it) }
            .singleOrNull()
    }
    
    override suspend fun savePostingPreference(
        postingId: String,
        request: UpdatePostingNotificationPreferenceRequest
    ): PostingNotificationPreference = dbQuery {
        val existing = getPostingPreference(postingId)
        if (existing != null) {
            return@dbQuery existing
        }
        
        val now = Instant.now()
        
        PostingNotificationPreferencesTable.insert {
            it[PostingNotificationPreferencesTable.postingId] = postingId
            it[notificationsEnabled] = request.notificationsEnabled ?: true
            it[notificationFrequency] = (request.notificationFrequency ?: NotificationFrequency.INSTANT).name
            it[minMatchScore] = request.minMatchScore ?: 0.7
            it[createdAt] = now
            it[updatedAt] = now
        }
        
        PostingNotificationPreference(
            postingId = postingId,
            notificationsEnabled = request.notificationsEnabled ?: true,
            notificationFrequency = request.notificationFrequency ?: NotificationFrequency.INSTANT,
            minMatchScore = request.minMatchScore ?: 0.7,
            createdAt = now,
            updatedAt = now
        )
    }
    
    override suspend fun updatePostingPreference(
        postingId: String,
        request: UpdatePostingNotificationPreferenceRequest
    ): PostingNotificationPreference? = dbQuery {
        val existing = getPostingPreference(postingId) ?: return@dbQuery null
        
        PostingNotificationPreferencesTable.update({ PostingNotificationPreferencesTable.postingId eq postingId }) {
            request.notificationsEnabled?.let { enabled -> it[notificationsEnabled] = enabled }
            request.notificationFrequency?.let { freq -> it[notificationFrequency] = freq.name }
            request.minMatchScore?.let { score -> it[minMatchScore] = score }
            it[updatedAt] = Instant.now()
        }
        
        getPostingPreference(postingId)
    }
    
    override suspend fun deletePostingPreference(postingId: String): Boolean = dbQuery {
        PostingNotificationPreferencesTable.deleteWhere {
            PostingNotificationPreferencesTable.postingId eq postingId
        } > 0
    }
    
    override suspend fun getActivePostingPreferences(): List<PostingNotificationPreference> = dbQuery {
        PostingNotificationPreferencesTable
            .selectAll()
            .where { PostingNotificationPreferencesTable.notificationsEnabled eq true }
            .map { rowToPostingPreference(it) }
    }
    
    // Match History
    
    override suspend fun createMatch(match: MatchHistoryEntry): MatchHistoryEntry = dbQuery {
        MatchHistoryTable.insert {
            it[id] = match.id
            it[userId] = match.userId
            it[matchType] = match.matchType.name
            it[sourceType] = match.sourceType.name
            it[sourceId] = match.sourceId
            it[targetType] = match.targetType.name
            it[targetId] = match.targetId
            it[matchScore] = match.matchScore
            it[matchReason] = match.matchReason
            it[notificationSent] = match.notificationSent
            it[notificationSentAt] = match.notificationSentAt
            it[viewed] = match.viewed
            it[viewedAt] = match.viewedAt
            it[dismissed] = match.dismissed
            it[dismissedAt] = match.dismissedAt
            it[matchedAt] = match.matchedAt
        }
        match
    }
    
    override suspend fun getMatch(matchId: String): MatchHistoryEntry? = dbQuery {
        MatchHistoryTable
            .selectAll()
            .where { MatchHistoryTable.id eq matchId }
            .mapNotNull { rowToMatchHistory(it) }
            .singleOrNull()
    }
    
    override suspend fun getUserMatches(userId: String, unviewedOnly: Boolean, limit: Int): List<MatchHistoryEntry> = dbQuery {
        var query = MatchHistoryTable
            .selectAll()
            .where { MatchHistoryTable.userId eq userId }
        
        if (unviewedOnly) {
            query = query.andWhere { MatchHistoryTable.viewed eq false }
        }
        
        query.orderBy(MatchHistoryTable.matchedAt, SortOrder.DESC)
            .limit(limit)
            .map { rowToMatchHistory(it) }
    }
    
    override suspend fun findExistingMatch(
        userId: String,
        sourceType: SourceType,
        sourceId: String,
        targetType: TargetType,
        targetId: String
    ): MatchHistoryEntry? = dbQuery {
        MatchHistoryTable
            .selectAll()
            .where {
                (MatchHistoryTable.userId eq userId) and
                (MatchHistoryTable.sourceType eq sourceType.name) and
                (MatchHistoryTable.sourceId eq sourceId) and
                (MatchHistoryTable.targetType eq targetType.name) and
                (MatchHistoryTable.targetId eq targetId)
            }
            .mapNotNull { rowToMatchHistory(it) }
            .singleOrNull()
    }
    
    override suspend fun markMatchViewed(matchId: String): Boolean = dbQuery {
        MatchHistoryTable.update({ MatchHistoryTable.id eq matchId }) {
            it[viewed] = true
            it[viewedAt] = Instant.now()
        } > 0
    }
    
    override suspend fun markMatchDismissed(matchId: String): Boolean = dbQuery {
        MatchHistoryTable.update({ MatchHistoryTable.id eq matchId }) {
            it[dismissed] = true
            it[dismissedAt] = Instant.now()
        } > 0
    }
    
    override suspend fun markMatchNotificationSent(matchId: String): Boolean = dbQuery {
        MatchHistoryTable.update({ MatchHistoryTable.id eq matchId }) {
            it[notificationSent] = true
            it[notificationSentAt] = Instant.now()
        } > 0
    }
    
    override suspend fun getUnnotifiedMatches(userId: String, since: Instant?): List<MatchHistoryEntry> = dbQuery {
        var query = MatchHistoryTable
            .selectAll()
            .where {
                (MatchHistoryTable.userId eq userId) and
                (MatchHistoryTable.notificationSent eq false) and
                (MatchHistoryTable.dismissed eq false)
            }
        
        since?.let {
            query = query.andWhere { MatchHistoryTable.matchedAt greaterEq it }
        }
        
        query.orderBy(MatchHistoryTable.matchedAt, SortOrder.DESC)
            .map { rowToMatchHistory(it) }
    }
    
    override suspend fun deleteOldMatches(olderThan: Instant): Int = dbQuery {
        MatchHistoryTable.deleteWhere {
            MatchHistoryTable.matchedAt.less(olderThan)
        }
    }
    
    // Helper functions to convert ResultRow to data classes
    
    private fun rowToUserContacts(row: ResultRow): UserNotificationContacts {
        val pushTokensJson = row[UserNotificationContactsTable.pushTokens]
        val pushTokens = pushTokensJson.map { tokenMap ->
            PushTokenInfo(
                token = tokenMap["token"] ?: "",
                platform = tokenMap["platform"] ?: "ANDROID",
                deviceId = tokenMap["deviceId"]?.takeIf { it.isNotEmpty() },
                isActive = tokenMap["isActive"]?.toBoolean() ?: true,
                addedAt = tokenMap["addedAt"]?.let { Instant.parse(it) } ?: Instant.now()
            )
        }
        
        return UserNotificationContacts(
            userId = row[UserNotificationContactsTable.userId],
            email = row[UserNotificationContactsTable.email],
            emailVerified = row[UserNotificationContactsTable.emailVerified],
            pushTokens = pushTokens,
            notificationsEnabled = row[UserNotificationContactsTable.notificationsEnabled],
            quietHoursStart = row[UserNotificationContactsTable.quietHoursStart],
            quietHoursEnd = row[UserNotificationContactsTable.quietHoursEnd]
        )
    }
    
    private fun rowToAttributePreference(row: ResultRow): AttributeNotificationPreference {
        return AttributeNotificationPreference(
            id = row[AttributeNotificationPreferencesTable.id],
            userId = row[AttributeNotificationPreferencesTable.userId],
            attributeId = row[AttributeNotificationPreferencesTable.attributeId],
            notificationsEnabled = row[AttributeNotificationPreferencesTable.notificationsEnabled],
            notificationFrequency = NotificationFrequency.valueOf(row[AttributeNotificationPreferencesTable.notificationFrequency]),
            minMatchScore = row[AttributeNotificationPreferencesTable.minMatchScore],
            notifyOnNewPostings = row[AttributeNotificationPreferencesTable.notifyOnNewPostings],
            notifyOnNewUsers = row[AttributeNotificationPreferencesTable.notifyOnNewUsers],
            createdAt = row[AttributeNotificationPreferencesTable.createdAt],
            updatedAt = row[AttributeNotificationPreferencesTable.updatedAt]
        )
    }
    
    private fun rowToPostingPreference(row: ResultRow): PostingNotificationPreference {
        return PostingNotificationPreference(
            postingId = row[PostingNotificationPreferencesTable.postingId],
            notificationsEnabled = row[PostingNotificationPreferencesTable.notificationsEnabled],
            notificationFrequency = NotificationFrequency.valueOf(row[PostingNotificationPreferencesTable.notificationFrequency]),
            minMatchScore = row[PostingNotificationPreferencesTable.minMatchScore],
            createdAt = row[PostingNotificationPreferencesTable.createdAt],
            updatedAt = row[PostingNotificationPreferencesTable.updatedAt]
        )
    }
    
    private fun rowToMatchHistory(row: ResultRow): MatchHistoryEntry {
        return MatchHistoryEntry(
            id = row[MatchHistoryTable.id],
            userId = row[MatchHistoryTable.userId],
            matchType = MatchType.valueOf(row[MatchHistoryTable.matchType]),
            sourceType = SourceType.valueOf(row[MatchHistoryTable.sourceType]),
            sourceId = row[MatchHistoryTable.sourceId],
            targetType = TargetType.valueOf(row[MatchHistoryTable.targetType]),
            targetId = row[MatchHistoryTable.targetId],
            matchScore = row[MatchHistoryTable.matchScore],
            matchReason = row[MatchHistoryTable.matchReason],
            notificationSent = row[MatchHistoryTable.notificationSent],
            notificationSentAt = row[MatchHistoryTable.notificationSentAt],
            viewed = row[MatchHistoryTable.viewed],
            viewedAt = row[MatchHistoryTable.viewedAt],
            dismissed = row[MatchHistoryTable.dismissed],
            dismissedAt = row[MatchHistoryTable.dismissedAt],
            matchedAt = row[MatchHistoryTable.matchedAt]
        )
    }
}
