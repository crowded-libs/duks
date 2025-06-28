package duks

import kotlin.time.Clock.System.now

/**
 * Default implementation of ActionCache that uses a simple in-memory map.
 *
 * This implementation stores cached action results in a mutable map and
 * handles expiration based on the timestamp stored in each CachedActions entry.
 */
class MapActionCache : ActionCache {
    /**
     * The internal map storing the cached actions.
     */
    private val cache: MutableMap<Action, CachedActions> = mutableMapOf()

    /**
     * Checks if the cache contains a valid, non-expired entry for the given action.
     *
     * @param action The cacheable action to check
     * @return true if the action is cached and not expired, false otherwise
     */
    override fun has(action: CacheableAction): Boolean {
        val value = cache[action]
        return value != null && value.expiresAfter > now()
    }

    /**
     * Stores an action result in the cache.
     *
     * @param action The cacheable action to use as a key
     * @param cached The cached action result with expiration time
     */
    override fun put(action: CacheableAction, cached: CachedActions) {
        cache[action] = cached
    }

    /**
     * Retrieves a cached action result, returning null if expired.
     *
     * @param action The cacheable action to look up
     * @return The cached action result or null if not found or expired
     */
    override fun get(action: CacheableAction): CachedActions? {
        val value = cache[action]
        value?.let {
            if(value.expiresAfter < now()) {
                return null
            }
        }
        return value
    }
}

