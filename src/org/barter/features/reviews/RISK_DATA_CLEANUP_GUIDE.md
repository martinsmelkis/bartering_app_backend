# Risk Tracking Data Cleanup Implementation

## Overview

The **RiskTrackingCleanupTask** automatically cleans up old risk tracking data to:
- ✅ Maintain database performance
- ✅ Comply with privacy regulations (GDPR, data retention policies)
- ✅ Prevent unbounded storage growth
- ✅ Respect user privacy

## What Gets Cleaned Up

### 1. Device Tracking Data
- **Table**: `review_device_tracking`
- **Retention**: 90 days
- **Purpose**: Removes old device fingerprints and user agent data

### 2. IP Tracking Data
- **Table**: `review_ip_tracking`
- **Retention**: 90 days
- **Purpose**: Removes old IP addresses and geolocation data

### 3. Location Change History
- **Table**: `user_location_changes`
- **Retention**: 90 days
- **Purpose**: Removes old location change records

### 4. Risk Pattern Records
- **Table**: `risk_patterns`
- **Retention**: 180 days (kept longer for trend analysis)
- **Purpose**: Removes old detected risk patterns

## Configuration

### Default Settings

```kotlin
val cleanupTask = RiskTrackingCleanupTask(
    riskPatternDao = riskPatternDao,
    intervalHours = 24,              // Run daily
    deviceRetentionDays = 90,        // Keep device data 90 days
    ipRetentionDays = 90,            // Keep IP data 90 days
    locationRetentionDays = 90,      // Keep location data 90 days
    riskPatternRetentionDays = 180   // Keep patterns 180 days
)
```

### Custom Retention Periods

You can customize retention periods based on your requirements:

```kotlin
// More aggressive cleanup (60 days)
val cleanupTask = RiskTrackingCleanupTask(
    riskPatternDao = riskPatternDao,
    deviceRetentionDays = 60,
    ipRetentionDays = 60,
    locationRetentionDays = 60,
    riskPatternRetentionDays = 120
)

// Longer retention for compliance (1 year)
val cleanupTask = RiskTrackingCleanupTask(
    riskPatternDao = riskPatternDao,
    deviceRetentionDays = 365,
    ipRetentionDays = 365,
    locationRetentionDays = 365,
    riskPatternRetentionDays = 365
)

// More frequent cleanup (every 12 hours)
val cleanupTask = RiskTrackingCleanupTask(
    riskPatternDao = riskPatternDao,
    intervalHours = 12
)
```

## How It Works

### Startup

The cleanup task is automatically started in `Application.kt`:

```kotlin
val riskPatternDao: RiskPatternDao by inject(RiskPatternDao::class.java)
val riskCleanupTask = RiskTrackingCleanupTask(riskPatternDao)
riskCleanupTask.start(GlobalScope)
```

### Execution Schedule

1. **First Run**: Immediately after application starts
2. **Subsequent Runs**: Every 24 hours (configurable)
3. **Background**: Runs in a coroutine, doesn't block the application

### Cleanup Process

For each table:
1. Calculate cutoff date (current time - retention days)
2. Delete all records older than cutoff date
3. Log results (number of records deleted)
4. Continue to next table if one fails

### Example Output

```
🧹 Starting risk tracking data cleanup...
   ✓ Deleted 1,234 old device tracking records
   ✓ Deleted 987 old IP tracking records
   ✓ Deleted 456 old location change records
   ✓ Deleted 123 old risk pattern records
✅ Risk tracking cleanup complete: Deleted 2,800 records in 847ms
```

If no old records exist:
```
✅ Risk tracking cleanup complete: No old records to delete
```

## Database Impact

### Performance

- **Queries**: Uses indexed `timestamp` columns for fast deletion
- **Locks**: Minimal - deletes are transaction-based
- **Load**: Very low - runs once per day during off-peak hours

### Storage Savings

Based on typical usage patterns:

| Users | Daily Records | 90-Day Storage | Savings/Cleanup |
|-------|---------------|----------------|-----------------|
| 1,000 | ~500 | ~45,000 records | ~500 records/day |
| 10,000 | ~5,000 | ~450,000 records | ~5,000 records/day |
| 100,000 | ~50,000 | ~4.5M records | ~50,000 records/day |

### Index Maintenance

The cleanup helps maintain index efficiency:
- Smaller indexes = faster lookups
- Less fragmentation
- Better query plan optimization

## Privacy & Compliance

### GDPR Compliance

The cleanup task helps comply with GDPR requirements:
- ✅ **Data Minimization**: Only keep data as long as necessary
- ✅ **Storage Limitation**: Automatic deletion after retention period
- ✅ **Purpose Limitation**: Risk data only used for fraud detection
- ✅ **Accountability**: Documented retention policies

### Right to Be Forgotten

