package app.bartering.features.chat.dao

import java.time.Instant

/**
 * Data Access Object interface for chat analytics operations
 */
interface ChatAnalyticsDao {
    /**
     * Records a response time between receiving a message and responding
     */
    suspend fun recordResponseTime(
        userId: String,
        conversationPartnerId: String,
        messageReceivedAt: Instant,
        responseSentAt: Instant
    ): Boolean

    /**
     * Gets the average response time for a user in hours
     * Calculates from the last 30 days of data
     * Returns null if user has no response time data
     */
    suspend fun getAverageResponseTime(userId: String, daysToConsider: Int = 30): Long?

    /**
     * Deletes old response time data (cleanup)
     */
    suspend fun deleteOldResponseTimes(olderThanDays: Int = 90): Int
}
