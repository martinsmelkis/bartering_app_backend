package app.bartering.features.notifications.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.bartering.extensions.normalizeAttributeForDBProcessing
import app.bartering.features.notifications.dao.NotificationPreferencesDao
import app.bartering.features.notifications.model.*
import app.bartering.features.notifications.utils.NotificationDataBuilder
import app.bartering.features.notifications.utils.StopWordsFilter
import app.bartering.features.postings.dao.UserPostingDao
import app.bartering.features.postings.model.UserPosting
import app.bartering.features.profile.dao.UserProfileDao
import app.bartering.localization.Localization
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * Service for matching new postings/attributes against user notification preferences
 * and creating match history entries
 */
class MatchNotificationService(
    private val preferencesDao: NotificationPreferencesDao,
    private val postingDao: UserPostingDao,
    private val orchestrator: NotificationOrchestrator,
    private val profileDao: UserProfileDao
) {
    private val log = LoggerFactory.getLogger(this::class.java)
    
    /**
     * Get localized attribute name from attributeId
     */
    private fun getLocalizedAttribute(attributeId: String, locale: Locale): String {
        val key = "attr_$attributeId"
        return Localization.getString(key, locale)
    }
    
    /**
     * Get user's preferred locale
     */
    private suspend fun getUserLocale(userId: String): Locale {
        return try {
            val profile = profileDao.getProfile(userId)
            val langCode = profile?.preferredLanguage ?: "en"
            Locale.forLanguageTag(langCode)
        } catch (e: Exception) {
            log.warn("Failed to get locale for user {}: {}", userId, e.message)
            Locale.ENGLISH
        }
    }
    
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
                        val userLocale = getUserLocale(userId)
                        val localizedAttribute = getLocalizedAttribute(preference.attributeId, userLocale)
                        val localizedReason = Localization.getString(
                            "match.posting_matches_interest",
                            userLocale,
                            localizedAttribute
                        )
                        
                        val match = MatchHistoryEntry(
                            id = UUID.randomUUID().toString(),
                            userId = userId,
                            matchType = MatchType.POSTING_MATCH,
                            sourceType = SourceType.ATTRIBUTE,
                            sourceId = preference.attributeId,
                            targetType = TargetType.POSTING,
                            targetId = posting.userId,
                            matchScore = matchScore,
                            matchReason = localizedReason,
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
                    val userLocale = getUserLocale(interestPosting.userId)
                    val localizedReason = Localization.getString(
                        "match.posting_matches_posting",
                        userLocale,
                        newPosting.title,
                        interestPosting.title
                    )
                    
                    val match = MatchHistoryEntry(
                        id = UUID.randomUUID().toString(),
                        userId = interestPosting.userId,
                        matchType = MatchType.POSTING_MATCH,
                        sourceType = SourceType.POSTING,
                        sourceId = interestPosting.id,
                        targetType = TargetType.POSTING,
                        targetId = newPosting.userId,
                        matchScore = matchScore,
                        matchReason = localizedReason,
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
                    val userLocale = getUserLocale(userId)
                    val localizedAttribute = getLocalizedAttribute(attributeId, userLocale)
                    val localizedReason = Localization.getString(
                        "match.posting_matches_attribute",
                        userLocale,
                        posting.title,
                        localizedAttribute
                    )
                    
                    val match = MatchHistoryEntry(
                        id = UUID.randomUUID().toString(),
                        userId = userId,
                        matchType = MatchType.POSTING_MATCH,
                        sourceType = SourceType.ATTRIBUTE,
                        sourceId = attributeId,
                        targetType = TargetType.POSTING,
                        targetId = posting.userId,
                        matchScore = matchScore,
                        matchReason = localizedReason,
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
                    val userLocale = getUserLocale(posting.userId)
                    val localizedAttribute = getLocalizedAttribute(attributeId, userLocale)
                    val localizedReason = Localization.getString(
                        "match.user_offering_matches_posting",
                        userLocale,
                        localizedAttribute,
                        posting.title
                    )
                    
                    val match = MatchHistoryEntry(
                        id = UUID.randomUUID().toString(),
                        userId = posting.userId, // Notify the posting owner
                        matchType = MatchType.POSTING_MATCH,
                        sourceType = SourceType.POSTING,
                        sourceId = posting.id,
                        targetType = TargetType.USER,
                        targetId = userId, // The user who added the offering
                        matchScore = matchScore,
                        matchReason = localizedReason,
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
                    val userLocale = getUserLocale(posting.userId)
                    val localizedAttribute = getLocalizedAttribute(attributeId, userLocale)
                    val localizedReason = Localization.getString(
                        "match.user_seeking_matches_posting",
                        userLocale,
                        localizedAttribute,
                        posting.title
                    )
                    
                    val match = MatchHistoryEntry(
                        id = UUID.randomUUID().toString(),
                        userId = posting.userId, // Notify the posting owner
                        matchType = MatchType.POSTING_MATCH,
                        sourceType = SourceType.POSTING,
                        sourceId = posting.id,
                        targetType = TargetType.USER,
                        targetId = userId, // The user who added the seeking attribute
                        matchScore = matchScore,
                        matchReason = localizedReason,
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
     * Check when a user adds a SEEKING or PROVIDING attribute against other users' profile attributes
     * Notify users when someone within 10 miles adds a matching complementary skill
     * (SEEKING users get notified about nearby PROVIDING users and vice versa)
     */
    suspend fun checkUserAttributeAgainstOtherUserProfiles(
        userId: String, 
        attributeId: String, 
        attributeType: app.bartering.features.attributes.model.UserAttributeType
    ): List<MatchHistoryEntry> {
        val matches = mutableListOf<MatchHistoryEntry>()
        
        try {
            // Get the user's profile to access their location
            val userProfile = profileDao.getProfile(userId)
            if (userProfile == null || userProfile.latitude == null || userProfile.longitude == null) {
                log.debug("User {} has no location set, skipping profile attribute matching", userId)
                return emptyList()
            }
            
            // Copy to local variables to avoid smart cast issues
            val userLatitude = userProfile.latitude
            val userLongitude = userProfile.longitude
            
            if (userLatitude == null || userLongitude == null) {
                log.debug("User {} has no location set, skipping profile attribute matching", userId)
                return emptyList()
            }
            
            // Determine the complementary attribute type to search for
            val complementaryType = when (attributeType) {
                app.bartering.features.attributes.model.UserAttributeType.SEEKING -> 
                    app.bartering.features.attributes.model.UserAttributeType.PROVIDING
                app.bartering.features.attributes.model.UserAttributeType.PROVIDING -> 
                    app.bartering.features.attributes.model.UserAttributeType.SEEKING
                else -> {
                    log.debug("Attribute type {} not supported for profile matching", attributeType)
                    return emptyList()
                }
            }
            
            // Find users with the complementary attribute within 10 miles (~16.09 km)
            val matchingUsers = findUsersWithAttributeNearby(
                attributeId = attributeId.normalizeAttributeForDBProcessing(),
                attributeType = complementaryType,
                latitude = userLatitude,
                longitude = userLongitude,
                radiusKm = 16.09, // 10 miles
                excludeUserId = userId
            )
            
            log.debug("Found {} users with complementary attribute {} within 10 miles", matchingUsers.size, attributeId)
            
            for (matchingUserProfile in matchingUsers) {
                val matchingUserId = matchingUserProfile.profile.userId
                
                // Check if the matching user has notifications enabled for this attribute
                val matchingUserPreference = preferencesDao.getAttributePreference(matchingUserId,
                    attributeId.normalizeAttributeForDBProcessing())
                
                // Default: notifications enabled
                val matchingUserNotificationsEnabled = matchingUserPreference?.notificationsEnabled ?: true
                val matchingUserNotifyOnNewUsers = matchingUserPreference?.notifyOnNewUsers ?: false
                val matchingUserNotificationFrequency = matchingUserPreference?.notificationFrequency 
                    ?: NotificationFrequency.INSTANT
                val minMatchScore = matchingUserPreference?.minMatchScore ?: 0.7
                
                if (!matchingUserNotificationsEnabled || !matchingUserNotifyOnNewUsers) {
                    log.debug("User {} has notifications disabled for attribute {}", matchingUserId, attributeId)
                    continue
                }
                
                // Calculate match score based on relevancy and distance
                val matchScore = calculateProfileAttributeMatchScore(
                    matchingUserProfile = matchingUserProfile,
                    attributeId = attributeId,
                    distance = matchingUserProfile.distanceKm ?: 0.0
                )
                
                if (matchScore < minMatchScore) {
                    log.debug("Match score {} below threshold {} for user {}", matchScore, minMatchScore, matchingUserId)
                    continue
                }
                
                // Check if match already exists
                val existingMatch = preferencesDao.findExistingMatch(
                    userId = matchingUserId,
                    sourceType = SourceType.ATTRIBUTE,
                    sourceId = attributeId,
                    targetType = TargetType.USER,
                    targetId = userId
                )
                
                if (existingMatch == null) {
                    val userLocale = getUserLocale(matchingUserId)
                    val localizedAttribute = getLocalizedAttribute(attributeId.normalizeAttributeForDBProcessing(), userLocale)
                    
                    val localizedReason = when (attributeType) {
                        app.bartering.features.attributes.model.UserAttributeType.PROVIDING ->
                            Localization.getString(
                                "match.user_providing_nearby",
                                userLocale,
                                userProfile.name,
                                localizedAttribute
                            )
                        app.bartering.features.attributes.model.UserAttributeType.SEEKING ->
                            Localization.getString(
                                "match.user_seeking_nearby",
                                userLocale,
                                userProfile.name,
                                localizedAttribute
                            )
                        else -> Localization.getString("match.default", userLocale)
                    }
                    
                    val match = MatchHistoryEntry(
                        id = UUID.randomUUID().toString(),
                        userId = matchingUserId, // Notify the matching user
                        matchType = MatchType.USER_MATCH,
                        sourceType = SourceType.ATTRIBUTE,
                        sourceId = attributeId,
                        targetType = TargetType.USER,
                        targetId = userId, // The user who just added the attribute
                        matchScore = matchScore,
                        matchReason = localizedReason,
                        matchedAt = Instant.now()
                    )
                    
                    preferencesDao.createMatch(match)
                    matches.add(match)
                    
                    // Send notification based on frequency
                    if (matchingUserNotificationFrequency == NotificationFrequency.INSTANT) {
                        log.info("Notifying userId={} about profile match: {}", matchingUserId, match.matchReason)
                        sendProfileMatchNotification(match, userProfile)
                    }
                }
            }
            
        } catch (e: Exception) {
            log.error("Failed to check user attribute against other profiles for userId={}, attributeId={}", 
                userId, attributeId, e)
        }
        
        return matches
    }
    
    /**
     * Find users with a specific attribute within a given radius
     */
    private suspend fun findUsersWithAttributeNearby(
        attributeId: String,
        attributeType: app.bartering.features.attributes.model.UserAttributeType,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        excludeUserId: String
    ): List<app.bartering.features.profile.model.UserProfileWithDistance> {
        return withContext(Dispatchers.IO) {
            newSuspendedTransaction(Dispatchers.IO) {
                val radiusMeters = radiusKm * 1000.0
                
                // Raw SQL query to find users with the attribute within the radius
                val query = """
                    SELECT DISTINCT
                        up.user_id,
                        up.name,
                        up.location,
                        ST_Distance(up.location::geography, ST_MakePoint(?, ?)::geography) as distance_meters,
                        up.preferred_language
                    FROM user_profiles up
                    INNER JOIN user_attributes ua ON up.user_id = ua.user_id
                    WHERE ua.attribute_id = ?
                      AND ua.type = ?
                      AND up.user_id != ?
                      AND up.location IS NOT NULL
                      AND ST_DWithin(
                          up.location::geography,
                          ST_MakePoint(?, ?)::geography,
                          ?
                      )
                    ORDER BY distance_meters ASC
                    LIMIT 50
                """.trimIndent()
                
                val results = mutableListOf<app.bartering.features.profile.model.UserProfileWithDistance>()
                
                org.jetbrains.exposed.sql.transactions.TransactionManager.current().connection
                    .prepareStatement(query, false).also { statement ->
                        statement[1] = longitude
                        statement[2] = latitude
                        statement[3] = attributeId
                        statement[4] = attributeType.name
                        statement[5] = excludeUserId
                        statement[6] = longitude
                        statement[7] = latitude
                        statement[8] = radiusMeters
                        
                        val rs = statement.executeQuery()
                        while (rs.next()) {
                            // Parse location using LocationParser utility to handle PostGIS POINT format
                            val (parsedLongitude, parsedLatitude) = app.bartering.features.profile.util.LocationParser.parseLocation(rs)
                            
                            val distanceMeters = rs.getDouble("distance_meters")
                            val distanceKm = distanceMeters / 1000.0

                            println("@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@ Nearby attr check user: ${rs.getString("user_id")} ${distanceMeters}")
                            val profile = app.bartering.features.profile.model.UserProfile(
                                userId = rs.getString("user_id"),
                                name = rs.getString("name") ?: "Unknown",
                                latitude = parsedLatitude,
                                longitude = parsedLongitude,
                                attributes = emptyList(),
                                profileKeywordDataMap = null,
                                preferredLanguage = rs.getString("preferred_language") ?: "en"
                            )
                            
                            results.add(
                                app.bartering.features.profile.model.UserProfileWithDistance(
                                    profile = profile,
                                    distanceKm = distanceKm,
                                    matchRelevancyScore = null
                                )
                            )
                        }
                    }
                
                results
            }
        }
    }
    
    /**
     * Calculate match score for profile attribute matching
     * Factors in attribute relevancy and proximity
     */
    private fun calculateProfileAttributeMatchScore(
        matchingUserProfile: app.bartering.features.profile.model.UserProfileWithDistance,
        attributeId: String,
        distance: Double
    ): Double {
        var score = 0.7 // Base score for attribute match
        
        // Distance bonus: closer users score higher
        // Within 1 km: +0.2, within 5 km: +0.15, within 10 km: +0.1
        val distanceBonus = when {
            distance <= 1.0 -> 0.2
            distance <= 5.0 -> 0.15
            distance <= 10.0 -> 0.1
            else -> 0.05
        }
        score += distanceBonus
        
        return score.coerceAtMost(1.0)
    }
    
    /**
     * Send notification for a profile match
     */
    private suspend fun sendProfileMatchNotification(match: MatchHistoryEntry, matchedUserProfile: app.bartering.features.profile.model.UserProfile) {
        try {
            // Get user's contacts for quiet hours check
            val contacts = preferencesDao.getUserContacts(match.userId)
            log.debug("User contacts: {}", contacts)
            
            // If contacts exist, check if notifications are enabled and quiet hours
            if (contacts != null) {
                if (!contacts.notificationsEnabled) {
                    log.debug("Notifications disabled for user {}", match.userId)
                    return
                }
                
                // Check quiet hours
                if (isInQuietHours(contacts.quietHoursStart, contacts.quietHoursEnd)) {
                    log.debug("User {} is in quiet hours", match.userId)
                    return
                }
            } else {
                log.debug("No contacts configured for user {}, will attempt WebSocket fallback", match.userId)
            }
            
            // Get localized default message if matchReason is null
            val userLocale = getUserLocale(match.userId)
            val finalMatchReason = match.matchReason ?: Localization.getString(
                "match.default",
                userLocale
            )
            
            val notification = NotificationDataBuilder.match(
                matchId = match.id,
                matchReason = finalMatchReason,
                postingId = null, // No posting for profile matches
                postingUserId = matchedUserProfile.userId,
                postingTitle = matchedUserProfile.name,
                postingImageUrl = null,
                matchScore = match.matchScore,
                matchType = "profile_match"
            )

            log.info("Profile match found, sending notification")
            
            // Send via orchestrator
            orchestrator.sendNotification(
                userId = match.userId,
                notification = notification
            )
            
            // Mark as sent
            preferencesDao.markMatchNotificationSent(match.id)
            
        } catch (e: Exception) {
            log.error("Failed to send profile match notification", e)
            e.printStackTrace()
        }
    }
    
    /**
     * Calculate match score between an attribute and a posting
     * Uses simple text matching - in production would use vector embeddings
     */
    private suspend fun calculateMatchScore(attributeId: String, posting: UserPosting): Double {
        var score = 0.0
        
        // Get posting owner's locale for language-aware stopword filtering
        val postingLocale = getUserLocale(posting.userId)
        
        // Extract meaningful words from the attribute ID for better matching
        val attributeWords = StopWordsFilter.extractMeaningfulWords(
            attributeId,
            minLength = 3,
            includeTransactionKeywords = true,
            locale = postingLocale
        )
        
        // Extract meaningful words from title and description
        val titleWords = StopWordsFilter.extractMeaningfulWords(
            posting.title,
            minLength = 3,
            includeTransactionKeywords = true,
            locale = postingLocale
        )
        
        val descriptionWords = StopWordsFilter.extractMeaningfulWords(
            posting.description,
            minLength = 3,
            includeTransactionKeywords = true,
            locale = postingLocale
        )
        
        // Calculate word overlap for title (highest weight)
        val titleMatches = attributeWords.count { attrWord ->
            titleWords.any { it.contains(attrWord, ignoreCase = true) || attrWord.contains(it, ignoreCase = true) }
        }
        if (attributeWords.isNotEmpty()) {
            score += (titleMatches.toDouble() / attributeWords.size) * 0.6
        }
        
        // Calculate word overlap for description
        val descMatches = attributeWords.count { attrWord ->
            descriptionWords.any { it.contains(attrWord, ignoreCase = true) || attrWord.contains(it, ignoreCase = true) }
        }
        if (attributeWords.isNotEmpty()) {
            score += (descMatches.toDouble() / attributeWords.size) * 0.4
        }
        
        // Check posting attributes - exact match
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

        // Get user locales for language-aware stopword filtering
        val interestLocale = getUserLocale(interestPosting.userId)
        val offerLocale = getUserLocale(offerPosting.userId)

        // Title similarity - filter out stopwords and transaction keywords
        val titleWords = StopWordsFilter.extractMeaningfulWords(
            interestPosting.title,
            minLength = 3,
            includeTransactionKeywords = true,
            locale = interestLocale
        )
        val offerTitleWords = StopWordsFilter.extractMeaningfulWords(
            offerPosting.title,
            minLength = 3,
            includeTransactionKeywords = true,
            locale = offerLocale
        )
        
        val titleOverlap = titleWords.count { word -> offerTitleWords.any { it.contains(word) || word.contains(it) } }
        score += (titleOverlap.toDouble() / titleWords.size.coerceAtLeast(1)) * 0.6

        // Description similarity - filter out stopwords and transaction keywords
        val descWords = StopWordsFilter.extractMeaningfulWords(
            interestPosting.description,
            minLength = 3,
            includeTransactionKeywords = true,
            locale = interestLocale
        ).take(20)
        val offerDescWords = StopWordsFilter.extractMeaningfulWords(
            offerPosting.description,
            minLength = 3,
            includeTransactionKeywords = true,
            locale = offerLocale
        )
        
        val descOverlap = descWords.count { word -> offerDescWords.any { it.contains(word) || word.contains(it) } }
        score += (descOverlap.toDouble() / descWords.size.coerceAtLeast(1)) * 0.4

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
                score += embeddingSimilarity * 0.4
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
            // Get user's contacts for quiet hours check
            val contacts = preferencesDao.getUserContacts(match.userId)
            log.debug("User contacts: {}", contacts)
            
            // If contacts exist, check if notifications are enabled and quiet hours
            if (contacts != null) {
                if (!contacts.notificationsEnabled) {
                    log.debug("Notifications disabled for user {}", match.userId)
                    return
                }
                
                // Check quiet hours
                if (isInQuietHours(contacts.quietHoursStart, contacts.quietHoursEnd)) {
                    log.debug("User {} is in quiet hours", match.userId)
                    return
                }
            } else {
                log.debug("No contacts configured for user {}, will attempt WebSocket fallback", match.userId)
            }
            
            // Create notification payload using builder
            // Determine match type: if source is a posting (wishlist), it's a wishlist_match
            val notificationType = if (match.sourceType == SourceType.POSTING) "wishlist_match" else "match"
            
            // Get localized default message if matchReason is null
            val userLocale = getUserLocale(match.userId)
            val finalMatchReason = match.matchReason ?: Localization.getString(
                "match.default",
                userLocale
            )
            
            val notification = NotificationDataBuilder.match(
                matchId = match.id,
                matchReason = finalMatchReason,
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
                notification = notification
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
                    notification = notification
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