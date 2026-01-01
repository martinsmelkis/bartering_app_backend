package org.barter.features.encryptedfiles.tasks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.barter.features.encryptedfiles.dao.EncryptedFileDao

/**
 * Background task to clean up expired or downloaded encrypted files
 * Runs periodically to free up storage space
 */
class FileCleanupTask(
    private val fileDao: EncryptedFileDao,
    private val intervalHours: Long = 1 // Run cleanup every hour
) {
    /**
     * Start the cleanup task in the provided coroutine scope
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            println("FileCleanupTask started (runs every $intervalHours hours)")
            while (isActive) {
                try {
                    val deletedCount = fileDao.deleteExpiredFiles()
                    if (deletedCount > 0) {
                        println("FileCleanupTask: Deleted $deletedCount expired/downloaded files")
                    }
                } catch (e: Exception) {
                    println("FileCleanupTask error: ${e.message}")
                    e.printStackTrace()
                }
                delay(intervalHours * 60 * 60 * 1000) // Wait for next run
            }
        }
    }
}