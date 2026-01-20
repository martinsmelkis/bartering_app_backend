# Inactive User Management Guide

## Overview

This guide explains how the Barter App handles inactive and deleted users to maintain a healthy, 
active user base while respecting user privacy and GDPR compliance.

## User Activity States

### Three-Tier Activity Model

```
┌──────────────┐
│    ACTIVE    │ ← Last seen 0-30 days
│  (Normal)    │   • Shows in all searches
│              │   • Full visibility
└──────┬───────┘   • Online boost applied
       │
       ↓ 30 days
┌──────────────┐
│  INACTIVE    │ ← Last seen 30-90 days
│  (Reduced)   │   • Shows in searches with lower priority
│              │   • Relevancy score penalty (-0.1)
└──────┬───────┘   • Visual indicator in UI
       │
       ↓ 90 days
┌──────────────┐
│   DORMANT    │ ← Last seen 90+ days
│  (Hidden)    │   • Hidden from all searches
│              │   • Postings marked inactive
└──────┬───────┘   • Account preserved
       │
       ↓ 180 days (optional)
┌──────────────┐
│AUTO-DELETED  │ ← Configurable threshold
│ (Optional)   │   • Full account deletion
└──────────────┘   • All data removed (GDPR)
```

## Implementation Architecture

### 1. Activity Tracking

**UserActivityCache** (In-Memory)
- Tracks last seen timestamp for each user
- Updates on every API request
- Syncs to database every 30 seconds
- Provides millisecond-precision activity data

**User Presence Table** (Database)
```sql
CREATE TABLE user_presence (
    user_id VARCHAR(255) PRIMARY KEY,
    last_activity_at TIMESTAMPTZ NOT NULL,
    last_activity_type VARCHAR(50),
    updated_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (user_id) REFERENCES user_registration_data(id) ON DELETE CASCADE
);
```

### 2. Activity Filtering

**UserActivityFilter** utility provides:
- Activity status detection (ACTIVE/INACTIVE/DORMANT/UNKNOWN)
- Search result filtering based on activity
- Relevancy score penalty for inactive users
- Batch status checking for performance

**Usage in Search Results:**

```kotlin
import app.bartering.features.profile.util.UserActivityFilter

// Filter out dormant users (90+ days inactive)
val activeProfiles = UserActivityFilter.filterByActivity(
    profiles = allProfiles,
    includeDormant = false,    // Hide dormant users
    includeInactive = true     // Show inactive users
)

// Or apply penalty instead of filtering
val rankedProfiles = UserActivityFilter.applyActivityPenalty(allProfiles)
```

### 3. Automated Cleanup

**InactiveUserCleanupTask** runs daily at 3 AM:

| Days Inactive | Action | Description |
|--------------|--------|-------------|
| 60 days | Reactivation Email | "We miss you!" notification |
| 120 days | Warning Email | Account inactivity notice |
| 180 days | Auto-Delete (Optional) | Permanent account deletion |

## Configuration

### Environment Variables

```bash
# Enable/disable auto-deletion of inactive users
INACTIVE_USER_AUTO_DELETE=false

# Days of inactivity before auto-deletion (default: 180)
INACTIVE_USER_AUTO_DELETE_THRESHOLD=180

# Days before sending reactivation email (default: 60)
INACTIVE_USER_REACTIVATION_EMAIL_DAYS=60

# Days before sending deletion warning (default: 120)
INACTIVE_USER_WARNING_EMAIL_DAYS=120
```

### Application Startup

Add to `Application.kt`:

```kotlin
import app.bartering.features.profile.tasks.InactiveUserCleanupTask
import app.bartering.features.notifications.service.NotificationOrchestrator

// In the configure() function:
val notificationOrchestrator: NotificationOrchestrator by inject()

val inactiveUserCleanup = InactiveUserCleanupTask(
    notificationOrchestrator = notificationOrchestrator,
    enableAutoDelete = System.getenv("INACTIVE_USER_AUTO_DELETE")?.toBoolean() ?: false,
    autoDeleteThresholdDays = System.getenv("INACTIVE_USER_AUTO_DELETE_THRESHOLD")?.toLong() ?: 180
)
inactiveUserCleanup.start()

// Register shutdown hook
environment.monitor.subscribe(ApplicationStopping) {
    inactiveUserCleanup.stop()
}
```

## Search Filtering Strategies

### Strategy 1: Hard Filter (Recommended for "Nearby Users")

**Use Case**: Geographic proximity searches where you want only active users.

```kotlin
override suspend fun getNearbyProfiles(...): List<UserProfileWithDistance> = dbQuery {
    // ... existing query logic ...
    
    // Filter out dormant users
    val filtered = UserActivityFilter.filterByActivity(
        profiles = results,
        includeDormant = false,    // Hide users 90+ days inactive
        includeInactive = true      // Still show 30-90 day inactive users
    )
    
    applyOnlineBoostAndStatus(filtered)
}
```

