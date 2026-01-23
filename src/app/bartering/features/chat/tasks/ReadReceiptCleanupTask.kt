package app.bartering.features.chat.tasks

import app.bartering.features.chat.dao.ReadReceiptDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Background task to clean up old read receipts
 * Runs periodically to delete receipts older than the retention period
 */
class ReadReceiptCleanupTask(
    private val readReceiptDao: ReadReceiptDao,
    private val intervalHours: Long = 24, // Run once per day
    private val retentionDays: Long = 30 // Keep receipts for 30 days
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Start the cleanup task in the provided coroutine scope
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            log.info("🧹 Read receipt cleanup task started (interval: {}h, retention: {}d)", 
                intervalHours, retentionDays)
            
            while (isActive) {
                try {
                    // Wait for the interval
                    delay(intervalHours * 60 * 60 * 1000)
                    
                    // Calculate cutoff date
                    val cutoffDate = Instant.now().minus(retentionDays, ChronoUnit.DAYS)
                    
                    log.debug("🧹 Running read receipt cleanup (deleting receipts older than {})", cutoffDate)
                    
                    // Delete old receipts
                    val deletedCount = readReceiptDao.deleteOldReceipts(cutoffDate)
                    
                    if (deletedCount > 0) {
                        log.info("🧹 Deleted {} old read receipts (older than {} days)", 
                            deletedCount, retentionDays)
                    } else {
                        log.debug("🧹 No old read receipts to delete")
                    }
                    
                } catch (e: Exception) {
                    log.error("❌ Error in read receipt cleanup task", e)
                    // Continue running even if one iteration fails
                }
            }
            
            log.info("🧹 Read receipt cleanup task stopped")
        }
    }
}
