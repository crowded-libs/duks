package duks

import kotlin.time.Clock.System.now

/**
 * Default implementation of [ActionCache] that uses an in-memory map.
 *
 * Entries are keyed by [CacheableAction.cacheKey]. Expired entries are removed
 * on [has] / [get]. When [maxSize] is positive, inserts may evict expired entries
 * first, then an arbitrary existing entry if still over capacity.
 *
 * @param maxSize Maximum number of live entries to retain; `0` means unlimited.
 */
class MapActionCache(
    private val maxSize: Int = 0
) : ActionCache {
    private val cache: MutableMap<String, CachedActions> = mutableMapOf()

    /**
     * Number of entries currently held (including any not yet purged as expired).
     */
    val size: Int
        get() = cache.size

    /**
     * Checks if the cache contains a valid, non-expired entry for the given action.
     * Removes the entry if it has expired.
     */
    override fun has(action: CacheableAction): Boolean {
        return getValid(action.cacheKey) != null
    }

    /**
     * Stores an action result in the cache, keyed by [CacheableAction.cacheKey].
     */
    override fun put(action: CacheableAction, cached: CachedActions) {
        val key = action.cacheKey
        if (maxSize > 0 && key !in cache && cache.size >= maxSize) {
            evictExpired()
            if (cache.size >= maxSize) {
                val victim = cache.keys.firstOrNull()
                if (victim != null) {
                    cache.remove(victim)
                }
            }
        }
        cache[key] = cached
    }

    /**
     * Retrieves a cached action result, returning null if missing or expired.
     * Removes the entry when expired.
     */
    override fun get(action: CacheableAction): CachedActions? {
        return getValid(action.cacheKey)
    }

    private fun getValid(key: String): CachedActions? {
        val value = cache[key] ?: return null
        return if (isExpired(value)) {
            cache.remove(key)
            null
        } else {
            value
        }
    }

    private fun isExpired(cached: CachedActions): Boolean {
        return cached.expiresAfter <= now()
    }

    private fun evictExpired() {
        val now = now()
        val expiredKeys = cache.entries
            .filter { it.value.expiresAfter <= now }
            .map { it.key }
        expiredKeys.forEach { cache.remove(it) }
    }
}
