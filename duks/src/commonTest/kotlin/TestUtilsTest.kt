package duks

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TestUtilsTest {

    data class TestState(val counter: Int = 0) : StateModel
    data class Increment(val by: Int = 1) : Action

    @Test
    fun `awaitState succeeds when predicate already true`() = runTest(timeout = 5.seconds) {
        val store = createStoreForTest(TestState(3)) {
            reduceWith { state, _ -> state }
        }
        awaitState(store.state) { it.counter == 3 }
    }

    @Test
    fun `awaitState observes later state`() = runTest(timeout = 5.seconds) {
        val store = createStoreForTest(TestState()) {
            reduceWith { state, action ->
                when (action) {
                    is Increment -> state.copy(counter = state.counter + action.by)
                    else -> state
                }
            }
        }

        backgroundScope.launch {
            store.dispatchAsync(Increment(5))
        }
        awaitState(store.state) { it.counter == 5 }
        assertEquals(5, store.state.value.counter)
    }

    @Test
    fun `awaitUntil fails when condition never met`() = runTest(timeout = 5.seconds) {
        assertFailsWith<AssertionError> {
            awaitUntil(maxPumps = 5) { false }
        }
    }

    @Test
    fun `dispatchAndAdvance uses dispatchAsync ordering`() = runTest(timeout = 5.seconds) {
        val order = mutableListOf<String>()
        val store = createStoreForTest(TestState()) {
            middleware {
                middleware { _, next, action ->
                    order.add("before")
                    val r = next(action)
                    order.add("after")
                    r
                }
            }
            reduceWith { state, action ->
                when (action) {
                    is Increment -> {
                        order.add("reduce")
                        state.copy(counter = state.counter + action.by)
                    }
                    else -> state
                }
            }
        }

        dispatchAndAdvance(store, Increment(1))
        assertEquals(listOf("before", "reduce", "after"), order)
        assertEquals(1, store.state.value.counter)
    }

    @Test
    fun `TestActionCache keys by cacheKey`() = runTest(timeout = 5.seconds) {
        data class Keyed(val id: Int, val noise: String) : Action, CacheableAction {
            override val cacheKey: String = "k:$id"
        }

        val cache = TestActionCache()
        val a = Keyed(1, "a")
        val b = Keyed(1, "b")
        cache.put(a, CachedActions(a.expiresAfter, Increment(1)))
        assertTrue(cache.has(b))
        assertEquals(1, (cache.get(b)?.action as Increment).by)
    }
}
