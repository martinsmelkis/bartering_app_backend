package app.bartering.features.reviews.tasks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.bartering.features.reviews.dao.RiskPatternDao
import org.slf4j.LoggerFactory

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
    private val log = LoggerFactory.getLogger(this::class.java)
    
    /**
     * Start the cleanup task in the provided coroutine scope.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            log.info("Starting RiskTrackingCleanupTask (runs every {}h)", intervalHours)
            log.info("Device tracking retention: {} days", deviceRetentionDays)
            log.info("IP tracking retention: {} days", ipRetentionDays)
            log.info("Location history retention: {} days", locationRetentionDays)
            log.info("Risk patterns retention: {} days", riskPatternRetentionDays)
            
            while (true) {
                try {
                    performCleanup()
                } catch (e: Exception) {
                    log.error("Error during risk tracking cleanup", e)
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
        log.info("Starting risk tracking data cleanup")
        val startTime = System.currentTimeMillis()
        
        var totalDeleted = 0
        
        // Clean up device tracking data
        try {
            val deletedDevices = riskPatternDao.cleanupOldDeviceTracking(deviceRetentionDays)
            totalDeleted += deletedDevices
            if (deletedDevices > 0) {
                log.info("Deleted {} old device tracking records", deletedDevices)
            }
        } catch (e: Exception) {
            log.warn("Failed to cleanup device tracking", e)
        }
        
        // Clean up IP tracking data
        try {
            val deletedIps = riskPatternDao.cleanupOldIpTracking(ipRetentionDays)
            totalDeleted += deletedIps
            if (deletedIps > 0) {
                log.info("Deleted {} old IP tracking records", deletedIps)
            }
        } catch (e: Exception) {
            log.warn("Failed to cleanup IP tracking", e)
        }
        
        // Clean up location change history
        try {
            val deletedLocations = riskPatternDao.cleanupOldLocationChanges(locationRetentionDays)
            totalDeleted += deletedLocations
            if (deletedLocations > 0) {
                log.info("Deleted {} old location change records", deletedLocations)
            }
        } catch (e: Exception) {
            log.warn("Failed to cleanup location changes", e)
        }
        
        // Clean up old risk patterns
        try {
            val deletedPatterns = riskPatternDao.cleanupOldRiskPatterns(riskPatternRetentionDays)
            totalDeleted += deletedPatterns
            if (deletedPatterns > 0) {
                log.info("Deleted {} old risk pattern records", deletedPatterns)
            }
        } catch (e: Exception) {
            log.warn("Failed to cleanup risk patterns", e)
        }
        
        val duration = System.currentTimeMillis() - startTime
        
        if (totalDeleted > 0) {
            log.info("Risk tracking cleanup complete: Deleted {} records in {}ms", totalDeleted, duration)
        } else {
            log.info("Risk tracking cleanup complete: No old records to delete")
        }
    }
}
