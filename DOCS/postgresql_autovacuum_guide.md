# PostgreSQL Autovacuum: Complete Guide

## What is Autovacuum?

Autovacuum is PostgreSQL's **automatic maintenance daemon** that runs in the background to keep your
database healthy and performant. It performs two critical operations:

1. **VACUUM** - Reclaims storage and prevents transaction ID wraparound
2. **ANALYZE** - Updates table statistics for the query planner

## Why Does PostgreSQL Need Vacuum?

PostgreSQL uses a **Multi-Version Concurrency Control (MVCC)** system. This means:

### The Problem: Dead Tuples

```
UPDATE users SET name = 'John' WHERE id = 1;
```

**What actually happens:**

1. PostgreSQL doesn't modify the old row in place
2. It creates a NEW version of the row with `name = 'John'`
3. The OLD row is marked as "dead" but **NOT deleted immediately**
4. Both versions exist on disk until VACUUM cleans up

**Example visualization:**

```
Disk:
[Row v1: id=1, name='Jane'] ← Dead tuple (no longer visible)
[Row v2: id=1, name='John'] ← Current version
```

### Consequences Without Vacuum:

1. **Disk Bloat** - Dead tuples accumulate, wasting disk space
2. **Performance Degradation** - Queries scan dead tuples, slowing down
3. **Index Bloat** - Indexes reference dead tuples, growing unnecessarily
4. **Transaction ID Wraparound** - After ~2 billion transactions, database shuts down!

## What Autovacuum Does

### 1. VACUUM Operations

**Reclaims Dead Tuples:**

```sql
-- Your app does:
DELETE FROM user_attributes WHERE user_id = 'abc123';
UPDATE user_profiles SET name = 'Bob' WHERE user_id = 'xyz789';

-- Autovacuum automatically runs (behind the scenes):
VACUUM user_attributes;
VACUUM user_profiles;
```

**Results:**

- Dead tuple space is marked as reusable
- Table size stops growing uncontrollably
- Query performance stays consistent

### 2. ANALYZE Operations

**Updates Statistics:**

```sql
-- After 1000 new rows are inserted into attributes table:
-- Autovacuum runs:
ANALYZE attributes;
```

**What statistics are collected:**

- Row count
- Column value distributions (histograms)
- Most common values
- NULL percentages
- Average row width
- Correlation between physical and logical order

**How the query planner uses these:**

```sql
SELECT * FROM user_attributes 
WHERE relevancy > 0.9;
```

Query planner decides:

- "Only 5% of rows have relevancy > 0.9" → Use index scan
- "95% of rows have relevancy > 0.9" → Use sequential scan (faster)

**Without accurate statistics:**

- Wrong index chosen → Slow query
- Sequential scan when index scan is better → Slow query
- Bad join order → Exponentially slower queries

## Use Cases for Your Barter App

### 1. User Attributes Table

```kotlin
// Your code:
override suspend fun updateProfile(userId: String, request: UserProfileUpdateRequest) {
    // Inserts/updates user_attributes
    request.attributes?.forEach { attr ->
        UserAttributesTable.insertIgnore { ... }
    }
}
```

**Autovacuum helps:**

- Users frequently update their skills/interests
- Old attribute versions create dead tuples
- Autovacuum reclaims space after updates
- ANALYZE keeps statistics current for semantic similarity searches

### 2. User Profiles Location Updates

```kotlin
// Users move or update location frequently:
table[location] = Point(request.longitude, request.latitude)
```

**Without autovacuum:**

- Each location update creates a dead tuple
- PostGIS spatial index (GIST) becomes bloated
- `ST_DWithin()` searches become slower
- Disk space grows rapidly

**With autovacuum:**

- Dead tuples cleaned regularly
- Spatial index stays efficient
- `getNearbyProfiles()` stays fast

### 3. Offline Messages Cleanup

```sql
-- Messages marked as delivered:
UPDATE offline_messages SET delivered = true WHERE recipient_id = ?;
-- Later deleted:
DELETE FROM offline_messages WHERE delivered = true AND timestamp < NOW() - INTERVAL '7 days';
```

**Autovacuum benefit:**

- High churn table (constant inserts/deletes)
- Without vacuum: Table bloats to 10x size
- With vacuum: Table stays at optimal size

### 4. User Postings Table

```kotlin
// Postings expire or get fulfilled:
UPDATE user_postings SET status = 'expired' WHERE expires_at < NOW();
UPDATE user_postings SET status = 'fulfilled' WHERE id = ?;
```

**Critical for:**

- HNSW vector index on `embedding` column
- Vector indexes are very sensitive to bloat
- Autovacuum keeps vector searches fast
- Your semantic posting searches depend on this

### 5. Search Performance (Your Optimization!)

```kotlin
// Your optimized searchProfilesByKeyword function:
WITH matching_attributes AS (
    SELECT * FROM attributes 
    WHERE custom_user_attr_text % ?  -- Uses GIN index
)
```

