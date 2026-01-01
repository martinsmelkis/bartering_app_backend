-- ============================================================================
-- DATABASE MONITORING QUERIES FOR BARTER APP
-- ============================================================================
-- Collection of useful queries to monitor PostgreSQL health and performance
-- Run these periodically to ensure your database is healthy
-- ============================================================================

-- ============================================================================
-- 1. TABLE STATISTICS AND AUTOVACUUM STATUS
-- ============================================================================

-- Check last autovacuum/analyze times and dead tuple counts
-- Use this to see if autovacuum is keeping up
SELECT 
    schemaname,
    relname AS table_name,
    n_live_tup AS live_rows,
    n_dead_tup AS dead_rows,
    ROUND(n_dead_tup::numeric / NULLIF(n_live_tup, 0) * 100, 2) AS dead_row_percent,
    last_vacuum,
    last_autovacuum,
    last_analyze,
    last_autoanalyze,
    n_mod_since_analyze AS rows_modified_since_analyze,
    pg_size_pretty(pg_total_relation_size(schemaname||'.'||relname)) AS total_size
FROM pg_stat_user_tables
WHERE schemaname = 'public'
ORDER BY n_dead_tup DESC;

-- ============================================================================
-- 2. TABLE BLOAT ESTIMATE
-- ============================================================================

-- Estimate how much wasted space each table has
-- High bloat indicates vacuum isn't aggressive enough
SELECT
    schemaname,
    tablename,
    pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS total_size,
    pg_size_pretty(pg_relation_size(schemaname||'.'||tablename)) AS table_size,
    pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename) - pg_relation_size(schemaname||'.'||tablename)) AS index_size,
    n_dead_tup,
    n_live_tup,
    CASE 
        WHEN n_live_tup = 0 THEN 0
        ELSE ROUND(n_dead_tup::numeric / n_live_tup * 100, 2)
    END AS bloat_percent,
    CASE
        WHEN n_dead_tup > 10000 AND n_dead_tup::numeric / NULLIF(n_live_tup, 0) > 0.1 THEN '⚠️ HIGH BLOAT'
        WHEN n_dead_tup > 5000 AND n_dead_tup::numeric / NULLIF(n_live_tup, 0) > 0.05 THEN '⚡ MODERATE BLOAT'
        ELSE '✅ HEALTHY'
    END AS status
FROM pg_stat_user_tables
WHERE schemaname = 'public'
ORDER BY n_dead_tup DESC;

-- ============================================================================
-- 3. INDEX HEALTH AND USAGE
-- ============================================================================

-- Check which indexes are being used (or not)
-- Unused indexes waste space and slow down writes
SELECT
    schemaname,
    tablename,
    indexname,
    idx_scan AS index_scans,
    idx_tup_read AS tuples_read,
    idx_tup_fetch AS tuples_fetched,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size,
    CASE
        WHEN idx_scan = 0 THEN '❌ UNUSED'
        WHEN idx_scan < 100 THEN '⚠️ RARELY USED'
        ELSE '✅ ACTIVE'
    END AS usage_status
FROM pg_stat_user_indexes
WHERE schemaname = 'public'
ORDER BY idx_scan ASC, pg_relation_size(indexrelid) DESC;

-- Find duplicate or redundant indexes
-- (Indexes with the same column prefix)
SELECT
    a.tablename,
    a.indexname AS index_1,
    pg_get_indexdef(a.indexrelid) AS definition_1,
    b.indexname AS index_2,
    pg_get_indexdef(b.indexrelid) AS definition_2,
    pg_size_pretty(pg_relation_size(a.indexrelid) + pg_relation_size(b.indexrelid)) AS combined_size
FROM pg_stat_user_indexes a
JOIN pg_stat_user_indexes b ON 
    a.tablename = b.tablename AND 
    a.indexname < b.indexname AND
    a.schemaname = 'public' AND
    b.schemaname = 'public'
WHERE 
    pg_get_indexdef(a.indexrelid) LIKE pg_get_indexdef(b.indexrelid) || '%'
    OR pg_get_indexdef(b.indexrelid) LIKE pg_get_indexdef(a.indexrelid) || '%';

-- ============================================================================
-- 4. QUERY PERFORMANCE STATISTICS
-- ============================================================================

