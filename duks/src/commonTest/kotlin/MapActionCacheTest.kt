package duks

import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.coroutines.test.*
import kotlin.time.Duration.Companion.seconds

class MapActionCacheTest {

    data class TestCacheableAction(val id: Int) : Action, CacheableAction {
        override val expiresAfter: Instant = Clock.System.now().plus(60, DateTimeUnit.SECOND)
    }

    data class TestResultAction(val id: Int) : Action

    private fun futureExpiry(seconds: Int = 10): Instant =
        Clock.System.now().plus(seconds, DateTimeUnit.SECOND)

    private fun pastExpiry(): Instant =
        Clock.System.now().plus(-1, DateTimeUnit.SECOND)

    @Test
    fun `should perform basic cache operations correctly`() = runTest(timeout = 5.seconds) {
        val cache = MapActionCache()
        val action = TestCacheableAction(1)
        val resultAction = TestResultAction(1)

        assertFalse(cache.has(action), "Cache should not initially contain action")
        assertNull(cache.get(action), "Getting a non-existent action should return null")

        val expiry = futureExpiry()
        val cachedAction = CachedActions(expiry, resultAction)
        cache.put(action, cachedAction)

        assertTrue(cache.has(action), "Cache should contain action after put")
        assertEquals(1, cache.size)

        val retrieved = cache.get(action)
        assertNotNull(retrieved, "Getting a cached action should not return null")
        assertEquals(expiry, retrieved.expiresAfter, "Cached expiry time should match")
        assertEquals(resultAction, retrieved.action, "Cached action should match")
    }

    @Test
    fun `should overwrite existing cache entries`() = runTest(timeout = 5.seconds) {
        val cache = MapActionCache()
        val action = TestCacheableAction(2)

        val firstResult = TestResultAction(100)
        cache.put(action, CachedActions(futureExpiry(10), firstResult))

        val firstRetrieved = cache.get(action)
        assertNotNull(firstRetrieved)
        assertEquals(firstResult, firstRetrieved.action, "First cache entry should be stored correctly")

        val secondResult = TestResultAction(200)
        val secondExpiry = futureExpiry(20)
        cache.put(action, CachedActions(secondExpiry, secondResult))

        val secondRetrieved = cache.get(action)
        assertNotNull(secondRetrieved)
        assertEquals(secondResult, secondRetrieved.action, "Second cache entry should overwrite first")
        assertEquals(secondExpiry, secondRetrieved.expiresAfter, "Updated expiry should be stored")
        assertEquals(1, cache.size)
    }

    @Test
    fun `should handle multiple actions independently`() = runTest(timeout = 5.seconds) {
        val cache = MapActionCache()

        val action1 = TestCacheableAction(1)
        val action2 = TestCacheableAction(2)
        val action3 = TestCacheableAction(3)

        assertFalse(cache.has(action1))
        assertFalse(cache.has(action2))
        assertFalse(cache.has(action3))

        val result1 = TestResultAction(100)
        val result2 = TestResultAction(200)
        val expiry = futureExpiry()

        cache.put(action1, CachedActions(expiry, result1))
        cache.put(action2, CachedActions(expiry, result2))

        assertTrue(cache.has(action1))
        assertTrue(cache.has(action2))
        assertFalse(cache.has(action3))
        assertEquals(2, cache.size)

        assertEquals(result1, cache.get(action1)?.action)
        assertEquals(result2, cache.get(action2)?.action)
        assertNull(cache.get(action3))
    }

    @Test
    fun `should remove expired entries on has and get`() = runTest(timeout = 5.seconds) {
        val cache = MapActionCache()

        val action = object : Action, CacheableAction {
            override val expiresAfter: Instant = Clock.System.now()
            override val cacheKey: String = "expirable"
            override fun equals(other: Any?): Boolean = other === this
            override fun hashCode(): Int = 42
            override fun toString(): String = "TestExpirableAction"
        }

        val resultAction = TestResultAction(999)

        cache.put(action, CachedActions(futureExpiry(), resultAction))
        assertTrue(cache.has(action))
        assertEquals(1, cache.size)

        cache.put(action, CachedActions(pastExpiry(), resultAction))
        assertFalse(cache.has(action), "has() should report expired entry as missing")
        assertEquals(0, cache.size, "has() should remove expired entry")

        cache.put(action, CachedActions(pastExpiry(), resultAction))
        assertEquals(1, cache.size)
        assertNull(cache.get(action), "get() should return null for expired entry")
        assertEquals(0, cache.size, "get() should remove expired entry")
    }

    @Test
    fun `should use cacheKey for lookup across equal value instances`() = runTest(timeout = 5.seconds) {
        val cache = MapActionCache()
        val a = TestCacheableAction(7)
        val b = TestCacheableAction(7)
        assertEquals(a.cacheKey, b.cacheKey)

        cache.put(a, CachedActions(futureExpiry(), TestResultAction(70)))
        assertTrue(cache.has(b))
        assertEquals(70, (cache.get(b)?.action as TestResultAction).id)
    }

    @Test
    fun `should respect custom cacheKey override`() = runTest(timeout = 5.seconds) {
        data class CustomKeyAction(val id: Int, val ignored: String) : Action, CacheableAction {
            override val cacheKey: String = "custom:$id"
            override val expiresAfter: Instant = futureExpiry()
        }

        val cache = MapActionCache()
        cache.put(CustomKeyAction(1, "a"), CachedActions(futureExpiry(), TestResultAction(1)))

        assertTrue(cache.has(CustomKeyAction(1, "b")))
        assertFalse(cache.has(CustomKeyAction(2, "a")))
    }

    @Test
    fun `should evict when maxSize is reached`() = runTest(timeout = 5.seconds) {
        val cache = MapActionCache(maxSize = 2)
        val expiry = futureExpiry()

        cache.put(TestCacheableAction(1), CachedActions(expiry, TestResultAction(1)))
        cache.put(TestCacheableAction(2), CachedActions(expiry, TestResultAction(2)))
        assertEquals(2, cache.size)

        cache.put(TestCacheableAction(3), CachedActions(expiry, TestResultAction(3)))
        assertEquals(2, cache.size, "Size must not exceed maxSize")
        assertTrue(cache.has(TestCacheableAction(3)), "Newest entry should be present")
    }

    @Test
    fun `should prefer evicting expired entries before maxSize victims`() = runTest(timeout = 5.seconds) {
        val cache = MapActionCache(maxSize = 2)

        cache.put(TestCacheableAction(1), CachedActions(pastExpiry(), TestResultAction(1)))
        cache.put(TestCacheableAction(2), CachedActions(futureExpiry(), TestResultAction(2)))
        assertEquals(2, cache.size)

        cache.put(TestCacheableAction(3), CachedActions(futureExpiry(), TestResultAction(3)))
        assertEquals(2, cache.size)
        assertFalse(cache.has(TestCacheableAction(1)), "Expired entry should be gone")
        assertTrue(cache.has(TestCacheableAction(2)))
        assertTrue(cache.has(TestCacheableAction(3)))
    }
}
