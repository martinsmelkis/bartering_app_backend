package app.bartering.features.chat.tasks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.bartering.features.chat.dao.ChatAnalyticsDao

/**
 * Background task to periodically clean up old chat analytics data
 * Runs every [intervalHours] hours and deletes records older than [retentionDays] days
 */
class ChatAnalyticsCleanupTask(
    private val chatAnalyticsDao: ChatAnalyticsDao,
    private val intervalHours: Long = 24,
    private val retentionDays: Int = 90
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                try {
                    val deleted = chatAnalyticsDao.deleteOldResponseTimes(retentionDays)
                    println("🧹 Chat analytics cleanup: Deleted $deleted old response time records")
                } catch (e: Exception) {
                    println("❌ Error during chat analytics cleanup: ${e.message}")
                    e.printStackTrace()
                }
                delay(intervalHours * 60 * 60 * 1000) // Convert hours to milliseconds
            }
        }
    }
}