**Impact:**
- ✅ Cleaner search results
- ✅ Better user experience (seeing active users)
- ❌ May reduce total results in sparse areas

### Strategy 2: Soft Penalty (Recommended for "Skill Matching")

**Use Case**: Semantic matching where an inactive user might still be a perfect match.

```kotlin
override suspend fun getSimilarProfiles(...): List<UserProfileWithDistance> = dbQuery {
    // ... existing query logic ...
    
    // Apply penalty but don't filter out
    val penalized = UserActivityFilter.applyActivityPenalty(results)
    
    // Re-sort by adjusted scores
    val sorted = penalized.sortedByDescending { it.matchRelevancyScore }
    
    sorted
}
```

**Impact:**
- ✅ Inactive users still findable if good match
- ✅ Active users prioritized
- ✅ Maximum result diversity

### Strategy 3: Visual Indicators Only

**Use Case**: Show all users but indicate activity status in UI.

```kotlin
// Return activity status with profile
data class UserProfileWithDistance(
    val profile: UserProfile,
    val distanceKm: Double?,
    val matchRelevancyScore: Double?,
    val activityStatus: UserActivityFilter.ActivityStatus? = null  // Add this
)

// In your DAO:
results.map { profile ->
    profile.copy(
        activityStatus = UserActivityFilter.getActivityStatus(profile.profile.userId)
    )
}
```

**UI Display:**
```
[User Avatar]
John Doe ⚠️ Last seen 45 days ago
Guitar teacher | 5km away
```

## User Deletion

### User-Initiated Deletion (GDPR Compliant)

**Endpoint:** `DELETE /api/v1/authentication/user/{userId}`

**What gets deleted:**
1. User account (`user_registration_data`)
2. User profile (CASCADE)
3. All attributes (CASCADE)
4. All relationships (CASCADE)
5. All postings (CASCADE)
6. All offline messages (manual)
7. All encrypted files (manual)
8. Activity cache entry (manual)

**Documentation:** See `DOCS/USER_DELETION_API.md`

### Auto-Deletion (Optional, Disabled by Default)

Only runs if explicitly enabled:

```bash
INACTIVE_USER_AUTO_DELETE=true
INACTIVE_USER_AUTO_DELETE_THRESHOLD=180  # 6 months
```

**Process:**
1. User inactive for 60 days → Reactivation email sent
2. User inactive for 120 days → Final warning sent
3. User inactive for 180 days → Account auto-deleted
4. All data permanently removed

## Recommendations by Use Case

### For Barter/Trade Apps (Recommended)

**Goal:** Active, engaged community

```kotlin
// Nearby users: Only show active users
getNearbyProfiles() → filterByActivity(includeDormant = false, includeInactive = true)

// Skill matching: Soft penalty
getSimilarProfiles() → applyActivityPenalty()

// Auto-deletion: ENABLED after 6 months
enableAutoDelete = true
autoDeleteThresholdDays = 180
```

**Rationale:**
- Encourages active participation
- Prevents messaging inactive users
- Natural user base churn
- Reduces database bloat

### For Professional Networks (Alternative)

**Goal:** Preserve connections, long-term relationships

```kotlin
// All searches: Show everyone with visual indicators
getAllProfiles() → applyActivityPenalty() + include activityStatus

// Auto-deletion: DISABLED
enableAutoDelete = false
```

**Rationale:**
- Users may be seasonal
- Professional connections have long-term value
- Manual cleanup if needed

### For Marketplace Apps (Middle Ground)

**Goal:** Fresh listings, but preserve user accounts

```kotlin
// User searches: Include inactive with penalty
searchUsers() → applyActivityPenalty()

// Posting searches: Filter by posting activity (not user activity)
searchPostings() → filter(posting.expiresAt > now)

// Auto-deletion: DISABLED, but hide dormant postings
enableAutoDelete = false
// Separate task to expire old postings
```

## Performance Considerations

### Memory Usage

**UserActivityCache**:
- ~100 bytes per user (userId + timestamp + activity type)
- 10,000 users = ~1 MB
- 100,000 users = ~10 MB
- Auto-cleanup removes inactive entries after 30 minutes

### Database Impact

**Filtering overhead**: Minimal
- Activity data from in-memory cache (not DB query)
- O(n) complexity on result set (already small after DB filter)
- No additional database queries

**Cleanup task**: Lightweight
- Runs once per day at 3 AM (low traffic)
- Single query to get user activities
- Email sending is async

## Monitoring & Metrics

### Recommended Metrics