When a user requests deletion:
1. User account is deleted (CASCADE)
2. All related risk tracking data is automatically deleted
3. Cleanup task removes any orphaned records

### Data Retention Policy

Recommended policy text:
> "Risk analysis data (device fingerprints, IP addresses, location changes) 
> is retained for 90 days for fraud detection purposes. This data is 
> automatically deleted after the retention period."

## Monitoring

### Manual Check

To check how much data will be cleaned:

```sql
-- Check old device tracking records
SELECT COUNT(*) FROM review_device_tracking 
WHERE timestamp < NOW() - INTERVAL '90 days';

-- Check old IP tracking records
SELECT COUNT(*) FROM review_ip_tracking 
WHERE timestamp < NOW() - INTERVAL '90 days';

-- Check old location changes
SELECT COUNT(*) FROM user_location_changes 
WHERE changed_at < NOW() - INTERVAL '90 days';

-- Check old risk patterns
SELECT COUNT(*) FROM risk_patterns 
WHERE detected_at < NOW() - INTERVAL '180 days';
```

### Logs

Check application logs for cleanup results:
```bash
grep "Risk tracking cleanup" application.log
```

### Metrics to Monitor

- **Records deleted per run**: Should be stable over time
- **Cleanup duration**: Should be < 1 second for most cases
- **Errors**: Should be zero or rare

## Troubleshooting

### No Records Being Deleted

**Possible causes**:
1. Retention period too long (all data is recent)
2. No risk tracking data being generated
3. Cleanup task not running

**Solution**:
```kotlin
// Temporarily reduce retention for testing
val cleanupTask = RiskTrackingCleanupTask(
    riskPatternDao = riskPatternDao,
    deviceRetentionDays = 1  // Delete data older than 1 day
)
```

### Too Many Records Being Deleted

**Possible causes**:
1. Retention period too short
2. Large backlog of old data (initial cleanup)

**Solution**:
- Increase retention periods
- Initial cleanup will be large, subsequent runs will be smaller

### Cleanup Errors

If cleanup fails for a specific table:
- Other tables will still be cleaned
- Error is logged but doesn't crash the application
- Next run will retry

**Check logs**:
```
⚠️ Failed to cleanup device tracking: <error message>
```

## Manual Cleanup

If you need to manually clean up data:

```kotlin
// In a route or migration
suspend fun manualCleanup() {
    val riskPatternDao: RiskPatternDao by inject(RiskPatternDao::class.java)
    
    val deletedDevices = riskPatternDao.cleanupOldDeviceTracking(90)
    val deletedIps = riskPatternDao.cleanupOldIpTracking(90)
    val deletedLocations = riskPatternDao.cleanupOldLocationChanges(90)
    val deletedPatterns = riskPatternDao.cleanupOldRiskPatterns(180)
    
    println("Cleaned up: $deletedDevices + $deletedIps + $deletedLocations + $deletedPatterns = ${deletedDevices + deletedIps + deletedLocations + deletedPatterns} records")
}
```

Or directly in SQL:
```sql
-- Manual cleanup (90 days)
DELETE FROM review_device_tracking WHERE timestamp < NOW() - INTERVAL '90 days';
DELETE FROM review_ip_tracking WHERE timestamp < NOW() - INTERVAL '90 days';
DELETE FROM user_location_changes WHERE changed_at < NOW() - INTERVAL '90 days';
DELETE FROM risk_patterns WHERE detected_at < NOW() - INTERVAL '180 days';
```

## Best Practices

### Retention Periods

- **Device/IP/Location**: 90 days is a good balance
  - Too short: May miss long-term fraud patterns
  - Too long: Unnecessary privacy risk and storage

- **Risk Patterns**: 180+ days recommended
  - Useful for trend analysis
  - Less sensitive than raw tracking data

### Scheduling

- **Daily cleanup**: Good for most applications
- **Hourly cleanup**: Only needed for high-volume systems
- **Weekly cleanup**: Acceptable for low-traffic apps

### Testing

Before deploying to production:

```kotlin
// Test with short retention
val testCleanup = RiskTrackingCleanupTask(
    riskPatternDao = riskPatternDao,
    deviceRetentionDays = 1,
    ipRetentionDays = 1,
    locationRetentionDays = 1,
    riskPatternRetentionDays = 1
)
testCleanup.start(testScope)
```

Verify:
1. Records are deleted correctly
2. Application performance is unaffected
3. No errors in logs

## Summary

✅ **Implemented**: Automatic cleanup task for all risk tracking tables  
✅ **Configurable**: Retention periods and cleanup frequency  
✅ **Automatic**: Runs in background without intervention  
✅ **Safe**: Errors don't crash application, only log warnings  
✅ **Privacy-Focused**: Helps comply with data retention policies  
✅ **Performance**: Keeps database lean and fast

The cleanup task is now **fully operational** and will run automatically!
