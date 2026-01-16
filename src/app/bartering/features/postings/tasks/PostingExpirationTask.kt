package app.bartering.features.postings.tasks

import kotlinx.coroutines.*
import app.bartering.features.postings.dao.UserPostingDao
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours

/**
 * Background task to mark expired postings
 * Runs periodically to update posting statuses
 */
class PostingExpirationTask(
    private val postingDao: UserPostingDao,
    private val intervalHours: Long = 1 // Check every hour
) {
    private val log = LoggerFactory.getLogger(this::class.java)
    private var expirationJob: Job? = null

    /**
     * Start the expiration task
     */
    fun start(scope: CoroutineScope) {
        if (expirationJob?.isActive == true) {
            log.warn("PostingExpirationTask already running")
            return
        }

        expirationJob = scope.launch {
            while (isActive) {
                try {
                    log.debug("Running posting expiration task")
                    val expiredCount = postingDao.markExpiredPostings()
                    if (expiredCount > 0) {
                        log.info("Marked {} postings as expired", expiredCount)
                    }
                } catch (e: Exception) {
                    log.error("Error during posting expiration check", e)
                }

                // Wait for the next check cycle
                delay(intervalHours.hours)
            }
        }

        log.info("PostingExpirationTask started (interval: {}h)", intervalHours)
    }

    /**
     * Stop the expiration task
     */
    fun stop() {
        expirationJob?.cancel()
        expirationJob = null
        log.info("PostingExpirationTask stopped")
    }
}