**Why accurate statistics matter:**

- Query planner decides: "Use GIN index or sequential scan?"
- Outdated stats: "Table has 100 rows" → Sequential scan chosen
- Current stats: "Table has 100,000 rows" → GIN index chosen
- **Your pg_trgm performance depends on this!**

## Autovacuum Configuration

### Default Settings (Usually Good)

```sql
-- Check current autovacuum settings:
SELECT name, setting, unit, context 
FROM pg_settings 
WHERE name LIKE 'autovacuum%';
```

### Key Parameters

#### 1. When Autovacuum Triggers

```sql
-- VACUUM triggers when:
-- dead_tuples > (autovacuum_vacuum_threshold + autovacuum_vacuum_scale_factor * table_rows)

autovacuum_vacuum_threshold = 50        -- Base threshold
autovacuum_vacuum_scale_factor = 0.2    -- 20% of table size

-- Example for 1000-row table:
-- Triggers at: 50 + (0.2 * 1000) = 250 dead tuples
```

#### 2. When ANALYZE Triggers

```sql
autovacuum_analyze_threshold = 50       -- Base threshold
autovacuum_analyze_scale_factor = 0.1   -- 10% of table size

-- Example for 1000-row table:
-- Triggers at: 50 + (0.1 * 1000) = 150 modified rows
```

#### 3. Resource Limits

```sql
autovacuum_max_workers = 3              -- Parallel autovacuum workers
autovacuum_work_mem = 1GB              -- Memory per worker
autovacuum_naptime = 1min              -- Time between autovacuum runs
```

### Per-Table Tuning (For High-Traffic Tables)

```sql
-- Make autovacuum more aggressive for offline_messages:
ALTER TABLE offline_messages SET (
    autovacuum_vacuum_scale_factor = 0.05,  -- 5% instead of 20%
    autovacuum_vacuum_threshold = 100,
    autovacuum_analyze_scale_factor = 0.05
);

-- Make autovacuum less aggressive for rarely-changed tables:
ALTER TABLE categories SET (
    autovacuum_vacuum_scale_factor = 0.4,   -- 40% instead of 20%
    autovacuum_analyze_scale_factor = 0.2
);
```

## When to Use Manual VACUUM/ANALYZE

### 1. After Bulk Data Loading

```sql
-- You just inserted 10,000 attributes from a migration:
INSERT INTO attributes (attribute_key, localization_key, ...) 
SELECT ... FROM staging_table;

-- Immediately update statistics:
ANALYZE attributes;
```

### 2. After Major Deletions

```sql
-- Deleted 50% of expired postings:
DELETE FROM user_postings WHERE status = 'expired';

-- Reclaim space immediately:
VACUUM user_postings;
-- Or more aggressive (rewrites table):
VACUUM FULL user_postings;  -- CAUTION: Locks table!
```

### 3. Before Critical Operations

```sql
-- Before running expensive analytical queries:
ANALYZE user_attributes;
ANALYZE user_profiles;
ANALYZE user_semantic_profiles;

-- Then run your complex query
```

### 4. Transaction ID Wraparound Prevention

```sql
-- Check age of oldest transaction:
SELECT datname, age(datfrozenxid) 
FROM pg_database 
ORDER BY age(datfrozenxid) DESC;

-- If age > 200 million, vacuum immediately:
VACUUM FREEZE;
```

## Monitoring Autovacuum

### Check Last Autovacuum Times

```sql
SELECT 
    schemaname,
    relname,
    last_vacuum,
    last_autovacuum,
    last_analyze,
    last_autoanalyze,
    n_dead_tup,
    n_live_tup,
    n_tup_ins,
    n_tup_upd,
    n_tup_del
FROM pg_stat_user_tables
WHERE schemaname = 'public'
ORDER BY n_dead_tup DESC;
```

### Check Autovacuum Activity

```sql
-- See currently running autovacuum:
SELECT 
    pid,
    usename,
    query_start,
    state,
    query
FROM pg_stat_activity
WHERE query LIKE '%autovacuum%'
    AND query NOT LIKE '%pg_stat_activity%';
```

### Check Table Bloat

```sql
-- Estimate table bloat:
SELECT
    schemaname,
    tablename,
    pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) as size,
    n_dead_tup,
    n_live_tup,
    ROUND(n_dead_tup::numeric / NULLIF(n_live_tup, 0) * 100, 2) as dead_tuple_percent
FROM pg_stat_user_tables
WHERE n_dead_tup > 0
ORDER BY n_dead_tup DESC;
```

## Best Practices for Your App

### 1. Trust Autovacuum for Normal Operations

```kotlin
// Your updateProfile function:
override suspend fun updateProfile(...) {
    UserProfilesTable.upsert { ... }
    // No need to call ANALYZE here
    // Autovacuum will handle it
}
```

### 2. Manual ANALYZE After Migrations

