# Firebase Push Service Implementation

## Overview

The FirebasePushService is a complete implementation of the PushNotificationService interface using Firebase Cloud Messaging (FCM). It supports sending push notifications to Android, iOS, and Web platforms with advanced features like batch sending, topic messaging, and automatic token validation.

## Features

✅ **Multi-platform Support**: Android, iOS, and Web push notifications
✅ **Batch Sending**: Efficiently send to up to 500 tokens per batch
✅ **Topic Messaging**: Pub/sub model for broadcasting to groups
✅ **Token Management**: Automatic validation and cleanup of invalid tokens
✅ **Priority Handling**: Support for HIGH, NORMAL, and LOW priority messages
✅ **Platform-specific Configs**: Customizable Android and iOS notification settings
✅ **Error Handling**: Comprehensive Firebase error code handling
✅ **Health Checks**: Service status monitoring

## Configuration

### Firebase Credentials

The service requires a Firebase Admin SDK service account JSON file. By default, it looks for:
- **File**: `barter-app-backend-dev-firebase-adminsdk-fbsvc-393197c88a.json`
- **Location**: Project root directory

You can customize the location using environment variables:

```bash
# Optional: Custom path to credentials
export FIREBASE_CREDENTIALS_PATH="/path/to/credentials"

# Optional: Custom filename
export FIREBASE_CREDENTIALS_FILE="my-firebase-credentials.json"

# Optional: Choose push provider (default: firebase)
export PUSH_PROVIDER="firebase"
```

### Koin Dependency Injection

The service is automatically registered in the `notificationsModule`:

```kotlin
single<PushNotificationService> {
    val provider = System.getenv("PUSH_PROVIDER") ?: "firebase"
    when (provider.lowercase()) {
        "firebase" -> FirebasePushService()
        "onesignal" -> OneSignalPushService(...)
        else -> throw IllegalArgumentException("Unsupported push provider: $provider")
    }
}
```

## Usage Examples

### 1. Send Push to Specific Tokens

```kotlin
val pushService: PushNotificationService by inject(PushNotificationService::class.java)

val notification = PushNotification(
    tokens = listOf("fcm-token-1", "fcm-token-2"),
    notification = NotificationData(
        title = "New Message",
        body = "You have a new message from John",
        imageUrl = "https://example.com/image.png",
        actionUrl = "barter://chat/user123",
        data = mapOf(
            "type" to "new_message",
            "senderId" to "user123"
        )
    ),
    priority = NotificationPriority.HIGH,
    sound = "default",
    channelId = "chat_messages",
    badge = 1
)

val result = pushService.sendPushNotification(notification)
if (result.success) {
    println("✅ Push sent! Message ID: ${result.messageId}")
} else {
    println("❌ Failed: ${result.errorMessage}")
}
```

### 2. Send to User (Automatic Token Lookup)

```kotlin
// Automatically looks up user's push tokens from database
val result = pushService.sendToUser(
    userId = "user123",
    notification = PushNotification(
        tokens = emptyList(), // Will be populated automatically
        notification = NotificationData(
            title = "Match Found!",
            body = "You have a new match for your posting",
            data = mapOf("type" to "match", "matchId" to "match456")
        ),
        priority = NotificationPriority.HIGH
    )
)
```

### 3. Topic Messaging (Broadcast)

```kotlin
// Subscribe users to a topic
pushService.subscribeToTopic(
    tokens = listOf("token1", "token2", "token3"),
    topic = "new_postings"
)

// Send to all subscribers of a topic
pushService.sendToTopic(
    topic = "new_postings",
    notification = PushNotification(
        tokens = emptyList(),
        notification = NotificationData(
            title = "New Posting Available",
            body = "Check out the latest items in your area"
        )
    )
)
```

### 4. Batch Send (Multiple Recipients)

```kotlin
// Efficiently send to many users at once (max 500 per batch)
val tokens = listOf("token1", "token2", ..., "token500")
val result = pushService.sendPushNotification(
    PushNotification(
        tokens = tokens,
        notification = NotificationData(
            title = "System Announcement",
            body = "New features are now available!"
        )
    )
)

println("Success: ${result.metadata["totalSuccess"]}, Failed: ${result.metadata["totalFailed"]}")
```

