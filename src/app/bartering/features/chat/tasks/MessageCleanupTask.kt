package app.bartering.features.chat.tasks

import kotlinx.coroutines.*
import app.bartering.features.chat.dao.OfflineMessageDao
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
                    println("Running offline message cleanup task...")
                    val deletedCount = offlineMessageDao.deleteDeliveredMessages(retentionDays)
                    println("Cleaned up $deletedCount delivered messages older than $retentionDays days")
                } catch (e: Exception) {
                    println("Error during message cleanup: ${e.message}")
                    e.printStackTrace()
                }

                // Wait for the next cleanup cycle
                delay(intervalHours.hours)
            }
        }

        println("MessageCleanupTask started (interval: ${intervalHours}h, retention: ${retentionDays}d)")
    }

    /**
     * Stop the cleanup task
     */
    fun stop() {
        cleanupJob?.cancel()
        cleanupJob = null
        println("MessageCleanupTask stopped")
    }
}
