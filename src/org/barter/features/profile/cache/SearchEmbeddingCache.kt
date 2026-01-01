package org.barter.features.profile.cache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Simple in-memory LRU cache for search query embeddings.
 *
 * This cache stores embeddings for popular/frequent search queries to avoid
 * repeated calls to the Ollama embedding service.
 *
 * Features:
 * - LRU (Least Recently Used) eviction policy
 * - Thread-safe operations using Mutex
 * - TTL (Time To Live) for cached entries
 * - Hit/miss statistics tracking
 * - Automatic size management
 */
class SearchEmbeddingCache(
    private val maxSize: Int = 1000,
    private val ttlMinutes: Long = 60 * 24 // Cache entries expire after 24 hours
) {

    private inner class CacheEntry(
        val embedding: FloatArray,
        val createdAt: Instant,
        var lastAccessedAt: Instant,
        var hitCount: Int = 0
    ) {
        fun isExpired(): Boolean {
            return ChronoUnit.MINUTES.between(createdAt, Instant.now()) > ttlMinutes
        }
    }

    // LinkedHashMap maintains insertion order for LRU
    private val cache = LinkedHashMap<String, CacheEntry>(maxSize, 0.75f, true)
    private val mutex = Mutex()

    // Statistics
    private var hits = 0L
    private var misses = 0L

    /**
     * Retrieves embedding from cache if available and not expired.
     * Returns null if not found or expired.
     */
    suspend fun get(searchText: String): FloatArray? = mutex.withLock {
        val normalized = normalizeKey(searchText)
        val entry = cache[normalized]

        return when {
            entry == null -> {
                misses++
                null
            }

            entry.isExpired() -> {
                cache.remove(normalized)
                misses++
                null
            }

            else -> {
                entry.lastAccessedAt = Instant.now()
                entry.hitCount++
                hits++
                entry.embedding
            }
        }
    }

    /**
     * Stores embedding in cache with LRU eviction if needed.
     */
    suspend fun put(searchText: String, embedding: FloatArray) = mutex.withLock {
        val normalized = normalizeKey(searchText)

        // Remove oldest entry if cache is full
        if (cache.size >= maxSize) {
            val oldest = cache.entries.first()
            cache.remove(oldest.key)
        }

        cache[normalized] = CacheEntry(
            embedding = embedding,
            createdAt = Instant.now(),
            lastAccessedAt = Instant.now()
        )
    }

    /**
     * Normalizes search text for consistent cache keys.
     * - Converts to lowercase
     * - Trims whitespace
     * - Removes extra spaces
     */
    private fun normalizeKey(searchText: String): String {
        return searchText.trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")
    }

    /**
     * Returns cache statistics for monitoring.
     */
    suspend fun getStats(): CacheStats = mutex.withLock {
        val totalRequests = hits + misses
        val hitRate = if (totalRequests > 0) hits.toDouble() / totalRequests else 0.0

        CacheStats(
            size = cache.size,
            maxSize = maxSize,
            hits = hits,
            misses = misses,
            hitRate = hitRate,
            topQueries = getTopQueries(10)
        )
    }

    /**
     * Returns most frequently accessed queries.
     */
    private fun getTopQueries(limit: Int): List<QueryStats> {
        return cache.entries
            .map { (query, entry) ->
                QueryStats(
                    query = query,
                    hitCount = entry.hitCount,
                    age = ChronoUnit.MINUTES.between(entry.createdAt, Instant.now())
                )
            }
            .sortedByDescending { it.hitCount }
            .take(limit)
    }

    /**
     * Clears expired entries from cache.
     */
    suspend fun cleanupExpired() = mutex.withLock {
        val expired = cache.entries
            .filter { it.value.isExpired() }
            .map { it.key }

        expired.forEach { cache.remove(it) }
    }

    /**
     * Clears entire cache.
     */
    suspend fun clear() = mutex.withLock {
        cache.clear()
        hits = 0L
        misses = 0L
    }

    data class CacheStats(
        val size: Int,
        val maxSize: Int,
        val hits: Long,
        val misses: Long,
        val hitRate: Double,
        val topQueries: List<QueryStats>
    )

    data class QueryStats(
        val query: String,
        val hitCount: Int,
        val age: Long // Minutes since creation
    )
}
