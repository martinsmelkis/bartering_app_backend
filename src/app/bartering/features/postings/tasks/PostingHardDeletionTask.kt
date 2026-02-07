package app.bartering.features.postings.tasks

import kotlinx.coroutines.*
import app.bartering.features.postings.dao.UserPostingDao
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours

/**
 * Background task to permanently delete postings that have been expired for a grace period
 * This implements GDPR data minimization by removing old, expired postings and their associated data
 * 
 * Runs periodically to hard delete postings that have been in EXPIRED or DELETED status
 * for more than the grace period (default: 30 days)
 */
class PostingHardDeletionTask(
    private val postingDao: UserPostingDao,
    private val gracePeriodDays: Int = 30,
    private val intervalHours: Long = 24 // Check daily
) {
    private val log = LoggerFactory.getLogger(this::class.java)
    private var deletionJob: Job? = null

    /**
     * Start the hard deletion task
     */
    fun start(scope: CoroutineScope) {
        if (deletionJob?.isActive == true) {
            log.warn("PostingHardDeletionTask already running")
            return
        }

        deletionJob = scope.launch {
            while (isActive) {
                try {
                    log.debug("Running posting hard deletion task (grace period: {} days)", gracePeriodDays)
                    val deletedCount = postingDao.hardDeleteExpiredPostings(gracePeriodDays)
                    if (deletedCount > 0) {
                        log.info("Hard deleted {} postings that were expired for more than {} days", 
                            deletedCount, gracePeriodDays)
                    }
                } catch (e: Exception) {
                    log.error("Error during posting hard deletion check", e)
                }

                // Wait for the next check cycle
                delay(intervalHours.hours)
            }
        }

        log.info("PostingHardDeletionTask started (grace period: {}d, interval: {}h)", 
            gracePeriodDays, intervalHours)
    }

    /**
     * Stop the hard deletion task
     */
    fun stop() {
        deletionJob?.cancel()
        deletionJob = null
        log.info("PostingHardDeletionTask stopped")
    }
}
