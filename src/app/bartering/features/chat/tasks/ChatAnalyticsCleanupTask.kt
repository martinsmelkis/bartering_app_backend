package app.bartering.features.chat.tasks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.bartering.features.chat.dao.ChatAnalyticsDao
import org.slf4j.LoggerFactory

/**
 * Background task to periodically clean up old chat analytics data
 * Runs every [intervalHours] hours and deletes records older than [retentionDays] days
 */
class ChatAnalyticsCleanupTask(
    private val chatAnalyticsDao: ChatAnalyticsDao,
    private val intervalHours: Long = 24,
    private val retentionDays: Int = 90
) {
    private val log = LoggerFactory.getLogger(this::class.java)
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                try {
                    val deleted = chatAnalyticsDao.deleteOldResponseTimes(retentionDays)
                    log.info("Chat analytics cleanup: Deleted {} old response time records", deleted)
                } catch (e: Exception) {
                    log.error("Error during chat analytics cleanup", e)
                }
                delay(intervalHours * 60 * 60 * 1000) // Convert hours to milliseconds
            }
        }
    }
}
