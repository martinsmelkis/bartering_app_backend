package org.barter.features.reviews.tasks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.barter.features.reviews.dao.RiskPatternDao

/**
 * Background task to periodically clean up old risk tracking data.
 * 
 * Cleans up data from:
 * - Device tracking records
 * - IP tracking records
 * - Location change history
 * - Risk pattern analysis records
 * 
 * This is important for:
 * - Privacy compliance (GDPR, data retention policies)
 * - Database performance (smaller tables = faster queries)
 * - Storage management (preventing unbounded growth)
 * 
 * Runs every [intervalHours] hours and deletes records older than the specified retention periods.
 * 
 * Usage:
 * ```
 * val cleanupTask = RiskTrackingCleanupTask(riskPatternDao)
 * cleanupTask.start(scope)
 * ```
 */
class ReviewRiskTrackingCleanupTask(
    private val riskPatternDao: RiskPatternDao,
    private val intervalHours: Long = 24,  // Run daily
    private val deviceRetentionDays: Int = 90,  // Keep device data for 90 days
    private val ipRetentionDays: Int = 90,  // Keep IP data for 90 days
    private val locationRetentionDays: Int = 90,  // Keep location history for 90 days
    private val riskPatternRetentionDays: Int = 180  // Keep risk patterns longer for trend analysis
) {
    
    /**
     * Start the cleanup task in the provided coroutine scope.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            println("🚀 Starting RiskTrackingCleanupTask (runs every ${intervalHours}h)")
            println("   - Device tracking retention: $deviceRetentionDays days")
            println("   - IP tracking retention: $ipRetentionDays days")
            println("   - Location history retention: $locationRetentionDays days")
            println("   - Risk patterns retention: $riskPatternRetentionDays days")
            
            while (true) {
                try {
                    performCleanup()
                } catch (e: Exception) {
                    println("❌ Error during risk tracking cleanup: ${e.message}")
                    e.printStackTrace()
                }
                
                // Wait until next cleanup cycle
                delay(intervalHours * 60 * 60 * 1000) // Convert hours to milliseconds
            }
        }
    }
    
    /**
     * Perform the actual cleanup of old risk tracking data.
     */
    private suspend fun performCleanup() {
        println("🧹 Starting risk tracking data cleanup...")
        val startTime = System.currentTimeMillis()
        
        var totalDeleted = 0
        
        // Clean up device tracking data
        try {
            val deletedDevices = riskPatternDao.cleanupOldDeviceTracking(deviceRetentionDays)
            totalDeleted += deletedDevices
            if (deletedDevices > 0) {
                println("   ✓ Deleted $deletedDevices old device tracking records")
            }
        } catch (e: Exception) {
            println("   ⚠️ Failed to cleanup device tracking: ${e.message}")
        }
        
        // Clean up IP tracking data
        try {
            val deletedIps = riskPatternDao.cleanupOldIpTracking(ipRetentionDays)
            totalDeleted += deletedIps
            if (deletedIps > 0) {
                println("   ✓ Deleted $deletedIps old IP tracking records")
            }
        } catch (e: Exception) {
            println("   ⚠️ Failed to cleanup IP tracking: ${e.message}")
        }
        
        // Clean up location change history
        try {
            val deletedLocations = riskPatternDao.cleanupOldLocationChanges(locationRetentionDays)
            totalDeleted += deletedLocations
            if (deletedLocations > 0) {
                println("   ✓ Deleted $deletedLocations old location change records")
            }
        } catch (e: Exception) {
            println("   ⚠️ Failed to cleanup location changes: ${e.message}")
        }
        
        // Clean up old risk patterns
        try {
            val deletedPatterns = riskPatternDao.cleanupOldRiskPatterns(riskPatternRetentionDays)
            totalDeleted += deletedPatterns
            if (deletedPatterns > 0) {
                println("   ✓ Deleted $deletedPatterns old risk pattern records")
            }
        } catch (e: Exception) {
            println("   ⚠️ Failed to cleanup risk patterns: ${e.message}")
        }
        
        val duration = System.currentTimeMillis() - startTime
        
        if (totalDeleted > 0) {
            println("✅ Risk tracking cleanup complete: Deleted $totalDeleted records in ${duration}ms")
        } else {
            println("✅ Risk tracking cleanup complete: No old records to delete")
        }
    }
}
