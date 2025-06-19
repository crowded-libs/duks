package duks

import kotlin.test.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.coroutines.test.*
import kotlin.time.Duration.Companion.seconds

class MapActionCacheTest {
    
    data class TestCacheableAction(val id: Int) : Action, CacheableAction {
        override val expiresAfter: Instant = Clock.System.now().plus(60, DateTimeUnit.SECOND)
    }
    
    data class TestResultAction(val id: Int) : Action

    @Test
    fun `should perform basic cache operations correctly`() = runTest(timeout = 5.seconds) {
        val cache = MapActionCache()
        val action = TestCacheableAction(1)
        val resultAction = TestResultAction(1)
        
        assertFalse(cache.has(action), "Cache should not initially contain action")
        assertNull(cache.get(action), "Getting a non-existent action should return null")
        
        val expiry = Clock.System.now().plus(10, DateTimeUnit.SECOND)
        val cachedAction = CachedActions(expiry, resultAction)
        cache.put(action, cachedAction)
        
        assertTrue(cache.has(action), "Cache should contain action after put")
        
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
        val firstExpiry = Clock.System.now().plus(10, DateTimeUnit.SECOND)
        cache.put(action, CachedActions(firstExpiry, firstResult))
        
        val firstRetrieved = cache.get(action)
        assertNotNull(firstRetrieved)
        assertEquals(firstResult, firstRetrieved.action, "First cache entry should be stored correctly")
        
        val secondResult = TestResultAction(200)
        val secondExpiry = Clock.System.now().plus(20, DateTimeUnit.SECOND)
        cache.put(action, CachedActions(secondExpiry, secondResult))
        
        val secondRetrieved = cache.get(action)
        assertNotNull(secondRetrieved)
        assertEquals(secondResult, secondRetrieved.action, "Second cache entry should overwrite first")
        assertEquals(secondExpiry, secondRetrieved.expiresAfter, "Updated expiry should be stored")
    }
    
    @Test
    fun `should handle multiple actions independently`() = runTest(timeout = 5.seconds) {
        val cache = MapActionCache()
        
        val action1 = TestCacheableAction(1)
        val action2 = TestCacheableAction(2)
        val action3 = TestCacheableAction(3)
        
        assertFalse(cache.has(action1), "Cache should start empty")
        assertFalse(cache.has(action2), "Cache should start empty")
        assertFalse(cache.has(action3), "Cache should start empty")
        
        val result1 = TestResultAction(100)
        val result2 = TestResultAction(200)
        val expiry = Clock.System.now().plus(10, DateTimeUnit.SECOND)
        
        cache.put(action1, CachedActions(expiry, result1))
        cache.put(action2, CachedActions(expiry, result2))
        
        assertTrue(cache.has(action1), "Cache should contain first action")
        assertTrue(cache.has(action2), "Cache should contain second action")
        assertFalse(cache.has(action3), "Cache should not contain third action")
        
        assertEquals(result1, cache.get(action1)?.action, "First action should be retrievable")
        assertEquals(result2, cache.get(action2)?.action, "Second action should be retrievable")
        assertNull(cache.get(action3), "Uncached action should return null")
    }
    
    @Test
    fun `should handle expired cache entries properly`() = runTest(timeout = 5.seconds) {
        val cache = MapActionCache()
        
        val action = object : Action, CacheableAction {
            override val expiresAfter: Instant = Clock.System.now()
            
            override fun equals(other: Any?): Boolean = other === this
            override fun hashCode(): Int = 42
            override fun toString(): String = "TestExpirableAction"
        }
        
        val resultAction = TestResultAction(999)
        
        val validTime = Clock.System.now().plus(10, DateTimeUnit.SECOND)
        cache.put(action, CachedActions(validTime, resultAction))
        
        assertTrue(cache.has(action), "Cache entry should be found with valid time")
        assertNotNull(cache.get(action), "Cache entry should be retrievable with valid time")
        
        val expiredTime = Clock.System.now().plus(-1, DateTimeUnit.SECOND)
        cache.put(action, CachedActions(expiredTime, resultAction))
        
        assertFalse(cache.has(action), "Cache entry with expired time should not be found")
        assertNull(cache.get(action), "Cache entry with expired time should not be retrievable")
    }
}