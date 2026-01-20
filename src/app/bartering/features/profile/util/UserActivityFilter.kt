package app.bartering.features.profile.util

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.profile.cache.UserActivityCache
import app.bartering.features.profile.db.UserPresenceTable
import app.bartering.features.profile.model.UserProfileWithDistance
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Utility for filtering users based on activity status.
 * 
 * Activity Tiers:
 * - ACTIVE: Last seen within 30 days - show normally
 * - INACTIVE: Last seen 30-90 days ago - show with lower priority
 * - DORMANT: Last seen 90+ days ago - hide from searches
 * 
 * Usage:
 * ```kotlin
 * val filtered = UserActivityFilter.filterByActivity(
 *     profiles = allProfiles,
 *     maxInactiveDays = 30,
 *     includeDormant = false
 * )
 * ```
 */
object UserActivityFilter {
    private val log = LoggerFactory.getLogger(this::class.java)
    
    // Activity thresholds (in days)
    const val ACTIVE_THRESHOLD_DAYS = 30L
    const val INACTIVE_THRESHOLD_DAYS = 90L
    
    /**
     * User activity status.
     */
    enum class ActivityStatus {
        ACTIVE,      // Last seen 0-30 days ago
        INACTIVE,    // Last seen 30-90 days ago
        DORMANT,     // Last seen 90+ days ago
        UNKNOWN      // Never seen or no activity data
    }
    
    /**
     * Get activity status for a user.
     * 
     * Uses a two-tier approach:
     * 1. Check in-memory cache first (fast, but may be incomplete)
     * 2. Fall back to database if not in cache (accurate, persistent)
     * 
     * This ensures accurate results even after server restarts.
     */
    fun getActivityStatus(userId: String): ActivityStatus {
        // Try cache first (fast path)
        val cachedLastSeen = UserActivityCache.getLastSeen(userId)
        
        val lastSeen = if (cachedLastSeen != null) {
            // Cache hit - use cached timestamp
            Instant.ofEpochMilli(cachedLastSeen)
        } else {
            // Cache miss - query database for persistent data
            runBlocking {
                dbQuery {
                    UserPresenceTable
                        .selectAll()
                        .where { UserPresenceTable.userId eq userId }
                        .firstOrNull()
                        ?.get(UserPresenceTable.lastActivityAt)
                }
            } ?: return ActivityStatus.UNKNOWN
        }
        
        val now = Instant.now()
        val daysSinceActive = ChronoUnit.DAYS.between(lastSeen, now)
        
        return when {
            daysSinceActive <= ACTIVE_THRESHOLD_DAYS -> ActivityStatus.ACTIVE
            daysSinceActive <= INACTIVE_THRESHOLD_DAYS -> ActivityStatus.INACTIVE
            else -> ActivityStatus.DORMANT
        }
    }
    
    /**
     * Check if user should be shown in search results.
     * 
     * @param userId User to check
     * @param includeDormant Whether to include dormant users (default: false)
     * @param includeInactive Whether to include inactive users (default: true)
     * @return true if user should be shown
     */
    fun shouldShowInSearch(
        userId: String,
        includeDormant: Boolean = false,
        includeInactive: Boolean = true
    ): Boolean {
        return when (getActivityStatus(userId)) {
            ActivityStatus.ACTIVE -> true
            ActivityStatus.INACTIVE -> includeInactive
            ActivityStatus.DORMANT -> includeDormant
            ActivityStatus.UNKNOWN -> true  // Show new users with no activity yet
        }
    }
    
    /**
     * Filter profiles by activity status.
     * 
     * @param profiles List of profiles to filter
     * @param includeDormant Whether to include dormant users (90+ days inactive)
     * @param includeInactive Whether to include inactive users (30-90 days inactive)
     * @return Filtered list of profiles
     */
    fun filterByActivity(
        profiles: List<UserProfileWithDistance>,
        includeDormant: Boolean = false,
        includeInactive: Boolean = true
    ): List<UserProfileWithDistance> {
        val filtered = profiles.filter { profile ->
            shouldShowInSearch(
                userId = profile.profile.userId,
                includeDormant = includeDormant,
                includeInactive = includeInactive
            )
        }
        
        val removedCount = profiles.size - filtered.size
        if (removedCount > 0) {
            log.debug("Filtered out {} inactive/dormant users from search results", removedCount)
        }
        
        return filtered
    }
    
