## Notifications Infrastructure

A flexible, provider-agnostic notification system supporting multiple email and push notification services.

## 🚀 Quick Start - Firebase Push Notifications

**Firebase Push Service is FULLY IMPLEMENTED and READY TO USE!**

- **Implementation**: `service/impl/FirebasePushService.kt`
- **Credentials**: `barter-app-backend-dev-firebase-adminsdk-fbsvc-393197c88a.json` (configured at project root)
- **Quick Start Guide**: [DOCS/FIREBASE_PUSH_QUICK_START.md](../../../DOCS/FIREBASE_PUSH_QUICK_START.md)
- **Full Documentation**: [DOCS/FIREBASE_PUSH_SERVICE_IMPLEMENTATION.md](../../../DOCS/FIREBASE_PUSH_SERVICE_IMPLEMENTATION.md)

### Test It Now

```bash
# Health check
curl http://localhost:8081/api/v1/push/health

# Send test notification to user
curl -X POST http://localhost:8081/api/v1/push/send-to-user \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "your-user-id",
    "notification": {
      "title": "Test",
      "body": "Hello from Barter!"
    }
  }'
```

### Already Integrated
- ✅ **ChatRoutes**: Automatically sends push notifications for offline messages
- ✅ **NotificationOrchestrator**: Coordinates push and email notifications
- ✅ **API Routes**: `/api/v1/push/*` endpoints for testing and management

## 📧 Quick Start - AWS SES Email Service

**AWS SES Service is FULLY IMPLEMENTED and READY TO USE!**

- **Implementation**: `service/impl/AwsSesEmailService.kt`
- **SDK**: `software.amazon.awssdk:ses:2.25.0`
- **Cost-effective**: 3,000 emails/day free tier, $0.10/1,000 emails thereafter

### Setup

```bash
# Set environment variables
export EMAIL_PROVIDER=aws_ses
export AWS_REGION=us-east-1  # or your preferred region

# Option 1: Use AWS access keys (for development)
export AWS_ACCESS_KEY_ID=your_access_key_id
export AWS_SECRET_ACCESS_KEY=your_secret_access_key

# Option 2: Use IAM role (recommended for production on EC2/ECS)

# Optionally specify verified sender email
export AWS_SES_FROM_EMAIL=noreply@barter.app

# Optionally specify configuration set for tracking
export AWS_SES_CONFIGURATION_SET=production-config
```

### Verify Email/Domain (Required for Sandbox Mode)

AWS SES requires verified sender emails/domains. In sandbox mode, recipients must also be verified.

```kotlin
// Verify email sender
val emailService: AwsSesEmailService by inject()
emailService.verifyEmailIdentity("noreply@barter.app")

// Verify domain (recommended for production)
emailService.verifyDomainIdentity("barter.app")
```

After verifying a domain, add these DNS records:
- TXT: `_amazonses` with verification token
- DKIM: 3 CNAME records provided by SES

### API Usage Examples

```kotlin
// Health check
val isHealthy = emailService.healthCheck()

// Check send quota
val quota = emailService.getSendQuota()
println("24h limit: ${quota.max24HourSend()}, Sent: ${quota.sentLast24Hours()}")

// Check if identity is verified
val isVerified = emailService.isIdentityVerified("noreply@barter.app")
```

### Test It Now

```bash
# Health check endpoint (if you add one)
curl http://localhost:8081/api/v1/notifications/email/health

# Send test email
curl -X POST http://localhost:8081/api/v1/notifications/email/send \
  -H "Content-Type: application/json" \
  -d '{
    "to": ["user@example.com"],
    "from": "noreply@barter.app",
    "subject": "Test Email",
    "htmlBody": "<h1>Hello from Barter!</h1>",
    "textBody": "Hello from Barter!"
  }'
```

### Important Notes

1. **Sandbox Mode**: New AWS SES accounts start in sandbox mode. You can only send to verified email addresses. Request production access to send to any email.

2. **Configuration Sets**: For advanced tracking (opens, clicks, bounces), create a configuration set in the AWS SES console and link it to SNS.

3. **Templates**: Create email templates in the AWS SES console before using `sendTemplatedEmail()`.

4. **Rate Limits**: SES enforces rate limits. Use `getSendQuota()` to monitor your limits.

## Features

### 📧 Email Support
- **SendGrid** - Transactional email with templates
- **AWS SES** - High-deliverability Amazon service
- **Mailgun** - Email API (stub for future)
- **SMTP** - Generic SMTP support (stub for future)

### 📱 Push Notification Support
- **Firebase Cloud Messaging (FCM)** - ✅ **FULLY IMPLEMENTED** - Cross-platform (Android/iOS/Web)
- **OneSignal** - Unified push API with analytics (stub for future)
- **AWS SNS** - Amazon push service (stub for future)
- **Apple APNs** - Direct iOS push (stub for future)

