package app.bartering.features.chat.cache

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Simple in-memory cache for public keys to prevent excessive database lookups
 * In a production environment with multiple server instances, consider using Redis
 */
class PublicKeyCache(
    private val expirationTimeMinutes: Long = 60
) {
    private data class CacheEntry(
        val publicKey: String,
        val timestamp: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * Get a public key from cache
     * Returns null if not found or expired
     */
    fun get(userId: String): String? {
        val entry = cache[userId] ?: return null

        // Check if entry has expired
        val age = System.currentTimeMillis() - entry.timestamp
        if (age > TimeUnit.MINUTES.toMillis(expirationTimeMinutes)) {
            cache.remove(userId)
            return null
        }

        return entry.publicKey
    }

    /**
     * Put a public key into cache
     */
    fun put(userId: String, publicKey: String) {
        cache[userId] = CacheEntry(publicKey, System.currentTimeMillis())
    }

    /**
     * Remove a specific user's public key from cache
     */
    fun invalidate(userId: String) {
        cache.remove(userId)
    }

    /**
     * Clear all cached public keys
     */
    fun clear() {
        cache.clear()
    }

    /**
     * Remove expired entries from cache
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        val expiredKeys = cache.filterValues { entry ->
            (now - entry.timestamp) > TimeUnit.MINUTES.toMillis(expirationTimeMinutes)
        }.keys

        expiredKeys.forEach { cache.remove(it) }
    }
}