    /**
     * Sort profiles by activity (active users first).
     * Within each tier, preserves existing sort order (usually by relevancy).
     * 
     * @param profiles List of profiles to sort
     * @return Sorted list with active users first
     */
    fun sortByActivity(profiles: List<UserProfileWithDistance>): List<UserProfileWithDistance> {
        return profiles.sortedWith(
            compareBy<UserProfileWithDistance> { profile ->
                // Sort by activity status (lower number = more active)
                when (getActivityStatus(profile.profile.userId)) {
                    ActivityStatus.ACTIVE -> 0
                    ActivityStatus.INACTIVE -> 1
                    ActivityStatus.DORMANT -> 2
                    ActivityStatus.UNKNOWN -> 0  // Treat new users as active
                }
            }.thenByDescending { it.matchRelevancyScore }  // Then by relevancy
        )
    }
    
    /**
     * Apply activity penalty to relevancy scores.
     * 
     * - Active users: No penalty
     * - Inactive users: -0.1 to score
     * - Dormant users: -0.3 to score
     * 
     * This allows inactive users to still appear if they're a great match,
     * but active users get preference.
     * 
     * @param profiles List of profiles
     * @return Profiles with adjusted relevancy scores
     */
    fun applyActivityPenalty(
        profiles: List<UserProfileWithDistance>
    ): List<UserProfileWithDistance> {
        return profiles.map { profile ->
            val penalty = when (getActivityStatus(profile.profile.userId)) {
                ActivityStatus.ACTIVE -> 0.0
                ActivityStatus.INACTIVE -> -0.1
                ActivityStatus.DORMANT -> -0.3
                ActivityStatus.UNKNOWN -> 0.0
            }
            
            if (penalty != 0.0 && profile.matchRelevancyScore != null) {
                val adjustedScore = (profile.matchRelevancyScore + penalty).coerceAtLeast(0.0)
                profile.copy(matchRelevancyScore = adjustedScore)
            } else {
                profile
            }
        }
    }
    
    /**
     * Get days since last activity for a user.
     * Returns null if user has no activity data.
     * 
     * Checks cache first, falls back to database for persistence.
     */
    fun getDaysSinceLastActivity(userId: String): Long? {
        // Try cache first
        val cachedLastSeen = UserActivityCache.getLastSeen(userId)
        
        val lastSeen = if (cachedLastSeen != null) {
            Instant.ofEpochMilli(cachedLastSeen)
        } else {
            // Fall back to database
            runBlocking {
                dbQuery {
                    UserPresenceTable
                        .selectAll()
                        .where { UserPresenceTable.userId eq userId }
                        .firstOrNull()
                        ?.get(UserPresenceTable.lastActivityAt)
                }
            } ?: return null
        }
        
        val now = Instant.now()
        return ChronoUnit.DAYS.between(lastSeen, now)
    }
    
    /**
     * Batch get activity status for multiple users.
     * Efficient for checking many users at once.
     * 
     * Uses hybrid approach:
     * 1. Get all users from cache
     * 2. Query database for cache misses
     * 3. Combine results
     */
    fun getBatchActivityStatus(userIds: List<String>): Map<String, ActivityStatus> {
        if (userIds.isEmpty()) return emptyMap()
        
        val now = Instant.now()
        
        // Step 1: Get from cache (fast)
        val cachedLastSeen = UserActivityCache.getBatchLastSeen(userIds)
        
        // Step 2: Find users not in cache
        val missedUserIds = userIds.filter { cachedLastSeen[it] == null }
        
        // Step 3: Query database for missed users (if any)
        val dbLastSeen = if (missedUserIds.isNotEmpty()) {
            runBlocking {
                dbQuery {
                    UserPresenceTable
                        .selectAll()
                        .where { UserPresenceTable.userId inList missedUserIds }
                        .associate { row ->
                            row[UserPresenceTable.userId] to row[UserPresenceTable.lastActivityAt]
                        }
                }
            }
        } else {
            emptyMap()
        }
        
        // Step 4: Combine cache + database results
        return userIds.associateWith { userId ->
            val lastSeenMillis = cachedLastSeen[userId]
            val lastSeen = if (lastSeenMillis != null) {
                Instant.ofEpochMilli(lastSeenMillis)
            } else {
                dbLastSeen[userId]
            }
            
            if (lastSeen == null) {
                ActivityStatus.UNKNOWN
            } else {
                val daysSinceActive = ChronoUnit.DAYS.between(lastSeen, now)
                
                when {
                    daysSinceActive <= ACTIVE_THRESHOLD_DAYS -> ActivityStatus.ACTIVE
                    daysSinceActive <= INACTIVE_THRESHOLD_DAYS -> ActivityStatus.INACTIVE
                    else -> ActivityStatus.DORMANT
                }
            }
        }
    }

}