### 5. Token Validation & Cleanup

```kotlin
// Validate a single token
val isValid = pushService.validateToken("fcm-token", PushPlatform.ANDROID)

// Clean up all invalid tokens for a user
val removedCount = pushService.cleanupInvalidTokens("user123")
println("Removed $removedCount invalid tokens")
```

### 6. Health Check

```kotlin
val isHealthy = pushService.healthCheck()
if (!isHealthy) {
    println("⚠️ Firebase service is unavailable")
}
```

## Integration with Chat (Offline Messages)

The FirebasePushService is integrated into ChatRoutes to notify users when they receive messages while offline:

```kotlin
// In ChatRoutes.kt
val stored = offlineMessageDao.storeOfflineMessage(offlineMessage)
if (stored) {
    // Send push notification to offline recipient
    cleanupScope.launch {
        try {
            val senderName = usersDao.getProfile(currentUserId)?.name ?: "Someone"
            pushNotificationService.sendToUser(
                userId = clientMessage.data.recipientId,
                notification = PushNotification(
                    tokens = emptyList(),
                    notification = NotificationData(
                        title = "New message from $senderName",
                        body = "You have a new encrypted message",
                        data = mapOf(
                            "type" to "new_message",
                            "senderId" to currentUserId,
                            "messageId" to offlineMessage.id
                        )
                    ),
                    priority = NotificationPriority.HIGH,
                    sound = "default",
                    channelId = "chat_messages"
                )
            )
            println("✅ Push notification sent to offline user")
        } catch (e: Exception) {
            println("⚠️ Failed to send push notification: ${e.message}")
        }
    }
}
```

## Integration with Notifications Package

The service works seamlessly with the NotificationOrchestrator:

```kotlin
val orchestrator: NotificationOrchestrator by inject(NotificationOrchestrator::class.java)

// Send via all enabled channels (email + push)
orchestrator.sendNotification(
    userId = "user123",
    notification = NotificationData(
        title = "Wishlist Match Found",
        body = "Someone has what you're looking for!",
        actionUrl = "barter://matches/match123"
    ),
    category = NotificationCategory.WISHLIST_MATCH
)
```

## Platform-Specific Features

### Android

```kotlin
PushNotification(
    tokens = listOf("android-token"),
    notification = NotificationData(
        title = "Title",
        body = "Body"
    ),
    channelId = "high_priority_channel", // Android notification channel
    sound = "custom_sound.mp3",
    priority = NotificationPriority.HIGH, // Maps to Android HIGH priority
    collapseKey = "message_group", // Group similar messages
    ttl = 3600 // Time to live in seconds
)
```

### iOS (APNs)

```kotlin
PushNotification(
    tokens = listOf("ios-token"),
    notification = NotificationData(
        title = "Title",
        body = "Body"
    ),
    badge = 5, // App badge count
    sound = "default.caf", // iOS sound file
    category = "MESSAGE_CATEGORY", // iOS notification category
    mutableContent = true, // Allow notification modification
    contentAvailable = true // Background notification
)
```

### Web

```kotlin
PushNotification(
    tokens = listOf("web-token"),
    notification = NotificationData(
        title = "Title",
        body = "Body",
        imageUrl = "https://example.com/icon.png",
        actionUrl = "https://example.com/action"
    )
)
```

## Error Handling

The service handles Firebase-specific errors gracefully:

| Error Code | Description | Action |
|------------|-------------|--------|
| `INVALID_ARGUMENT` | Invalid message parameters | Check notification format |
| `UNREGISTERED` | Token is invalid/unregistered | Remove token from database |
| `SENDER_ID_MISMATCH` | Token doesn't match project | Verify Firebase config |
| `QUOTA_EXCEEDED` | Rate limit exceeded | Implement backoff/retry |
| `UNAVAILABLE` | FCM service unavailable | Retry later |
| `THIRD_PARTY_AUTH_ERROR` | APNs certificate error | Check iOS config |

