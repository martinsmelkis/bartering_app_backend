package app.bartering.features.notifications.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.bartering.extensions.normalizeAttributeForDBProcessing
import app.bartering.features.notifications.dao.NotificationPreferencesDao
import app.bartering.features.notifications.model.*
import app.bartering.features.notifications.utils.NotificationDataBuilder
import app.bartering.features.postings.dao.UserPostingDao
import app.bartering.features.postings.model.UserPosting
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Service for matching new postings/attributes against user notification preferences
 * and creating match history entries
 */
class MatchNotificationService(
    private val preferencesDao: NotificationPreferencesDao,
    private val postingDao: UserPostingDao,
    private val orchestrator: NotificationOrchestrator
) {
    private val log = LoggerFactory.getLogger(this::class.java)
    
    /**
     * Check a new posting against all users' attribute notification preferences
     * Called when a new posting is created
     */
    suspend fun checkPostingAgainstUserAttributes(posting: UserPosting): List<MatchHistoryEntry> {
        val matches = mutableListOf<MatchHistoryEntry>()
        
        // Get all active attribute preferences
        val activePreferences = preferencesDao.getActiveAttributePreferences()
            .filter { it.notifyOnNewPostings } // Only users who want posting notifications
        
        // Group preferences by user
        val preferencesByUser = activePreferences.groupBy { it.userId }
        
        for ((userId, userPreferences) in preferencesByUser) {
            // Don't match user's own postings
            if (userId == posting.userId) continue
            
            // Check each attribute preference
            for (preference in userPreferences) {

                val matchScore = calculateMatchScore(preference.attributeId, posting)

                log.debug("User preference score: {} for preference: {}", matchScore, preference)

                // Only create match if score meets minimum threshold
                if (matchScore >= preference.minMatchScore) {
                    // Check if match already exists
                    val existingMatch = preferencesDao.findExistingMatch(
                        userId = userId,
                        sourceType = SourceType.ATTRIBUTE,
                        sourceId = preference.attributeId,
                        targetType = TargetType.POSTING,
                        targetId = posting.id
                    )
                    
                    if (existingMatch == null) {
                        val match = MatchHistoryEntry(
                            id = UUID.randomUUID().toString(),
                            userId = userId,
                            matchType = MatchType.POSTING_MATCH,
                            sourceType = SourceType.ATTRIBUTE,
                            sourceId = preference.attributeId,
                            targetType = TargetType.POSTING,
                            targetId = posting.userId,
                            matchScore = matchScore,
                            matchReason = "New posting matches your interest in '${preference.attributeId}'",
                            matchedAt = Instant.now()
                        )
                        
                        preferencesDao.createMatch(match)
                        matches.add(match)
                        
                        // Send notification based on frequency
                        if (preference.notificationFrequency == NotificationFrequency.INSTANT) {
                            sendMatchNotification(match, posting)
                        }
                    }
                }
            }
        }
        
        return matches
    }
    
    /**
     * Check a new posting against users' existing interest postings
     * Called when a new offer posting is created
     */
    suspend fun checkPostingAgainstInterestPostings(newPosting: UserPosting): List<MatchHistoryEntry> {
        if (!newPosting.isOffer) {
            // Only check offer postings against interest postings
            return emptyList()
        }
        
        val matches = mutableListOf<MatchHistoryEntry>()
        
        // Get all interest postings (isOffer = false) from all users
        // We'll check ALL interest postings, not just those with explicit preferences
        // since notifications are enabled by default
        val allPostings = postingDao.getAllPostings(includeExpired = false)
        val interestPostings = allPostings.filter { !it.isOffer && it.userId != newPosting.userId }
        
        for (interestPosting in interestPostings) {
            // Get posting preference if exists, or use default (enabled)
            val preference = preferencesDao.getPostingPreference(interestPosting.id)
            
            // Default values: notifications enabled, instant frequency, 0.7 min score
            val notificationsEnabled = preference?.notificationsEnabled ?: true
            val notificationFrequency = preference?.notificationFrequency ?: NotificationFrequency.INSTANT
            val minMatchScore = preference?.minMatchScore ?: 0.7
            
            // Skip if user explicitly disabled notifications for this posting
            if (!notificationsEnabled) continue
            
            val matchScore = calculatePostingMatchScore(interestPosting, newPosting)

            log.debug("Match score posting against interest postings: {} for posting {}", matchScore, interestPosting.id)
            
            if (matchScore >= minMatchScore) {
                // Check if match already exists
                val existingMatch = preferencesDao.findExistingMatch(
                    userId = interestPosting.userId,
                    sourceType = SourceType.POSTING,
                    sourceId = interestPosting.id,
                    targetType = TargetType.POSTING,
                    targetId = newPosting.userId
                )

                log.debug("Existing match found: {}", existingMatch)
                
                if (existingMatch == null) {
                    val match = MatchHistoryEntry(
                        id = UUID.randomUUID().toString(),
                        userId = interestPosting.userId,
                        matchType = MatchType.POSTING_MATCH,
                        sourceType = SourceType.POSTING,
                        sourceId = interestPosting.id,
                        targetType = TargetType.POSTING,
                        targetId = newPosting.userId,
                        matchScore = matchScore,
                        matchReason = "New posting '${newPosting.title}' matches your interest '${interestPosting.title}'",
                        matchedAt = Instant.now()
                    )
                    
                    preferencesDao.createMatch(match)
                    matches.add(match)

                    log.debug("Notification frequency: {}", notificationFrequency)
                    // Send notification based on frequency
                    if (notificationFrequency == NotificationFrequency.INSTANT) {
                        sendMatchNotification(match, newPosting)
                    }
                }
            }
        }
        
        return matches
    }
    
    /**
     * Check when a user adds a new SEEKING attribute
     * Find all existing postings that match this attribute
     */
    suspend fun checkAttributeAgainstPostings(userId: String, attributeId: String): List<MatchHistoryEntry> {
        val matches = mutableListOf<MatchHistoryEntry>()
        
        // Get user's notification preference for this attribute
        val preference = preferencesDao.getAttributePreference(userId, attributeId)
        if (preference == null || !preference.notificationsEnabled || !preference.notifyOnNewPostings) {
            return emptyList()
        }
        
        // Search for postings that match this attribute
        // This is a simplified version - in production you'd use vector similarity search
        val matchingPostings = postingDao.searchPostings(
            searchText = attributeId,
            limit = 20
        )
        
        for (postingWithDistance in matchingPostings) {
            val posting = postingWithDistance.posting
            
            // Don't match user's own postings
            if (posting.userId == userId) continue
            
            val matchScore = postingWithDistance.similarityScore ?: 0.5
            
            if (matchScore >= preference.minMatchScore) {
                // Check if match already exists
                val existingMatch = preferencesDao.findExistingMatch(
                    userId = userId,
                    sourceType = SourceType.ATTRIBUTE,
                    sourceId = attributeId,
                    targetType = TargetType.POSTING,
                    targetId = posting.id
                )
                
                if (existingMatch == null) {
                    val match = MatchHistoryEntry(
                        id = UUID.randomUUID().toString(),
                        userId = userId,
                        matchType = MatchType.POSTING_MATCH,
                        sourceType = SourceType.ATTRIBUTE,
                        sourceId = attributeId,
                        targetType = TargetType.POSTING,
                        targetId = posting.userId,
                        matchScore = matchScore,
                        matchReason = "Posting '${posting.title}' matches your interest in '$attributeId'",
                        matchedAt = Instant.now()
                    )
                    
                    preferencesDao.createMatch(match)
                    matches.add(match)
                    
                    // For retrospective matches, usually sent in digest
                    if (preference.notificationFrequency == NotificationFrequency.INSTANT) {
                        sendMatchNotification(match, posting)
                    }
                }
            }
        }
        
        return matches
    }
    
    /**
     * Check when a user adds a PROVIDING/OFFERING attribute
     * Find all existing SEEKING postings that match this attribute
     * Notify the posting owners about the match
     */
    suspend fun checkOfferingAgainstSeekingPostings(userId: String, attributeId: String): List<MatchHistoryEntry> {
        val matches = mutableListOf<MatchHistoryEntry>()
        
        // Search for SEEKING postings (isOffer = false) that match this offering
        val matchingPostings = postingDao.searchPostings(
            searchText = attributeId,
            limit = 20
        )
        
        for (postingWithDistance in matchingPostings) {
            val posting = postingWithDistance.posting
            
            // Only match SEEKING postings (not offers)
            if (posting.isOffer) continue
            
            // Don't match user's own postings
            if (posting.userId == userId) continue
            
            val matchScore = postingWithDistance.similarityScore ?: 0.5
            
            // Get the posting owner's preferences
            val postingOwnerPreference = preferencesDao.getPostingPreference(posting.id)
            if (postingOwnerPreference == null || !postingOwnerPreference.notificationsEnabled) {
                continue
            }
            
            if (matchScore >= postingOwnerPreference.minMatchScore) {
                // Check if match already exists for the posting owner
                val existingMatch = preferencesDao.findExistingMatch(
                    userId = posting.userId, // Match is for the posting owner
                    sourceType = SourceType.POSTING,
                    sourceId = posting.id,
                    targetType = TargetType.USER,
                    targetId = userId
                )
                
                if (existingMatch == null) {
                    val match = MatchHistoryEntry(
                        id = UUID.randomUUID().toString(),
                        userId = posting.userId, // Notify the posting owner
                        matchType = MatchType.POSTING_MATCH,
                        sourceType = SourceType.POSTING,
                        sourceId = posting.id,
                        targetType = TargetType.USER,
                        targetId = userId, // The user who added the offering
                        matchScore = matchScore,
                        matchReason = "User offering '$attributeId' matches your posting '${posting.title}'",
                        matchedAt = Instant.now()
                    )
                    
                    preferencesDao.createMatch(match)
                    matches.add(match)
                    
                    // Send notification to posting owner based on their frequency
                    if (postingOwnerPreference.notificationFrequency == NotificationFrequency.INSTANT) {
                        log.info("Notifying userId={} about match: {}", posting.userId, match.matchReason)
                        sendMatchNotification(match, posting)
                    }
                }
            }
        }
        
        return matches
    }
    
    /**
     * Check when a user adds a SEEKING attribute
     * Find all existing OFFERING postings that match this attribute
     * Notify the posting owners about the match
     */
    suspend fun checkSeekingAgainstOfferingPostings(userId: String, attributeId: String): List<MatchHistoryEntry> {
        val matches = mutableListOf<MatchHistoryEntry>()
        
        // Search for OFFERING postings (isOffer = true) that match this seeking
        val matchingPostings = postingDao.searchPostings(
            searchText = attributeId,
            limit = 20
        )
        
        for (postingWithDistance in matchingPostings) {
            val posting = postingWithDistance.posting
            
            // Only match OFFERING postings (not seeking/interest postings)
            if (!posting.isOffer) continue
            
            // Don't match user's own postings
            if (posting.userId == userId) continue
            
            val matchScore = postingWithDistance.similarityScore ?: 0.5
            
            // Get the posting owner's preferences
            val postingOwnerPreference = preferencesDao.getPostingPreference(posting.id)
            if (postingOwnerPreference == null || !postingOwnerPreference.notificationsEnabled) {
                continue
            }
            
            if (matchScore >= postingOwnerPreference.minMatchScore) {
                // Check if match already exists for the posting owner
                val existingMatch = preferencesDao.findExistingMatch(
                    userId = posting.userId, // Match is for the posting owner
                    sourceType = SourceType.POSTING,
                    sourceId = posting.id,
                    targetType = TargetType.USER,
                    targetId = userId
                )
                
                if (existingMatch == null) {
                    val match = MatchHistoryEntry(
                        id = UUID.randomUUID().toString(),
                        userId = posting.userId, // Notify the posting owner
                        matchType = MatchType.POSTING_MATCH,
                        sourceType = SourceType.POSTING,
                        sourceId = posting.id,
                        targetType = TargetType.USER,
                        targetId = userId, // The user who added the seeking attribute
                        matchScore = matchScore,
                        matchReason = "User seeking '$attributeId' matches your posting '${posting.title}'",
                        matchedAt = Instant.now()
                    )
                    
                    preferencesDao.createMatch(match)
                    matches.add(match)
                    
                    // Send notification to posting owner based on their frequency
                    if (postingOwnerPreference.notificationFrequency == NotificationFrequency.INSTANT) {
                        log.info("Notifying userId={} about match: {}", posting.userId, match.matchReason)
                        sendMatchNotification(match, posting)
                    }
                }
            }
        }
        
        return matches
    }
    
    /**
     * Calculate match score between an attribute and a posting
     * Uses simple text matching - in production would use vector embeddings
     */
    private fun calculateMatchScore(attributeId: String, posting: UserPosting): Double {
        var score = 0.0
        
        // Exact match in title (highest weight)
        if (posting.title.normalizeAttributeForDBProcessing().contains(attributeId, ignoreCase = true)) {
            score += 0.6
        }
        
        // Exact match in description
        if (posting.description.normalizeAttributeForDBProcessing().contains(attributeId, ignoreCase = true)) {
            score += 0.4
        }
        
        // Check posting attributes
        for (postingAttr in posting.attributes) {
            if (postingAttr.attributeId.equals(attributeId, ignoreCase = true)) {
                score += 0.5
            }
        }
        
        // Add freshness bonus (newer postings score slightly higher)
        val ageInDays = (Instant.now().toEpochMilli() - posting.createdAt.toEpochMilli()) / (1000 * 60 * 60 * 24)
        val freshnessBonus = when {
            ageInDays <= 1 -> 0.1
            ageInDays <= 7 -> 0.05
            else -> 0.0
        }
        score += freshnessBonus
        
        return score.coerceAtMost(1.0)
    }
    
    /**
     * Calculate match score between two postings (interest vs offer)
     */
    private suspend fun calculatePostingMatchScore(interestPosting: UserPosting, offerPosting: UserPosting): Double {
        var score = 0.0

        // Title similarity
        val titleWords = interestPosting.title.lowercase().split(" ").filter { it.length > 3 }
        val offerTitleWords = offerPosting.title.lowercase().split(" ").filter { it.length > 3 }
        val titleOverlap = titleWords.count { word -> offerTitleWords.any { it.contains(word) || word.contains(it) } }
        score += (titleOverlap.toDouble() / titleWords.size.coerceAtLeast(1)) * 0.5

        // Description similarity
        val descWords = interestPosting.description.lowercase().split(" ").filter { it.length > 3 }.take(20)
        val offerDescWords = offerPosting.description.lowercase().split(" ").filter { it.length > 3 }
        val descOverlap = descWords.count { word -> offerDescWords.any { it.contains(word) || word.contains(it) } }
        score += (descOverlap.toDouble() / descWords.size.coerceAtLeast(1)) * 0.3

        // 2. Attribute matching (30% weight)
        val interestAttrIds = interestPosting.attributes.map { it.attributeId }
        val offerAttrIds = offerPosting.attributes.map { it.attributeId }
        val attrOverlap = interestAttrIds.count { it in offerAttrIds }
        if (interestAttrIds.isNotEmpty()) {
            score += (attrOverlap.toDouble() / interestAttrIds.size) * 0.3
        }

        // 3. Value matching (20% weight) - if both have values
        if (interestPosting.value != null && offerPosting.value != null) {
            val valueDiff = kotlin.math.abs(interestPosting.value - offerPosting.value)
            val maxValue = maxOf(interestPosting.value, offerPosting.value)
            val valueScore = if (maxValue > 0) {
                (1.0 - (valueDiff / maxValue)).coerceAtLeast(0.0)
            } else {
                1.0
            }
            score += valueScore
        }

        if (score < 0.7) {
            // 1. Semantic similarity using embeddings (most important - 50% weight)
            val embeddingSimilarity = calculateEmbeddingSimilarity(interestPosting.id, offerPosting.id)
            if (embeddingSimilarity != null) {
                score += embeddingSimilarity * 0.8
                log.debug("Embedding similarity between '{}' and '{}': {}", 
                    interestPosting.title, offerPosting.title, embeddingSimilarity)
            }
        }

        return score.coerceAtMost(1.0)
    }

    /**
     * Calculate cosine similarity between embeddings of two postings
     */
    private suspend fun calculateEmbeddingSimilarity(postingId1: String, postingId2: String): Double? {
        return try {
            withContext(Dispatchers.IO) {
                newSuspendedTransaction(Dispatchers.IO) {
                    // Fetch embeddings from database
                    val embedding1 = app.bartering.features.postings.db.UserPostingsTable
                        .select(app.bartering.features.postings.db.UserPostingsTable.embedding)
                        .where { app.bartering.features.postings.db.UserPostingsTable.id eq postingId1 }
                        .singleOrNull()
                        ?.get(app.bartering.features.postings.db.UserPostingsTable.embedding)

                    val embedding2 = app.bartering.features.postings.db.UserPostingsTable
                        .select(app.bartering.features.postings.db.UserPostingsTable.embedding)
                        .where { app.bartering.features.postings.db.UserPostingsTable.id eq postingId2 }
                        .singleOrNull()
                        ?.get(app.bartering.features.postings.db.UserPostingsTable.embedding)

                    if (embedding1 == null || embedding2 == null) {
                        return@newSuspendedTransaction null
                    }

                    // Parse embeddings (format: "[0.1, 0.2, ...]")
                    val vec1 = parseEmbedding(embedding1)
                    val vec2 = parseEmbedding(embedding2)

                    if (vec1 == null || vec2 == null || vec1.size != vec2.size) {
                        return@newSuspendedTransaction null
                    }

                    // Calculate cosine similarity
                    cosineSimilarity(vec1, vec2)
                }
            }
        } catch (e: Exception) {
            log.warn("Error calculating embedding similarity", e)
            null
        }
    }

    /**
     * Parse embedding string to float array
     */
    private fun parseEmbedding(embeddingStr: String): FloatArray? {
        return try {
            embeddingStr
                .trim('[', ']')
                .split(",")
                .map { it.trim().toFloat() }
                .toFloatArray()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Calculate cosine similarity between two vectors
     */
    private fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Double {
        var dotProduct = 0.0
        var norm1 = 0.0
        var norm2 = 0.0

        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            norm1 += vec1[i] * vec1[i]
            norm2 += vec2[i] * vec2[i]
        }

        norm1 = kotlin.math.sqrt(norm1)
        norm2 = kotlin.math.sqrt(norm2)

        return if (norm1 > 0 && norm2 > 0) {
            dotProduct / (norm1 * norm2)
        } else {
            0.0
        }
    }
    
    /**
     * Send notification for a match
     */
    private suspend fun sendMatchNotification(match: MatchHistoryEntry, posting: UserPosting) {
        try {
            // Get user's contacts
            val contacts = preferencesDao.getUserContacts(match.userId)
            log.debug("User contacts: {}", contacts)
            if (contacts == null || !contacts.notificationsEnabled) {
                return
            }
            
            // Check quiet hours
            if (isInQuietHours(contacts.quietHoursStart, contacts.quietHoursEnd)) {
                return
            }
            
            // Create notification payload using builder
            // Determine match type: if source is a posting (wishlist), it's a wishlist_match
            val notificationType = if (match.sourceType == SourceType.POSTING) "wishlist_match" else "match"
            
            val notification = NotificationDataBuilder.match(
                matchId = match.id,
                matchReason = match.matchReason ?: "A new posting matches your interests",
                postingId = posting.id,
                postingUserId = posting.userId,
                postingTitle = posting.title,
                postingImageUrl = posting.imageUrls.firstOrNull(),
                matchScore = match.matchScore,
                matchType = notificationType
            )

            log.info("Match found, sending notification")
            
            // Send via orchestrator
            orchestrator.sendNotification(
                userId = match.userId,
                notification = notification,
                category = NotificationCategory.WISHLIST_MATCH // Use available category
            )
            
            // Mark as sent
            preferencesDao.markMatchNotificationSent(match.id)
            
        } catch (e: Exception) {
            log.error("Failed to send match notification", e)
            e.printStackTrace()
        }
    }
    
    /**
     * Check if current time is within user's quiet hours
     */
    private fun isInQuietHours(quietHoursStart: Int?, quietHoursEnd: Int?): Boolean {
        if (quietHoursStart == null || quietHoursEnd == null) {
            return false
        }
        
        val now = java.time.LocalTime.now()
        val currentHour = now.hour

        log.debug("Quiet hours: {} - {}", quietHoursStart, quietHoursEnd)

        return if (quietHoursStart < quietHoursEnd) {
            currentHour in quietHoursStart until quietHoursEnd
        } else {
            // Handles cases like 22:00 to 07:00 (overnight)
            currentHour !in quietHoursEnd..<quietHoursStart
        }
    }
    
    /**
     * Process digest notifications (daily/weekly)
     * This should be called by a scheduled job
     */
    suspend fun processDigestNotifications(frequency: NotificationFrequency) {
        // Get all users with preferences for this frequency
        val allPreferences = preferencesDao.getActiveAttributePreferences()
            .filter { it.notificationFrequency == frequency }
            .groupBy { it.userId }
        
        for ((userId, _) in allPreferences) {
            try {
                // Get unnotified matches for this user
                val since = when (frequency) {
                    NotificationFrequency.DAILY -> Instant.now().minusSeconds(24 * 60 * 60)
                    NotificationFrequency.WEEKLY -> Instant.now().minusSeconds(7 * 24 * 60 * 60)
                    else -> continue
                }
                
                val unnotifiedMatches = preferencesDao.getUnnotifiedMatches(userId, since)
                
                if (unnotifiedMatches.isEmpty()) continue
                
                // Get user's contacts
                val contacts = preferencesDao.getUserContacts(userId)
                if (contacts == null || !contacts.notificationsEnabled) continue
                
                // Send digest notification
                val notification = NotificationData(
                    title = when (frequency) {
                        NotificationFrequency.DAILY -> "Daily Match Digest"
                        NotificationFrequency.WEEKLY -> "Weekly Match Digest"
                        else -> "Match Digest"
                    },
                    body = "You have ${unnotifiedMatches.size} new matches",
                    data = mapOf(
                        "matchCount" to unnotifiedMatches.size.toString(),
                        "frequency" to frequency.name,
                        "type" to "DIGEST"
                    )
                )
                
                orchestrator.sendNotification(
                    userId = userId,
                    notification = notification,
                    category = NotificationCategory.SYSTEM_UPDATE // Use available category
                )
                
                // Mark all as sent
                unnotifiedMatches.forEach { match ->
                    preferencesDao.markMatchNotificationSent(match.id)
                }
                
            } catch (e: Exception) {
                log.error("Failed to process digest for userId={}", userId, e)
                e.printStackTrace()
            }
        }
    }
}