# Inactive User Management - Quick Start Guide

## 🚀 What's Implemented

The system now automatically manages inactive users with:
- ✅ Activity-based filtering in search results
- ✅ Automated email notifications (reactivation + warnings)
- ✅ Optional auto-deletion after configurable threshold
- ✅ Zero database schema changes required

## 📋 Quick Setup Checklist

### 1. Environment Variables (Optional)

Add to your `.env` file:

```bash
# Disable auto-deletion (recommended for initial deployment)
INACTIVE_USER_AUTO_DELETE=false

# Or enable with 6-month threshold
# INACTIVE_USER_AUTO_DELETE=true
# INACTIVE_USER_AUTO_DELETE_THRESHOLD=180
```

### 2. Verify Application Startup

Check logs for confirmation:

```
✅ User activity tracking initialized
✅ Inactive user cleanup task started (auto-delete: false, threshold: 180 days)
```

### 3. Test Activity Filtering

Run a nearby users search and verify dormant users are filtered out:

```bash
curl "http://localhost:8081/api/v1/profiles/nearby?lat=56.95&lon=24.10&radius=50000"
```

## 🎯 How It Works

### Activity States

```
ACTIVE (0-30 days)     → Shows normally in all searches
INACTIVE (30-90 days)  → Shows with -0.1 relevancy penalty  
DORMANT (90+ days)     → Hidden from nearby searches, soft penalty in keyword/skill searches
```

### Search Behavior

| Search Type | Dormant Users | Inactive Users | Strategy |
|-------------|---------------|----------------|----------|
| Nearby Users | ❌ Hidden | ✅ Shown | Hard filter |
| Similar Profiles | ⚠️ Penalty | ⚠️ Penalty | Soft penalty |
| Helpful Profiles | ⚠️ Penalty | ⚠️ Penalty | Soft penalty |
| Keyword Search | ⚠️ Penalty | ⚠️ Penalty | Soft penalty |

### Automated Notifications

| Days Inactive | Action |
|---------------|--------|
| 60 | "We miss you!" reactivation email |
| 120 | Account inactivity warning |
| 180 | Auto-delete (if enabled) |

## 🛠️ Manual Testing

### Test Activity Status Detection

```kotlin
import app.bartering.features.profile.util.UserActivityFilter

// Check a specific user's status
val status = UserActivityFilter.getActivityStatus("user-id-123")
println("User status: $status")  // ACTIVE, INACTIVE, DORMANT, or UNKNOWN

// Check days since last activity
val days = UserActivityFilter.getDaysSinceLastActivity("user-id-123")
println("Days inactive: $days")
```

### Test Search Filtering

```kotlin
import app.bartering.features.profile.util.UserActivityFilter

// Filter a list of profiles
val filtered = UserActivityFilter.filterByActivity(
    profiles = allProfiles,
    includeDormant = false,
    includeInactive = true
)

// Or apply soft penalty
val penalized = UserActivityFilter.applyActivityPenalty(allProfiles)
```

### Trigger Cleanup Task Manually

```kotlin
// For testing purposes only - normally runs automatically at 3 AM
val cleanupTask = InactiveUserCleanupTask(notificationOrchestrator, enableAutoDelete = false)
cleanupTask.processNow()
```

## 📊 Monitoring

### Check Activity Distribution

```sql
-- Current user activity breakdown
SELECT 
    CASE 
        WHEN last_activity_at > NOW() - INTERVAL '30 days' THEN 'active'
        WHEN last_activity_at > NOW() - INTERVAL '90 days' THEN 'inactive'
        ELSE 'dormant'
    END as status,
    COUNT(*) as count,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 2) as percentage
FROM user_presence
GROUP BY status
ORDER BY count DESC;
```

### Find Users at Risk

```sql
-- Users who will receive warnings/deletion soon
SELECT 
    user_id,
    last_activity_at,
    AGE(NOW(), last_activity_at) as inactive_duration,
    CASE 
        WHEN last_activity_at < NOW() - INTERVAL '120 days' THEN 'Will be warned next run'
        WHEN last_activity_at < NOW() - INTERVAL '60 days' THEN 'Will get reactivation email'
        ELSE 'Active enough'
    END as next_action
FROM user_presence
WHERE last_activity_at < NOW() - INTERVAL '60 days'
ORDER BY last_activity_at ASC
LIMIT 20;
```