Example error handling:

```kotlin
val result = pushService.sendPushNotification(notification)
if (!result.success) {
    when {
        result.errorMessage?.contains("UNREGISTERED") == true -> {
            // Token is invalid, remove it
            result.failedRecipients.forEach { token ->
                preferencesDao.removePushToken(userId, token)
            }
        }
        result.errorMessage?.contains("QUOTA_EXCEEDED") == true -> {
            // Rate limited, schedule retry
            scheduleRetry(notification)
        }
        else -> {
            // Log error for investigation
            logger.error("Push failed: ${result.errorMessage}")
        }
    }
}
```

## Performance Considerations

### Batch Sending

- FCM supports up to **500 tokens per batch**
- The service automatically chunks large token lists
- Batch API is more efficient than individual sends

```kotlin
// Automatically batched if tokens > 500
val largeTokenList = List(1000) { "token-$it" }
val result = pushService.sendPushNotification(
    PushNotification(
        tokens = largeTokenList,
        notification = NotificationData(...)
    )
)
// Result includes batch metadata
```

### Token Cleanup

Regular cleanup prevents sending to invalid tokens:

```kotlin
// Schedule periodic cleanup (e.g., daily job)
suspend fun cleanupAllUsers() {
    val userIds = getAllUserIds()
    userIds.forEach { userId ->
        val removed = pushService.cleanupInvalidTokens(userId)
        if (removed > 0) {
            println("Cleaned up $removed invalid tokens for user $userId")
        }
    }
}
```

## Testing

### Test Notification Send

```kotlin
// Send a test notification
@Test
fun testPushNotification() = runTest {
    val service = FirebasePushService()
    
    val result = service.sendPushNotification(
        PushNotification(
            tokens = listOf("test-token"),
            notification = NotificationData(
                title = "Test",
                body = "This is a test notification"
            )
        )
    )
    
    assertTrue(result.success || result.errorMessage != null)
}
```

### Dry Run Mode

Firebase supports dry run mode for testing without actually sending:

```kotlin
// In FirebasePushService.validateToken()
FirebaseMessaging.getInstance().send(message, true) // true = dry run
```

## Monitoring & Logging

The service includes comprehensive logging:

```
✅ Firebase Admin SDK initialized successfully
✅ Push notification sent to offline user user123
⚠️ Failed to send push notification: Token is invalid
❌ Firebase health check failed: Service unavailable
```

Use these logs for:
- Debugging integration issues
- Monitoring success rates
- Identifying invalid tokens
- Tracking service health

## Security Considerations

1. **Credentials Security**: Never commit the Firebase JSON credentials to version control
2. **Token Storage**: Store FCM tokens securely in the database
3. **User Privacy**: Only send notifications to users who have opted in
4. **Data Encryption**: Sensitive data should be encrypted in notification payloads
5. **Rate Limiting**: Implement app-level rate limiting to prevent abuse

## Future Enhancements

Potential improvements:

- [ ] Analytics integration for delivery tracking
- [ ] A/B testing support for notification content
- [ ] Advanced scheduling with time zones
- [ ] Rich media notifications (videos, carousels)
- [ ] Two-way messaging for interactive notifications
- [ ] Integration with Firebase Cloud Functions for triggers

## References

- [Firebase Admin SDK Documentation](https://firebase.google.com/docs/admin/setup)
- [FCM Architecture Overview](https://firebase.google.com/docs/cloud-messaging)
- [FCM Best Practices](https://firebase.google.com/docs/cloud-messaging/concept-options)
- [Firebase Console](https://console.firebase.google.com)

## Support

For issues or questions:
1. Check Firebase service status
2. Verify credentials are correctly configured
3. Review logs for specific error messages
4. Test with Firebase Console's test notification feature
5. Consult Firebase documentation for error codes

---

**Last Updated**: December 29, 2025  
**Version**: 1.0.0  
**Dependencies**: `firebase-admin:9.3.0`