```kotlin
// Track in your metrics system
metrics.gauge("users.active", UserActivityFilter.countActive())
metrics.gauge("users.inactive", UserActivityFilter.countInactive())
metrics.gauge("users.dormant", UserActivityFilter.countDormant())

// Daily cleanup results
metrics.counter("inactive_cleanup.reactivation_emails_sent")
metrics.counter("inactive_cleanup.warning_emails_sent")
metrics.counter("inactive_cleanup.users_auto_deleted")
```

### Dashboard Queries

```sql
-- Current activity distribution
SELECT 
    CASE 
        WHEN last_activity_at > NOW() - INTERVAL '30 days' THEN 'active'
        WHEN last_activity_at > NOW() - INTERVAL '90 days' THEN 'inactive'
        ELSE 'dormant'
    END as status,
    COUNT(*) as count
FROM user_presence
GROUP BY status;

-- Users at risk of auto-deletion
SELECT user_id, last_activity_at,
    AGE(NOW(), last_activity_at) as inactive_duration
FROM user_presence
WHERE last_activity_at < NOW() - INTERVAL '150 days'
ORDER BY last_activity_at ASC;
```

## Testing

### Unit Tests

```kotlin
@Test
fun `test activity status detection`() {
    // Mock user last seen 45 days ago
    val status = UserActivityFilter.getActivityStatus("user123")
    assertEquals(ActivityStatus.INACTIVE, status)
}

@Test
fun `test dormant users filtered from search`() {
    val profiles = listOf(
        createProfile("active-user", daysAgo = 10),
        createProfile("dormant-user", daysAgo = 100)
    )
    
    val filtered = UserActivityFilter.filterByActivity(
        profiles, 
        includeDormant = false
    )
    
    assertEquals(1, filtered.size)
    assertEquals("active-user", filtered[0].profile.userId)
}
```

### Integration Tests

Test the cleanup task:

```kotlin
@Test
fun `test reactivation email sent at 60 days`() {
    // Create user with last activity 60 days ago
    createTestUser("user123", lastActivity = 60.days.ago)
    
    // Run cleanup task
    cleanupTask.processNow()
    
    // Verify email sent
    verify(notificationOrchestrator).sendNotification(
        userId = "user123",
        notification = any(),
        category = NotificationCategory.SYSTEM_UPDATE
    )
}
```

## Migration from Current System

### Step 1: Add Activity Filtering (Non-Breaking)

```kotlin
// Start by just tracking but not filtering
val activityStatus = UserActivityFilter.getActivityStatus(userId)
log.info("User {} is {}", userId, activityStatus)
```

### Step 2: Soft Rollout

```kotlin
// Add feature flag
if (featureFlags.isEnabled("filter_inactive_users")) {
    profiles = UserActivityFilter.filterByActivity(profiles, ...)
}
```

### Step 3: Enable Cleanup Task

```kotlin
// Start with notifications only (no auto-delete)
InactiveUserCleanupTask(
    notificationOrchestrator = orchestrator,
    enableAutoDelete = false  // Safe mode
)
```

### Step 4: Monitor & Adjust

- Watch metrics for 2-4 weeks
- Adjust thresholds based on user behavior
- Enable auto-delete if desired

## FAQ

### Q: Will this delete users who are seasonal?

A: No, not unless you enable auto-delete with a threshold (default: disabled). 
Even then, users get warnings at 60 and 120 days, allowing them to log in and reset the timer.

### Q: What if a user is inactive but has valuable connections?

A: Their connections are preserved even when hidden from searches. They can still message existing 
connections. If auto-delete is disabled, accounts persist indefinitely.

### Q: Can users opt out of auto-deletion?

A: Yes, add a user preference:

```kotlin
// In user settings
data class UserPreferences(
    val keepAccountActive: Boolean = false  // Exempt from auto-deletion
)
```

### Q: What about users who only use the app occasionally?

A: The 180-day threshold (6 months) is very generous. Users get 2 reminder emails. For seasonal users, 
consider disabling auto-delete or increasing threshold to 365 days.

---

## Summary

**Recommended Configuration for Barter App:**

✅ **Enable activity-based filtering**
- Hide dormant users (90+ days) from nearby searches
- Apply penalty to inactive users in skill matching

✅ **Enable notification emails**
- 60 days: Reactivation email
- 120 days: Warning email

❌ **Disable auto-deletion** (initially)
- Monitor user behavior for 3-6 months
- Adjust thresholds based on data
- Enable later if needed

This balanced approach:
- Improves search quality
- Re-engages inactive users
- Preserves user accounts
- Respects user privacy
- GDPR compliant

**Status:** ✅ Ready for implementation  
**Version:** 1.0  
**Last Updated:** January 20, 2026
