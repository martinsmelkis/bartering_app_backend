package app.bartering.features.chat.tasks

import kotlinx.coroutines.*
import app.bartering.features.chat.dao.OfflineMessageDao
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours

/**
 * Background task to clean up old delivered messages
 * Runs periodically to keep the database clean
 */
class MessageCleanupTask(
    private val offlineMessageDao: OfflineMessageDao,
    private val intervalHours: Long = 24,
    private val retentionDays: Int = 7
) {
    private val log = LoggerFactory.getLogger(this::class.java)
    private var cleanupJob: Job? = null

    /**
     * Start the cleanup task
     */
    fun start(scope: CoroutineScope) {
        if (cleanupJob?.isActive == true) {
            println("MessageCleanupTask already running")
            return
        }

        cleanupJob = scope.launch {
            while (isActive) {
                try {
                    log.info("Running offline message cleanup task")
                    val deletedCount = offlineMessageDao.deleteDeliveredMessages(retentionDays)
                    log.info("Cleaned up {} delivered messages older than {} days", deletedCount, retentionDays)
                } catch (e: Exception) {
                    log.error("Error during message cleanup", e)
                }

                // Wait for the next cleanup cycle
                delay(intervalHours.hours)
            }
        }

        log.info("MessageCleanupTask started (interval: {}h, retention: {}d)", intervalHours, retentionDays)
    }

    /**
     * Stop the cleanup task
     */
    fun stop() {
        cleanupJob?.cancel()
        cleanupJob = null
        log.info("MessageCleanupTask stopped")
    }
}