-- Find tables with most sequential scans (may need indexes)
-- High seq_scan with large tables indicates missing indexes
SELECT
    schemaname,
    relname AS table_name,
    seq_scan AS sequential_scans,
    idx_scan AS index_scans,
    n_live_tup AS row_count,
    CASE
        WHEN seq_scan = 0 THEN 0
        ELSE ROUND((seq_scan::numeric / (seq_scan + idx_scan)) * 100, 2)
    END AS seq_scan_percent,
    pg_size_pretty(pg_relation_size(schemaname||'.'||relname)) AS table_size,
    CASE
        WHEN n_live_tup > 10000 AND seq_scan > idx_scan THEN '⚠️ MAY NEED INDEX'
        WHEN n_live_tup > 1000 AND seq_scan > idx_scan * 10 THEN '❌ NEEDS INDEX'
        ELSE '✅ OK'
    END AS recommendation
FROM pg_stat_user_tables
WHERE schemaname = 'public'
ORDER BY seq_scan DESC
LIMIT 20;

-- ============================================================================
-- 5. AUTOVACUUM CONFIGURATION
-- ============================================================================

-- Check current autovacuum settings
SELECT 
    name,
    setting,
    unit,
    short_desc
FROM pg_settings
WHERE name LIKE 'autovacuum%'
ORDER BY name;

-- Check per-table autovacuum settings
SELECT
    n.nspname AS schema,
    c.relname AS table_name,
    COALESCE(c.reloptions, '{}'::text[]) AS table_options,
    pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size
FROM pg_class c
LEFT JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public'
    AND c.relkind = 'r'
    AND c.reloptions IS NOT NULL
ORDER BY c.relname;

-- ============================================================================
-- 6. ACTIVE AUTOVACUUM PROCESSES
-- ============================================================================

-- See currently running autovacuum and vacuum operations
SELECT
    pid,
    usename,
    datname,
    state,
    query_start,
    NOW() - query_start AS duration,
    CASE
        WHEN query LIKE '%autovacuum%' THEN 'AUTOVACUUM'
        WHEN query LIKE '%VACUUM%' THEN 'MANUAL VACUUM'
        WHEN query LIKE '%ANALYZE%' THEN 'ANALYZE'
        ELSE 'OTHER'
    END AS operation_type,
    query
FROM pg_stat_activity
WHERE 
    (query LIKE '%vacuum%' OR query LIKE '%ANALYZE%')
    AND query NOT LIKE '%pg_stat_activity%'
ORDER BY query_start;

-- ============================================================================
-- 7. TRANSACTION ID AGE (WRAPAROUND PREVENTION)
-- ============================================================================

-- Check transaction ID age to prevent wraparound
-- Age > 200,000,000 is concerning
-- Age > 1,000,000,000 is critical (database will shut down at 2 billion)
SELECT
    datname,
    age(datfrozenxid) AS xid_age,
    ROUND((age(datfrozenxid)::numeric / 2000000000) * 100, 2) AS percent_to_wraparound,
    CASE
        WHEN age(datfrozenxid) > 1000000000 THEN '🚨 CRITICAL - VACUUM IMMEDIATELY'
        WHEN age(datfrozenxid) > 500000000 THEN '⚠️ WARNING - SCHEDULE VACUUM'
        WHEN age(datfrozenxid) > 200000000 THEN '⚡ ATTENTION NEEDED'
        ELSE '✅ HEALTHY'
    END AS status
FROM pg_database
ORDER BY age(datfrozenxid) DESC;

-- Check per-table transaction ID age
SELECT
    schemaname,
    relname AS table_name,
    age(relfrozenxid) AS xid_age,
    pg_size_pretty(pg_total_relation_size(schemaname||'.'||relname)) AS total_size,
    last_vacuum,
    last_autovacuum,
    CASE
        WHEN age(relfrozenxid) > 1000000000 THEN '🚨 CRITICAL'
        WHEN age(relfrozenxid) > 500000000 THEN '⚠️ WARNING'
        WHEN age(relfrozenxid) > 200000000 THEN '⚡ ATTENTION'
        ELSE '✅ HEALTHY'
    END AS status
FROM pg_stat_user_tables
JOIN pg_class ON pg_class.relname = pg_stat_user_tables.relname
WHERE schemaname = 'public'
ORDER BY age(relfrozenxid) DESC;

-- ============================================================================
-- 8. DATABASE SIZE AND GROWTH
-- ============================================================================

-- Check overall database size
SELECT
    datname AS database_name,
    pg_size_pretty(pg_database_size(datname)) AS size,
    numbackends AS active_connections