```sql
-- V2__Add_more_attributes.sql
INSERT INTO attributes (attribute_key, ...) VALUES
    ('skill_carpentry', ...),
    ('skill_plumbing', ...),
    ... -- 500 more rows
;

-- Force statistics update:
ANALYZE attributes;
```

### 3. Monitor High-Churn Tables

```kotlin
// Create admin endpoint to check table health:
@GET
@Path("/admin/db-health")
suspend fun getDatabaseHealth(): Response {
    val stats = dbQuery {
        // Query pg_stat_user_tables
    }
    return Response.ok(stats).build()
}
```

### 4. Tune Per-Table Settings

```sql
-- After observing patterns, tune:
ALTER TABLE user_postings SET (
    autovacuum_vacuum_scale_factor = 0.1  -- More frequent vacuum
);

ALTER TABLE user_semantic_profiles SET (
    autovacuum_analyze_scale_factor = 0.05  -- Keep stats very current
);
```

## Common Issues and Solutions

### Issue 1: "Autovacuum is too slow"

**Symptoms:** Growing table sizes, increasing query times

**Solution:**

```sql
-- Make it more aggressive:
ALTER SYSTEM SET autovacuum_naptime = '30s';  -- Default: 1min
ALTER SYSTEM SET autovacuum_max_workers = 5;  -- Default: 3
SELECT pg_reload_conf();
```

### Issue 2: "Autovacuum is consuming too many resources"

**Symptoms:** High CPU/disk I/O during autovacuum

**Solution:**

```sql
-- Throttle it:
ALTER SYSTEM SET autovacuum_vacuum_cost_delay = '20ms';  -- Default: 2ms
ALTER SYSTEM SET autovacuum_vacuum_cost_limit = 200;     -- Default: -1 (use vacuum_cost_limit)
SELECT pg_reload_conf();
```

### Issue 3: "Query planner is making bad decisions"

**Symptoms:** Wrong index chosen, slow queries

**Solution:**

```sql
-- Force analyze:
ANALYZE user_attributes;

-- Check if statistics are stale:
SELECT 
    schemaname,
    tablename,
    last_autoanalyze,
    n_mod_since_analyze
FROM pg_stat_user_tables
WHERE schemaname = 'public'
ORDER BY n_mod_since_analyze DESC;
```

### Issue 4: "Table won't shrink despite VACUUM"

**Symptoms:** Disk space not reclaimed

**Explanation:** VACUUM marks space as reusable but doesn't return it to OS

**Solution:**

```sql
-- Option A: VACUUM FULL (locks table):
VACUUM FULL user_postings;  -- Use during maintenance window

-- Option B: pg_repack (no locks, requires extension):
CREATE EXTENSION pg_repack;
pg_repack -t user_postings;
```

## For Your Barter App: Recommendations

### 1. Let Autovacuum Handle Most Tables

```sql
-- Default settings are fine for:
- user_registration_data (low churn)
- categories (rarely changes)
- user_relationships (moderate churn)
```

### 2. Tune High-Frequency Tables

```sql
-- Aggressive settings for:
ALTER TABLE user_attributes SET (
    autovacuum_vacuum_scale_factor = 0.1,
    autovacuum_analyze_scale_factor = 0.05
);

ALTER TABLE user_postings SET (
    autovacuum_vacuum_scale_factor = 0.1,
    autovacuum_analyze_scale_factor = 0.05
);

ALTER TABLE offline_messages SET (
    autovacuum_vacuum_scale_factor = 0.05,  -- Very aggressive
    autovacuum_vacuum_threshold = 50
);
```

### 3. Monitor Weekly

```sql
-- Add to cron job or scheduled task:
SELECT 
    tablename,
    last_autoanalyze,
    n_dead_tup,
    n_live_tup
FROM pg_stat_user_tables
WHERE n_dead_tup > 1000
ORDER BY n_dead_tup DESC;
```

### 4. Manual ANALYZE After Bulk Operations

```kotlin
// In your AttributesDao:
suspend fun bulkImportAttributes(attributes: List<Attribute>) {
    dbQuery {
        // Insert all attributes
        attributes.forEach { ... }
        
        // Update statistics
        exec("ANALYZE attributes")
    }
}
```

## Summary

**Autovacuum is essential because:**

1. ✅ Prevents disk bloat from dead tuples
2. ✅ Keeps query performance consistent
3. ✅ Updates statistics for optimal query plans
4. ✅ Prevents transaction ID wraparound
5. ✅ Maintains index efficiency (including your GIN and HNSW indexes)

**For your app:**

- Trust autovacuum for normal operations
- Manual ANALYZE after migrations/bulk loads
- Monitor high-churn tables (offline_messages, user_postings)
- Tune per-table settings for critical tables
- Your pg_trgm search optimization depends on current statistics!

**Bottom line:** Autovacuum is like garbage collection for your database. Without it, PostgreSQL
would slow down and eventually stop working. It's not optional—it's fundamental to PostgreSQL's MVCC
architecture.
