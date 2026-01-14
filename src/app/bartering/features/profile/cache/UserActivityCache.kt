package app.bartering.features.profile.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.profile.db.UserPresenceTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.upsert
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance in-memory cache for user activity tracking.
 * 
 * Features:
 * - Zero-latency reads from memory
 * - Batch database writes every 30 seconds
 * - Thread-safe concurrent access
 * - Automatic cleanup of inactive users
 * 
 * Usage:
 * ```
 * UserActivityCache.updateActivity(userId, "browsing")
 * val isOnline = UserActivityCache.isOnline(userId)
 * val lastSeen = UserActivityCache.getLastSeen(userId)
 * ```
 */
object UserActivityCache {
    
    // In-memory activity storage: userId -> (timestamp, activityType)
    private val activityMap = ConcurrentHashMap<String, ActivityRecord>()
    
    // Scheduler for periodic database sync
    private val syncScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "UserActivityCache-Sync").apply {
            isDaemon = true
        }
    }
    
    // Flag to track if cache has been initialized
    private val isInitialized = AtomicBoolean(false)
    
    // Configuration
    private const val SYNC_INTERVAL_SECONDS = 30L
    private const val ONLINE_THRESHOLD_MILLIS = 5 * 60 * 1000L // 5 minutes
    private const val CLEANUP_THRESHOLD_MILLIS = 30 * 60 * 1000L // 30 minutes
    
    /**
     * Internal data class to store activity information
     */
    private data class ActivityRecord(
        val timestamp: Long,
        val activityType: String,
        val isDirty: Boolean = true // Needs to be synced to DB
    )
    
    /**
     * Initialize the cache and start periodic database sync.
     * Should be called once during application startup.
     */
    fun init() {
        if (isInitialized.getAndSet(true)) {
            println("⚠️ UserActivityCache already initialized")
            return
        }
        
        println("🚀 Initializing UserActivityCache with ${SYNC_INTERVAL_SECONDS}s sync interval")
        
        // Schedule periodic database sync
        syncScheduler.scheduleAtFixedRate({
            try {
                syncToDatabase()
            } catch (e: Exception) {
                println("❌ Error during activity sync: ${e.message}")
                e.printStackTrace()
            }
        }, SYNC_INTERVAL_SECONDS, SYNC_INTERVAL_SECONDS, TimeUnit.SECONDS)
        
        // Schedule periodic cleanup of old entries
        syncScheduler.scheduleAtFixedRate({
            try {
                cleanupInactiveUsers()
            } catch (e: Exception) {
                println("❌ Error during cleanup: ${e.message}")
            }
        }, 5, 5, TimeUnit.MINUTES)
        
        println("✅ UserActivityCache initialized successfully")
    }
    
    /**
     * Update user activity timestamp.
     * This is extremely fast as it only updates in-memory cache.
     * 
     * @param userId The user ID
     * @param activityType Type of activity (browsing, searching, chatting, etc.)
     */
    fun updateActivity(userId: String, activityType: String = "active") {
        val now = System.currentTimeMillis()
        activityMap[userId] = ActivityRecord(
            timestamp = now,
            activityType = activityType,
            isDirty = true
        )
    }
    
    /**
     * Check if a user is currently online.
     * A user is considered online if their last activity was within 5 minutes.
     * 
     * @param userId The user ID to check
     * @return true if user is online, false otherwise
     */
    fun isOnline(userId: String): Boolean {
        val record = activityMap[userId] ?: return false
        val now = System.currentTimeMillis()
        return (now - record.timestamp) < ONLINE_THRESHOLD_MILLIS
    }
    
    /**
     * Get the last seen timestamp for a user.
     * 
     * @param userId The user ID
     * @return Timestamp in milliseconds, or null if user has never been active
     */
    fun getLastSeen(userId: String): Long? {
        return activityMap[userId]?.timestamp
    }
    
    /**
     * Get the last activity type for a user.
     * 
     * @param userId The user ID
     * @return Activity type string, or null if user has never been active
     */
    fun getLastActivityType(userId: String): String? {
        return activityMap[userId]?.activityType
    }
    
    /**
     * Batch check online status for multiple users.
     * Very efficient for checking many users at once.
     * 
     * @param userIds List of user IDs to check
     * @return Map of userId to online status
     */
    fun getBatchOnlineStatus(userIds: List<String>): Map<String, Boolean> {
        val now = System.currentTimeMillis()
        return userIds.associateWith { userId ->
            val record = activityMap[userId]
            record != null && (now - record.timestamp) < ONLINE_THRESHOLD_MILLIS
        }
    }
    
    /**
     * Batch get last seen timestamps for multiple users.
     * 
     * @param userIds List of user IDs
     * @return Map of userId to last seen timestamp (or null)
     */
    fun getBatchLastSeen(userIds: List<String>): Map<String, Long?> {
        return userIds.associateWith { userId ->
            activityMap[userId]?.timestamp
        }
    }
    
    /**
     * Get current cache statistics for monitoring.
     */
    fun getStats(): CacheStats {
        val now = System.currentTimeMillis()
        var onlineCount = 0
        var dirtyCount = 0
        
        activityMap.values.forEach { record ->
            if ((now - record.timestamp) < ONLINE_THRESHOLD_MILLIS) {
                onlineCount++
            }
            if (record.isDirty) {
                dirtyCount++
            }
        }
        
        return CacheStats(
            totalUsers = activityMap.size,
            onlineUsers = onlineCount,
            dirtyRecords = dirtyCount
        )
    }
    
    /**
     * Sync dirty records to the database.
     * Called automatically every 30 seconds.
     */
    private fun syncToDatabase() {
        val dirtyRecords = activityMap.filter { it.value.isDirty }
        
        if (dirtyRecords.isEmpty()) {
            return
        }
        
        println("📊 Syncing ${dirtyRecords.size} activity records to database...")
        
        // Use coroutine for async database write
        CoroutineScope(Dispatchers.IO).launch {
            try {
                dbQuery {
                    dirtyRecords.forEach { (userId, record) ->
                        try {
                            UserPresenceTable.upsert {
                                it[UserPresenceTable.userId] = userId
                                it[lastActivityAt] = Instant.ofEpochMilli(record.timestamp)
                                it[lastActivityType] = record.activityType
                                it[updatedAt] = Instant.now()
                            }
                            
                            // Mark as clean (not dirty) after successful sync
                            activityMap[userId] = record.copy(isDirty = false)
                        } catch (e: Exception) {
                            println("⚠️ Failed to sync activity for user $userId: ${e.message}")
                        }
                    }
                }
                println("✅ Activity sync complete")
            } catch (e: Exception) {
                println("❌ Database sync failed: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Clean up users who haven't been active for a long time.
     * Keeps memory usage bounded.
     */
    private fun cleanupInactiveUsers() {
        val now = System.currentTimeMillis()
        val toRemove = mutableListOf<String>()
        
        activityMap.forEach { (userId, record) ->
            // Remove users inactive for more than 30 minutes
            if ((now - record.timestamp) > CLEANUP_THRESHOLD_MILLIS) {
                toRemove.add(userId)
            }
        }
        
        if (toRemove.isNotEmpty()) {
            toRemove.forEach { activityMap.remove(it) }
            println("🧹 Cleaned up ${toRemove.size} inactive users from cache")
        }
    }
    
    /**
     * Manually trigger a database sync (for testing or graceful shutdown).
     */
    fun forceSyncNow() {
        syncToDatabase()
    }
    
    /**
     * Shutdown the cache and sync all remaining data.
     * Should be called during application shutdown.
     */
    fun shutdown() {
        println("🛑 Shutting down UserActivityCache...")
        
        // Sync any remaining dirty records
        forceSyncNow()
        
        // Wait a bit for sync to complete
        Thread.sleep(1000)
        
        // Shutdown scheduler
        syncScheduler.shutdown()
        try {
            if (!syncScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                syncScheduler.shutdownNow()
            }
        } catch (e: InterruptedException) {
            syncScheduler.shutdownNow()
        }
        
        println("✅ UserActivityCache shutdown complete")
    }
    
    /**
     * Statistics data class
     */
    data class CacheStats(
        val totalUsers: Int,
        val onlineUsers: Int,
        val dirtyRecords: Int
    )
}