FROM pg_database
WHERE datistemplate = false
ORDER BY pg_database_size(datname) DESC;

-- Check table sizes (largest first)
SELECT
    schemaname,
    tablename,
    pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS total_size,
    pg_size_pretty(pg_relation_size(schemaname||'.'||tablename)) AS table_size,
    pg_size_pretty(pg_indexes_size(schemaname||'.'||tablename)) AS index_size,
    n_live_tup AS row_count,
    CASE 
        WHEN n_live_tup > 0 THEN pg_relation_size(schemaname||'.'||tablename) / n_live_tup
        ELSE 0
    END AS bytes_per_row
FROM pg_stat_user_tables
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;

-- ============================================================================
-- 9. CACHE HIT RATIO (SHOULD BE > 99%)
-- ============================================================================

-- Check buffer cache hit ratio
-- Low hit ratio means PostgreSQL is reading from disk too much
SELECT
    'Buffer Cache Hit Ratio' AS metric,
    ROUND((sum(blks_hit) / NULLIF(sum(blks_hit + blks_read), 0)) * 100, 2) AS hit_ratio_percent,
    CASE
        WHEN (sum(blks_hit) / NULLIF(sum(blks_hit + blks_read), 0)) > 0.99 THEN '✅ EXCELLENT'
        WHEN (sum(blks_hit) / NULLIF(sum(blks_hit + blks_read), 0)) > 0.95 THEN '⚡ GOOD'
        WHEN (sum(blks_hit) / NULLIF(sum(blks_hit + blks_read), 0)) > 0.90 THEN '⚠️ NEEDS ATTENTION'
        ELSE '❌ POOR - INCREASE shared_buffers'
    END AS status,
    sum(blks_hit) AS buffer_hits,
    sum(blks_read) AS disk_reads
FROM pg_stat_database
WHERE datname = current_database();

-- Per-table cache hit ratio
SELECT
    schemaname,
    relname AS table_name,
    heap_blks_hit + idx_blks_hit AS total_cache_hits,
    heap_blks_read + idx_blks_read AS total_disk_reads,
    CASE 
        WHEN (heap_blks_hit + idx_blks_hit + heap_blks_read + idx_blks_read) = 0 THEN 0
        ELSE ROUND(((heap_blks_hit + idx_blks_hit)::numeric / 
                    NULLIF(heap_blks_hit + idx_blks_hit + heap_blks_read + idx_blks_read, 0)) * 100, 2)
    END AS cache_hit_ratio,
    pg_size_pretty(pg_total_relation_size(schemaname||'.'||relname)) AS size
FROM pg_statio_user_tables
WHERE schemaname = 'public'
    AND (heap_blks_hit + idx_blks_hit + heap_blks_read + idx_blks_read) > 0
ORDER BY (heap_blks_hit + idx_blks_hit + heap_blks_read + idx_blks_read) DESC
LIMIT 20;

-- ============================================================================
-- 10. MOST FREQUENTLY UPDATED TABLES
-- ============================================================================

-- Find tables with most write activity
-- These tables benefit most from aggressive autovacuum
SELECT
    schemaname,
    relname AS table_name,
    n_tup_ins AS inserts,
    n_tup_upd AS updates,
    n_tup_del AS deletes,
    n_tup_ins + n_tup_upd + n_tup_del AS total_writes,
    n_live_tup AS current_rows,
    ROUND((n_tup_upd + n_tup_del)::numeric / NULLIF(n_live_tup, 0), 2) AS churn_ratio,
    pg_size_pretty(pg_total_relation_size(schemaname||'.'||relname)) AS size,
    CASE
        WHEN (n_tup_upd + n_tup_del)::numeric / NULLIF(n_live_tup, 0) > 2.0 THEN '🔥 HIGH CHURN - TUNE AUTOVACUUM'
        WHEN (n_tup_upd + n_tup_del)::numeric / NULLIF(n_live_tup, 0) > 1.0 THEN '⚡ MODERATE CHURN'
        ELSE '✅ LOW CHURN'
    END AS recommendation
FROM pg_stat_user_tables
WHERE schemaname = 'public'
    AND n_live_tup > 0
ORDER BY (n_tup_ins + n_tup_upd + n_tup_del) DESC;

-- ============================================================================
-- 11. LONG-RUNNING QUERIES (POTENTIAL ISSUES)
-- ============================================================================

