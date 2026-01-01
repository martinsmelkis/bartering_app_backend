package org.barter.features.notifications.utils

import org.barter.features.notifications.model.NotificationData

/**
 * Helper object to build standardized notification data
 * Ensures all notifications include the proper fields expected by Flutter client
 */
object NotificationDataBuilder {
    
    /**
     * Build notification for new chat message
     * 
     * Flutter expects:
     * - type: "new_message"
     * - senderId: String
     * - senderName: String (optional but nice to have)
     * - messageId: String
     */
    fun newMessage(
        senderId: String,
        senderName: String,
        messageId: String,
        timestamp: Long
    ): NotificationData {
        return NotificationData(
            title = "New message from $senderName",
            body = "You have a new encrypted message",
            actionUrl = "barter://chat/$senderId",
            data = mapOf(
                "type" to "new_message",
                "senderId" to senderId,
                "senderName" to senderName,
                "messageId" to messageId,
                "timestamp" to timestamp.toString()
            )
        )
    }
    
    /**
     * Build notification for match found
     * 
     * Flutter expects:
     * - type: "match" or "wishlist_match"
     * - matchId: String
     * - postingId: String (optional)
     * - postingUserId: String (optional)
     */
    fun match(
        matchId: String,
        matchReason: String,
        postingId: String? = null,
        postingUserId: String? = null,
        postingTitle: String? = null,
        postingImageUrl: String? = null,
        matchScore: Double? = null,
        matchType: String = "match"
    ): NotificationData {
        val data = mutableMapOf(
            "type" to matchType, // "match" or "wishlist_match"
            "matchId" to matchId
        )
        
        postingId?.let { data["postingId"] = it }
        postingUserId?.let { data["postingUserId"] = it }
        postingTitle?.let { data["postingTitle"] = it }
        matchScore?.let { data["matchScore"] = it.toString() }
        
        return NotificationData(
            title = "New Match Found! 🎉",
            body = matchReason,
            imageUrl = postingImageUrl,
            actionUrl = "barter://matches/$matchId",
            data = data
        )
    }
    
    /**
     * Build notification for new posting
     * 
     * Flutter expects:
     * - type: "new_posting"
     * - postingId: String
     * - userId: String (owner)
     */
    fun newPosting(
        postingId: String,
        userId: String,
        title: String,
        description: String,
        imageUrl: String? = null
    ): NotificationData {
        return NotificationData(
            title = "New Posting: $title",
            body = description.take(100) + if (description.length > 100) "..." else "",
            imageUrl = imageUrl,
            actionUrl = "barter://postings/$postingId",
            data = mapOf(
                "type" to "new_posting",
                "postingId" to postingId,
                "userId" to userId,
                "postingTitle" to title
            )
        )
    }
    
    /**
     * Build notification for connection request
     */
    fun connectionRequest(
        requesterId: String,
        requesterName: String,
        requestId: String
    ): NotificationData {
        return NotificationData(
            title = "New Connection Request",
            body = "$requesterName wants to connect with you",
            actionUrl = "barter://connections/$requestId",
            data = mapOf(
                "type" to "connection_request",
                "requesterId" to requesterId,
                "requesterName" to requesterName,
                "requestId" to requestId
            )
        )
    }
    
    /**
     * Build notification for posting comment
     */
    fun postingComment(
        postingId: String,
        postingTitle: String,
        commenterId: String,
        commenterName: String,
        commentId: String,
        commentText: String
    ): NotificationData {
        return NotificationData(
            title = "New Comment on \"$postingTitle\"",
            body = "$commenterName: ${commentText.take(50)}...",
            actionUrl = "barter://postings/$postingId",
            data = mapOf(
                "type" to "posting_comment",
                "postingId" to postingId,
                "postingTitle" to postingTitle,
                "commenterId" to commenterId,
                "commenterName" to commenterName,
                "commentId" to commentId
            )
        )
    }
    
    /**
     * Build notification for system update
     */
    fun systemUpdate(
        title: String,
        body: String,
        actionUrl: String? = null
    ): NotificationData {
        return NotificationData(
            title = title,
            body = body,
            actionUrl = actionUrl,
            data = mapOf(
                "type" to "system_update"
            )
        )
    }
    
    /**
     * Build custom notification with full control
     */
    fun custom(
        type: String,
        title: String,
        body: String,
        imageUrl: String? = null,
        actionUrl: String? = null,
        additionalData: Map<String, String> = emptyMap()
    ): NotificationData {
        val data = mutableMapOf("type" to type)
        data.putAll(additionalData)
        
        return NotificationData(
            title = title,
            body = body,
            imageUrl = imageUrl,
            actionUrl = actionUrl,
            data = data
        )
    }
}