### Monitor Cleanup Results

Check application logs for daily summaries:

```
Inactive user processing complete: 5 reactivation emails, 2 warnings, 0 auto-deleted
```

## 🔧 Configuration Options

### Disable Activity Filtering (Rollback)

If you need to temporarily disable activity filtering:

```kotlin
// In UserProfileDaoImpl.kt, comment out the filter:
// val activityFiltered = UserActivityFilter.filterByActivity(...)
// Use: val activityFiltered = blockedFiltered
```

### Change Activity Thresholds

Edit `UserActivityFilter.kt`:

```kotlin
const val ACTIVE_THRESHOLD_DAYS = 30L     // Change to 45L for more lenient
const val INACTIVE_THRESHOLD_DAYS = 90L   // Change to 120L for more lenient
```

### Customize Email Timing

Edit `InactiveUserCleanupTask.kt`:

```kotlin
private const val REACTIVATION_EMAIL_DAYS = 60L   // When to send "we miss you"
private const val WARNING_EMAIL_DAYS = 120L       // When to send final warning
```

## ⚠️ Important Notes

### Safe Defaults

The system is configured conservatively:
- ✅ Auto-deletion **DISABLED** by default
- ✅ Generous thresholds (30/90/180 days)
- ✅ Multiple warning emails before any action
- ✅ Soft penalties preserve search diversity

### User Impact

**What users will notice:**
- More relevant search results (active users prioritized)
- Possible email notifications if inactive for 60+ days
- No data loss unless auto-delete is explicitly enabled

**What users won't notice:**
- The filtering is transparent in the UI
- Their account persists indefinitely (if auto-delete is off)
- They can reactivate by simply logging in

### Performance

**Impact: Minimal**
- Activity data from in-memory cache (not database)
- Filtering happens on already-small result sets
- Cleanup task runs once per day at 3 AM (low traffic)
- No additional database queries during searches

## 📚 Full Documentation

For complete details, see:
- [`DOCS/INACTIVE_USER_MANAGEMENT.md`](./INACTIVE_USER_MANAGEMENT.md) - Complete guide
- [`DOCS/USER_DELETION_API.md`](./USER_DELETION_API.md) - User deletion endpoint

## 🆘 Troubleshooting

### "Users aren't being filtered"

1. Check that UserActivityCache is initialized:
   ```
   ✅ User activity tracking initialized
   ```

2. Verify users have activity data:
   ```sql
   SELECT * FROM user_presence LIMIT 10;
   ```

3. Check if filtering is actually happening (logs):
   ```
   Filtered out 3 inactive/dormant users from search results
   ```

### "Too many users being filtered"

Lower the thresholds to be more lenient:

```kotlin
// In UserActivityFilter.kt
const val ACTIVE_THRESHOLD_DAYS = 45L    // Was 30
const val INACTIVE_THRESHOLD_DAYS = 120L  // Was 90
```

### "Emails not being sent"

1. Verify NotificationOrchestrator is configured
2. Check email provider settings in `.env`
3. Look for errors in logs during cleanup task run

## ✅ Deployment Checklist

Before deploying to production:

- [ ] Reviewed `.env.example` and set appropriate values
- [ ] Confirmed `INACTIVE_USER_AUTO_DELETE=false` for initial deployment
- [ ] Tested search functionality with inactive test users
- [ ] Verified email notifications work (test in staging)
- [ ] Set up monitoring for activity distribution metrics
- [ ] Documented decision in team wiki/docs
- [ ] Planned review date (e.g., 3 months) to assess effectiveness

## 📈 Recommended Rollout Plan

### Week 1-2: Soft Launch
- Deploy with filtering **enabled**
- Auto-delete **disabled**
- Monitor user feedback and metrics

### Week 3-4: Email Testing
- Verify reactivation emails are being sent
- Monitor open rates and reactivation rates
- Adjust email copy if needed

### Month 3: Assessment
- Review activity distribution
- Analyze reactivation success rate
- Decide whether to enable auto-delete

### Month 6+: Steady State
- Auto-delete can be enabled if desired
- Thresholds adjusted based on user behavior
- Process is mature and automated

---

**Status:** ✅ Ready for Production  
**Last Updated:** January 20, 2026  
**Version:** 1.0