-- Find queries that have been running for a long time
-- Long-running transactions block autovacuum
SELECT
    pid,
    usename,
    datname,
    NOW() - query_start AS duration,
    state,
    wait_event_type,
    wait_event,
    LEFT(query, 100) AS query_preview,
    CASE
        WHEN NOW() - query_start > interval '1 hour' THEN '🚨 CRITICAL - CONSIDER TERMINATING'
        WHEN NOW() - query_start > interval '10 minutes' THEN '⚠️ WARNING - INVESTIGATE'
        WHEN NOW() - query_start > interval '1 minute' THEN '⚡ LONG RUNNING'
        ELSE '✅ NORMAL'
    END AS status
FROM pg_stat_activity
WHERE state != 'idle'
    AND query NOT LIKE '%pg_stat_activity%'
    AND datname = current_database()
ORDER BY query_start;

-- ============================================================================
-- 12. SPECIFIC MONITORING FOR YOUR BARTER APP
-- ============================================================================

-- Check health of critical tables for your search optimization
WITH table_health AS (
    SELECT
        relname AS table_name,
        n_live_tup,
        n_dead_tup,
        CASE 
            WHEN n_live_tup = 0 THEN 0
            ELSE ROUND(n_dead_tup::numeric / n_live_tup * 100, 2)
        END AS dead_row_percent,
        last_autoanalyze,
        n_mod_since_analyze,
        pg_size_pretty(pg_total_relation_size('public.'||relname)) AS size
    FROM pg_stat_user_tables
    WHERE schemaname = 'public'
        AND relname IN ('attributes', 'user_attributes', 'user_profiles', 
                        'user_semantic_profiles', 'user_postings')
)
SELECT
    table_name,
    n_live_tup AS rows,
    n_dead_tup AS dead_rows,
    dead_row_percent || '%' AS bloat,
    n_mod_since_analyze AS stale_rows,
    last_autoanalyze,
    size,
    CASE
        WHEN dead_row_percent > 10 OR n_mod_since_analyze > n_live_tup * 0.2 
            THEN '⚠️ NEEDS MAINTENANCE'
        ELSE '✅ HEALTHY'
    END AS status,
    CASE
        WHEN dead_row_percent > 10 THEN 'VACUUM ' || table_name || ';'
        WHEN n_mod_since_analyze > n_live_tup * 0.2 THEN 'ANALYZE ' || table_name || ';'
        ELSE ''
    END AS recommended_action
FROM table_health
ORDER BY 
    CASE WHEN dead_row_percent > 10 OR n_mod_since_analyze > n_live_tup * 0.2 THEN 0 ELSE 1 END,
    dead_row_percent DESC;

-- Check GIN index health (critical for your pg_trgm searches)
SELECT
    schemaname,
    tablename,
    indexname,
    idx_scan AS times_used,
    idx_tup_read AS tuples_read,
    idx_tup_fetch AS tuples_fetched,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size,
    pg_get_indexdef(indexrelid) AS definition
FROM pg_stat_user_indexes
WHERE indexname LIKE '%gin%'
    OR pg_get_indexdef(indexrelid) LIKE '%gin%'
ORDER BY idx_scan DESC;

-- Check HNSW vector index health (for your semantic searches)
SELECT
    schemaname,
    tablename,
    indexname,
    idx_scan AS times_used,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size,
    pg_get_indexdef(indexrelid) AS definition
FROM pg_stat_user_indexes
WHERE indexname LIKE '%hnsw%'
    OR pg_get_indexdef(indexrelid) LIKE '%hnsw%'
ORDER BY idx_scan DESC;

-- ============================================================================
-- USAGE INSTRUCTIONS
-- ============================================================================

/*
RECOMMENDED MONITORING SCHEDULE:

DAILY:
- Query #1: Table statistics (check for dead tuple buildup)
- Query #6: Active autovacuum processes
- Query #11: Long-running queries

WEEKLY:
- Query #2: Table bloat estimate
- Query #4: Sequential scan analysis
- Query #9: Cache hit ratio
- Query #12: Critical table health

MONTHLY:
- Query #3: Index usage (find unused indexes)
- Query #7: Transaction ID age
- Query #8: Database growth trends
- Query #10: Write activity analysis

AS NEEDED:
- Query #5: Check autovacuum configuration
- Run manual VACUUM/ANALYZE based on recommendations

AUTOMATION:
Consider creating a monitoring dashboard or scheduled job that runs
these queries and alerts on concerning values.
*/
