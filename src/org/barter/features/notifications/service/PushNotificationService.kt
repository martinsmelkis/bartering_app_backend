package org.barter.features.notifications.service

import org.barter.features.notifications.model.PushNotification
import org.barter.features.notifications.model.NotificationResult
import org.barter.features.notifications.model.PushPlatform

/**
 * Push notification service interface
 * 
 * Implementations should support popular push providers:
 * - Firebase Cloud Messaging (FCM)
 * - AWS SNS
 * - Apple Push Notification service (APNs)
 * - Web Push API
 */
interface PushNotificationService {
    
    /**
     * Send push notification to specific tokens
     */
    suspend fun sendPushNotification(notification: PushNotification): NotificationResult
    
    /**
     * Send push to multiple tokens (batch)
     */
    suspend fun sendBulkPushNotifications(notifications: List<PushNotification>): List<NotificationResult>
    
    /**
     * Send push to a topic/channel (pub/sub model)
     */
    suspend fun sendToTopic(
        topic: String,
        notification: PushNotification
    ): NotificationResult
    
    /**
     * Send push to specific user ID (looks up user's tokens)
     */
    suspend fun sendToUser(
        userId: String,
        notification: PushNotification
    ): NotificationResult
    
    /**
     * Subscribe user's token to a topic
     */
    suspend fun subscribeToTopic(tokens: List<String>, topic: String): NotificationResult
    
    /**
     * Unsubscribe user's token from a topic
     */
    suspend fun unsubscribeFromTopic(tokens: List<String>, topic: String): NotificationResult
    
    /**
     * Validate if a push token is valid
     */
    suspend fun validateToken(token: String, platform: PushPlatform): Boolean
    
    /**
     * Remove invalid/expired tokens from database
     */
    suspend fun cleanupInvalidTokens(userId: String): Int
    
    /**
     * Check if push provider is healthy
     */
    suspend fun healthCheck(): Boolean
}
