package io.kurlew.pipeline
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache context for temporary data storage.
 *
 * Thread-safe cache that can be used to store intermediate results,
 * lookup data, or any temporary information needed during processing.
 */
class CacheContext {
    val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * Stores a value in the cache with optional TTL.
     */
    fun put(key: String, value: Any, ttlMs: Long? = null) {
        val expiresAt = ttlMs?.let { System.currentTimeMillis() + it }
        cache[key] = CacheEntry(value, expiresAt)
    }

    /**
     * Retrieves a value from the cache.
     * Returns null if not found or expired.
     */
    fun get(key: String): Any? {
        val entry = cache[key] ?: return null

        // Check expiration
        if (entry.expiresAt != null && System.currentTimeMillis() > entry.expiresAt) {
            cache.remove(key)
            return null
        }

        return entry.value
    }

    /**
     * Removes a value from the cache.
     */
    fun remove(key: String) {
        cache.remove(key)
    }

    /**
     * Clears all expired entries.
     */
    fun cleanExpired() {
        val now = System.currentTimeMillis()
        cache.entries.removeIf { (_, entry) ->
            entry.expiresAt != null && now > entry.expiresAt
        }
    }

    /**
     * Clears the entire cache.
     */
    fun clear() {
        cache.clear()
    }

    /**
     * Gets or computes a value.
     */
    fun getOrPut(key: String, ttlMs: Long? = null, defaultValue: () -> Any): Any {
        return get(key) ?: defaultValue().also { put(key, it, ttlMs) }
    }

    data class CacheEntry(
        val value: Any,
        val expiresAt: Long? = null
    )
}