### 🎯 Key Features
- Provider-agnostic interfaces
- Easy provider switching via configuration
- User notification preferences
- Category-based notification control
- Quiet hours support
- Batch sending support
- Template support
- Delivery tracking

## Architecture

```
┌─────────────────────────────────────────────────────┐
│           NotificationOrchestrator                  │
│  (Routes notifications based on user preferences)   │
└──────────────┬────────────────────┬─────────────────┘
               │                    │
      ┌────────▼────────┐   ┌──────▼──────────┐
      │  EmailService   │   │ PushNotification│
      │   Interface     │   │    Service      │
      └────────┬────────┘   └──────┬──────────┘
               │                    │
     ┌─────────┼─────────┐         │
     │         │         │          │
┌────▼───┐ ┌──▼────┐ ┌──▼───┐ ┌───▼──────┐
│SendGrid│ │AWS SES│ │SMTP  │ │ Firebase │
└────────┘ └───────┘ └──────┘ │ OneSignal│
                               └──────────┘
```

## Data Models

### NotificationData
Core notification structure:
```kotlin
data class NotificationData(
    val title: String,
    val body: String,
    val imageUrl: String? = null,
    val actionUrl: String? = null,
    val data: Map<String, String> = emptyMap()
)
```

### EmailNotification
Comprehensive email configuration:
```kotlin
data class EmailNotification(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val from: String,
    val subject: String,
    val htmlBody: String? = null,
    val textBody: String? = null,
    val templateId: String? = null,
    val attachments: List<EmailAttachment> = emptyList()
)
```

### PushNotification
Cross-platform push configuration:
```kotlin
data class PushNotification(
    val tokens: List<String>,
    val notification: NotificationData,
    val platform: PushPlatform? = null,
    val priority: NotificationPriority = NORMAL,
    val sound: String? = null,
    val badge: Int? = null
)
```

### UserNotificationPreferences
User-specific settings:
```kotlin
data class UserNotificationPreferences(
    val userId: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val emailEnabled: Boolean = true,
    val pushTokens: List<PushToken> = emptyList(),
    val pushEnabled: Boolean = true,
    val categories: Map<String, CategoryPreference>
)
```

## Interfaces

### EmailService
```kotlin
interface EmailService {
    suspend fun sendEmail(email: EmailNotification): NotificationResult
    suspend fun sendBulkEmails(emails: List<EmailNotification>): List<NotificationResult>
    suspend fun sendTemplatedEmail(to, templateId, data): NotificationResult
    suspend fun sendVerificationEmail(email, token, url): NotificationResult
    suspend fun healthCheck(): Boolean
}
```

### PushNotificationService
```kotlin
interface PushNotificationService {
    suspend fun sendPushNotification(notification: PushNotification): NotificationResult
    suspend fun sendBulkPushNotifications(notifications: List): List<NotificationResult>
    suspend fun sendToTopic(topic: String, notification: PushNotification): NotificationResult
    suspend fun sendToUser(userId: String, notification: PushNotification): NotificationResult
    suspend fun subscribeToTopic(tokens, topic): NotificationResult
    suspend fun validateToken(token, platform): Boolean
    suspend fun healthCheck(): Boolean
}
```

## Configuration

### Environment Variables

```bash
# Email Provider Selection
EMAIL_PROVIDER=sendgrid  # Options: sendgrid, aws_ses, smtp
SENDGRID_API_KEY=your_api_key

# OR for AWS SES
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=your_access_key
AWS_SECRET_ACCESS_KEY=your_secret_key

# Push Provider Selection
PUSH_PROVIDER=firebase  # Options: firebase, onesignal, aws_sns
# Firebase uses existing firebase-admin setup

```

### Application Integration

Add to `Application.kt`:
```kotlin
install(Koin) {
    modules(
        authenticationModule,
        profilesModule,
        notificationsModule,  // ← Add this
        wishlistModule
    )
}
```

## Usage Examples

### Send Simple Notification

```kotlin
val orchestrator: NotificationOrchestrator by inject()

orchestrator.sendNotification(
    userId = "user123",
    notification = NotificationData(
        title = "New Match Found!",
        body = "Check out this vintage bicycle",
        actionUrl = "/postings/456"
    ),
    category = NotificationCategory.WISHLIST_MATCH
)
```

### Send Email Only

```kotlin
val emailService: EmailService by inject()

emailService.sendEmail(
    EmailNotification(
        to = listOf("user@example.com"),
        from = "noreply@barter.app",
        subject = "Welcome to Barter App",
        htmlBody = "<h1>Welcome!</h1>"
    )
)
```

### Send Push Only

