package app.bartering.features.migration.tasks

import kotlinx.coroutines.*
import app.bartering.features.migration.dao.MigrationSessionDao
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours

/**
 * Background task to clean up expired and old migration sessions.
 *
 * This task:
 * 1. Marks sessions that have passed their expiry time as EXPIRED
 * 2. Deletes completed/expired/cancelled sessions older than 7 days
 * 3. Prevents accumulation of stale migration data
 *
 * Runs every hour by default.
 */
class MigrationCleanupTask(
    private val migrationDao: MigrationSessionDao,
    private val intervalHours: Long = 1
) {
    private val log = LoggerFactory.getLogger(this::class.java)
    private var cleanupJob: Job? = null

    /**
     * Start the cleanup task
     */
    fun start(scope: CoroutineScope) {
        if (cleanupJob?.isActive == true) {
            log.warn("MigrationCleanupTask already running")
            return
        }

        cleanupJob = scope.launch {
            // Initial cleanup on startup
            runCleanup()

            while (isActive) {
                // Wait for the next check cycle
                delay(intervalHours.hours)
                runCleanup()
            }
        }

        log.info("MigrationCleanupTask started (interval: {}h)", intervalHours)
    }

    /**
     * Run a single cleanup cycle
     */
    private suspend fun runCleanup() {
        try {
                log.debug("Running migration session cleanup")

                val deletedCount = migrationDao.cleanupExpiredSessions()
                if (deletedCount > 0) {
                        log.info("Cleaned up {} old migration sessions", deletedCount)
                    }
            } catch (e: Exception) {
                log.error("Error during migration session cleanup", e)
            }
        }

    /**
     * Stop the cleanup task
     */
    fun stop() {
        cleanupJob?.cancel()
        cleanupJob = null
        log.info("MigrationCleanupTask stopped")
    }
}