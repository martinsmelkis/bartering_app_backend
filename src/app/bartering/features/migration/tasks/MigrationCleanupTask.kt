package app.bartering.features.migration.tasks

import app.bartering.features.migration.dao.MigrationDao
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Background task to clean up expired migration sessions.
 */
class MigrationCleanupTask(
    private val migrationDao: MigrationDao
) {
    private val log = LoggerFactory.getLogger(this::class.java)
    private var cleanupJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start(intervalMinutes: Long = 60) {
        if (cleanupJob != null) return

        log.info("Starting migration cleanup task (interval: {} min)", intervalMinutes)
        cleanupJob = scope.launch {
            while (isActive) {
                try {
                    val cleaned = migrationDao.cleanupExpiredSessions()
                    if (cleaned > 0) log.info("Cleaned up {} expired migration sessions", cleaned)
                    delay(TimeUnit.MINUTES.toMillis(intervalMinutes))
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    log.error("Error during cleanup", e)
                    delay(TimeUnit.MINUTES.toMillis(5))
                }
            }
        }
    }

    fun stop() {
        cleanupJob?.cancel()
        cleanupJob = null
        scope.cancel()
    }
}
