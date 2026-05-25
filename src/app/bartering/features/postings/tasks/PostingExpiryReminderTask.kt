package app.bartering.features.postings.tasks

import app.bartering.features.postings.service.PostingExpiryReminderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class PostingExpiryReminderTask(
    private val reminderService: PostingExpiryReminderService,
    private val intervalMinutes: Long = DEFAULT_INTERVAL_MINUTES
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    fun start(scope: CoroutineScope) {
        scope.launch {
            log.info("Starting PostingExpiryReminderTask (runs every {} minutes)", intervalMinutes)
            while (true) {
                try {
                    val sentCount = reminderService.processDueReminders()
                    if (sentCount > 0) {
                        log.info("PostingExpiryReminderTask sent {} reminder notification(s)", sentCount)
                    } else {
                        log.debug("PostingExpiryReminderTask complete: no reminders due")
                    }
                } catch (e: Exception) {
                    log.error("Error while processing posting expiry reminders", e)
                }

                delay(intervalMinutes * 60 * 1000)
            }
        }
    }

    companion object {
        private val DEFAULT_INTERVAL_MINUTES: Long =
            System.getenv("POSTING_EXPIRY_REMINDER_INTERVAL_MINUTES")?.toLongOrNull()?.coerceAtLeast(30) ?: 360
    }
}
