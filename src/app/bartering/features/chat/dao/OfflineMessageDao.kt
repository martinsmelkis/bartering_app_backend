package app.bartering.features.chat.dao

import app.bartering.features.chat.model.OfflineMessageDto

/**
 * Interface for offline message data access operations
 */
interface OfflineMessageDao {
    /**
     * Store a message for offline delivery
     */
    suspend fun storeOfflineMessage(message: OfflineMessageDto): Boolean

    /**
     * Get all pending offline messages for a specific user
     */
    suspend fun getPendingMessages(userId: String): List<OfflineMessageDto>

    /**
     * Mark a message as delivered
     */
    suspend fun markAsDelivered(messageId: String): Boolean

    /**
     * Delete old delivered messages (cleanup)
     */
    suspend fun deleteDeliveredMessages(olderThanDays: Int = 7): Int
}
