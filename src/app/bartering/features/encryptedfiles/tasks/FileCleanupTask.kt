package app.bartering.features.encryptedfiles.tasks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import app.bartering.features.encryptedfiles.dao.EncryptedFileDao
import org.slf4j.LoggerFactory

/**
 * Background task to clean up expired or downloaded encrypted files
 * Runs periodically to free up storage space
 */
class FileCleanupTask(
    private val fileDao: EncryptedFileDao,
    private val intervalHours: Long = 1 // Run cleanup every hour
) {
    private val log = LoggerFactory.getLogger(this::class.java)
    /**
     * Start the cleanup task in the provided coroutine scope
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            log.info("FileCleanupTask started (runs every {} hours)", intervalHours)
            while (isActive) {
                try {
                    val deletedCount = fileDao.deleteExpiredFiles()
                    if (deletedCount > 0) {
                        log.info("Deleted {} expired/downloaded encrypted files", deletedCount)
                    }
                } catch (e: Exception) {
                    log.error("FileCleanupTask error", e)
                }
                delay(intervalHours * 60 * 60 * 1000) // Wait for next run
            }
        }
    }
}