```kotlin
val pushService: PushNotificationService by inject()

pushService.sendPushNotification(
    PushNotification(
        tokens = listOf("fcm_token_123"),
        notification = NotificationData(
            title = "New Message",
            body = "You have a new message"
        ),
        priority = NotificationPriority.HIGH
    )
)
```

### Send with Template

```kotlin
emailService.sendTemplatedEmail(
    to = listOf("user@example.com"),
    templateId = "welcome_template",
    templateData = mapOf(
        "user_name" to "John",
        "activation_link" to "https://..."
    )
)
```

## Wishlist Integration

The `WishlistNotificationService` provides wishlist-specific notifications:

```kotlin
val wishlistNotifications: WishlistNotificationService by inject()

// Send match notification
wishlistNotifications.sendWishlistMatchNotification(
    userId = userId,
    match = match,
    wishlistItem = wishlistItem,
    posting = posting
)

// Send daily digest
wishlistNotifications.sendDailyMatchDigest(
    userId = userId,
    matches = listOf(
        Triple(match, wishlistItem, posting)
    )
)
```

### Integration with WishlistMatchingService

Update `WishlistMatchingService.kt`:

```kotlin
class WishlistMatchingService(
    private val wishlistDao: WishlistDao,
    private val postingDao: UserPostingDao,
    private val notificationService: WishlistNotificationService  // Add this
) {
    suspend fun checkPostingAgainstWishlists(posting: UserPosting): List<WishlistMatch> {
        // ... matching logic ...
        
        if (matchScore >= threshold) {
            val match = wishlistDao.recordMatch(...)
            
            // Send notification
            notificationService.sendWishlistMatchNotification(
                userId = wishlist.userId,
                match = match,
                wishlistItem = wishlist,
                posting = posting
            )
        }
    }
}
```

## Notification Categories

Pre-defined categories for user preferences:

- `WISHLIST_MATCH` - Wishlist item matches
- `NEW_MESSAGE` - Chat messages
- `CONNECTION_REQUEST` - Friend/connection requests
- `POSTING_COMMENT` - Comments on postings
- `SYSTEM_UPDATE` - System announcements
- `MARKETING` - Promotional content

Users can enable/disable notifications per category.

## Provider Implementation Status

| Provider | Status | SDK Required |
|----------|--------|--------------|
| **Email** | | |
| SendGrid | ⚠️ Stub | `com.sendgrid:sendgrid-java` |
| AWS SES | ✅ **IMPLEMENTED** | `software.amazon.awssdk:ses:2.25.0` (✅ in project) |
| Mailgun | 🚧 Stub | `com.mailgun:mailgun-java` |
| SMTP | 🚧 Stub | `javax.mail:mail` |
| **Push** | | |
| Firebase FCM | ✅ **IMPLEMENTED** | `com.google.firebase:firebase-admin` (✅ in project) |
| OneSignal | ❌ Removed | N/A |
| AWS SNS | 🚧 Stub | `software.amazon.awssdk:sns` |
| Apple APNs | 🚧 Stub | `com.eatthepath:pushy` |

## Next Steps

### To Complete Implementation:

1. **Implement DAO**
   ```kotlin
   class NotificationPreferencesDaoImpl : NotificationPreferencesDao {
       // Database operations for user preferences
   }
   ```

2. **Complete Provider Implementations**
   - Fill in TODO methods in provider classes
   - Add actual SDK calls

3. **Create Database Tables**
   ```sql
   CREATE TABLE user_notification_preferences (
       user_id VARCHAR(255) PRIMARY KEY,
       email VARCHAR(255),
       email_verified BOOLEAN,
       email_enabled BOOLEAN,
       push_enabled BOOLEAN,
       created_at TIMESTAMPTZ,
       updated_at TIMESTAMPTZ
   );
   
   CREATE TABLE user_push_tokens (
       id VARCHAR(36) PRIMARY KEY,
       user_id VARCHAR(255),
       token TEXT,
       platform VARCHAR(20),
       device_id VARCHAR(255),
       is_active BOOLEAN,
       created_at TIMESTAMPTZ
   );
   
   CREATE TABLE notification_category_preferences (
       user_id VARCHAR(255),
       category VARCHAR(50),
       enabled BOOLEAN,
       email_enabled BOOLEAN,
       push_enabled BOOLEAN,
       PRIMARY KEY (user_id, category)
   );
   ```

4. **Add API Routes** for managing preferences

5. **Testing**
   - Unit tests for each provider
   - Integration tests for orchestrator
   - End-to-end notification tests

## Security & Privacy

- Email addresses must be verified before sending
- Push tokens validated before use
- User preferences always respected
- Unsubscribe links in all emails
- GDPR-compliant data handling
- Rate limiting on notification sending

---

**Status**: ✅ AWS SES Full Implementation, ✅ Firebase Full Implementation  
**Version**: 2.0
**Last Updated**: December 31, 2025
