package app.bartering.features.postings.tasks

import kotlinx.coroutines.*
import app.bartering.features.postings.dao.UserPostingDao
import kotlin.time.Duration.Companion.hours

/**
 * Background task to mark expired postings
 * Runs periodically to update posting statuses
 */
class PostingExpirationTask(
    private val postingDao: UserPostingDao,
    private val intervalHours: Long = 1 // Check every hour
) {
    private var expirationJob: Job? = null

    /**
     * Start the expiration task
     */
    fun start(scope: CoroutineScope) {
        if (expirationJob?.isActive == true) {
            println("PostingExpirationTask already running")
            return
        }

        expirationJob = scope.launch {
            while (isActive) {
                try {
                    println("Running posting expiration task...")
                    val expiredCount = postingDao.markExpiredPostings()
                    if (expiredCount > 0) {
                        println("Marked $expiredCount postings as expired")
                    }
                } catch (e: Exception) {
                    println("Error during posting expiration check: ${e.message}")
                    e.printStackTrace()
                }

                // Wait for the next check cycle
                delay(intervalHours.hours)
            }
        }

        println("PostingExpirationTask started (interval: ${intervalHours}h)")
    }

    /**
     * Stop the expiration task
     */
    fun stop() {
        expirationJob?.cancel()
        expirationJob = null
        println("PostingExpirationTask stopped")
    }
}
