package duks

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CachingTest {
    
    data class TestState(
        val counter: Int = 0,
        val cacheHits: Int = 0
    ) : StateModel
    
    data class CacheableTestAction(val id: Int, val value: Int) : Action, CacheableAction {
        override val expiresAfter: Instant = Clock.System.now().plus(5, DateTimeUnit.SECOND)
    }
    
    data class ResultAction(val id: Int, val result: Int) : Action

    data class ShortExpirationAction(val value: Int) : Action, CacheableAction {
        override val expiresAfter: Instant = Clock.System.now().plus(50, DateTimeUnit.MILLISECOND)
    }
    
    @Test
    fun `should cache transformed actions correctly`() = runTest(timeout = 5.seconds) {
        val processedActions = mutableListOf<Action>()
        val testCache = TestActionCache()
        
        val transformerMiddleware: Middleware<TestState> = { store, next, action ->
            processedActions.add(action)
            
            if (action is CacheableTestAction) {
                val resultAction = ResultAction(action.id, action.value * 2)
                next(resultAction)
                resultAction
            } else if (action is ResultAction) {
                next(action)
                action
            } else {
                next(action)
            }
        }
        
        val store = createStoreForTest(TestState()) {
            middleware {
                caching(testCache)
                middleware(transformerMiddleware)
            }
            
            reduceWith { state, action ->
                when (action) {
                    is ResultAction -> state.copy(counter = state.counter + action.result)
                    else -> state
                }
            }
        }
        
        val action1 = CacheableTestAction(1, 5)
        store.dispatch(action1)
        
        advanceUntilIdle()
        
        assertEquals(1, processedActions.size)
        assertEquals(CacheableTestAction(1, 5), processedActions[0])
        
        assertEquals(10, store.state.value.counter)
        
        processedActions.clear()
        store.dispatch(action1)
        
        advanceUntilIdle()
        
        assertEquals(1, processedActions.size, "Transformer should receive one action")
        assertTrue(processedActions[0] is ResultAction, "Transformer should receive cached ResultAction, not original CacheableTestAction")
        
        assertEquals(20, store.state.value.counter)
        
        processedActions.clear()
        val action2 = CacheableTestAction(2, 7)
        store.dispatch(action2)
        
        advanceUntilIdle()
        
        assertEquals(1, processedActions.size)
        assertEquals(CacheableTestAction(2, 7), processedActions[0])
        
        assertEquals(34, store.state.value.counter)
    }
    
    @Test
    fun `should respect cache expiration times`() = runTest(timeout = 5.seconds) {
        val processedActions = mutableListOf<Action>()
        val testCache = TestActionCache()
        
        val transformerMiddleware: Middleware<TestState> = { store, next, action ->
            processedActions.add(action)
            
            if (action is ShortExpirationAction) {
                val resultAction = ResultAction(0, action.value)
                next(resultAction)
                resultAction
            } else if (action is ResultAction) {
                next(action)
                action
            } else {
                next(action)
            }
        }
        
        val store = createStoreForTest(TestState()) {
            middleware {
                caching(testCache)
                middleware(transformerMiddleware)
            }
            
            reduceWith { state, action ->
                when (action) {
                    is ResultAction -> state.copy(counter = state.counter + action.result)
                    else -> state
                }
            }
        }
        
        val action1 = ShortExpirationAction(5)
        store.dispatch(action1)
        
        advanceUntilIdle()
        
        assertEquals(1, processedActions.size)
        assertEquals(ShortExpirationAction(5), processedActions[0])
        
        assertEquals(5, store.state.value.counter)
        
        processedActions.clear()
        store.dispatch(action1)
        
        advanceUntilIdle()
        
        assertEquals(1, processedActions.size, "Transformer should receive one action")
        assertTrue(processedActions[0] is ResultAction, "Transformer should receive cached ResultAction, not original action")
        
        assertEquals(10, store.state.value.counter)
        
        testCache.expireAll()
        
        processedActions.clear()
        store.dispatch(action1)
        
        advanceUntilIdle()
        
        assertEquals(1, processedActions.size)
        assertEquals(ShortExpirationAction(5), processedActions[0])
        
        assertEquals(15, store.state.value.counter)
    }
    
    @Test
    fun `should execute middleware in correct order with caching`() = runTest(timeout = 5.seconds) {
        val executionOrder = mutableListOf<String>()
        
        val firstMiddleware: Middleware<TestState> = { store, next, action ->
            executionOrder.add("first-before")
            val result = next(action)
            executionOrder.add("first-after")
            result
        }
        
        val secondMiddleware: Middleware<TestState> = { store, next, action ->
            executionOrder.add("second-before")
            val result = next(action)
            executionOrder.add("second-after")
 
            result
        }
        
        val store = createStoreForTest(TestState()) {
            middleware {
                middleware(firstMiddleware)
                middleware(secondMiddleware)
            }
            
            reduceWith { state, action -> 
                executionOrder.add("reducer")
                state 
            }
        }
        
        store.dispatch(CacheableTestAction(1, 5))
        advanceUntilIdle()
        
        
        assertEquals("first-before", executionOrder[0], "First middleware should start execution first")
        assertEquals("second-before", executionOrder[1], "Second middleware should start execution second")
        assertEquals("reducer", executionOrder[2], "Reducer should execute after all middleware starts")
        assertEquals("second-after", executionOrder[3], "Second middleware should finish before first middleware")
        assertEquals("first-after", executionOrder[4], "First middleware should finish last")
    }
    
    @Test
    fun `should track cache actions in detail for debugging`() = runTest(timeout = 5.seconds) {
        val receivedActions = mutableMapOf<String, MutableList<Action>>()
        val testCache = TestActionCache()
        val actionSequence = mutableListOf<String>()
        
        val cachingTracker: Middleware<TestState> = { store, next, action ->
            val list = receivedActions.getOrPut("caching") { mutableListOf() }
            list.add(action)
            actionSequence.add("Caching middleware received: $action")
            next(action)
        }
        
        val transformerMiddleware: Middleware<TestState> = { store, next, action ->
            val list = receivedActions.getOrPut("transformer") { mutableListOf() }
            list.add(action)
            actionSequence.add("Transformer middleware received: $action")
            
            if (action is CacheableTestAction) {
                actionSequence.add("Transforming cacheable action to: ResultAction")
                val transformed = ResultAction(action.id, action.value * 2)
                next(transformed)
                transformed
            } else {
                actionSequence.add("Passing through action: $action")
                next(action)
                action
            }
        }
        
        val store = createStoreForTest(TestState()) {
            middleware {
                middleware { store, next, action ->
                    cachingTracker(store, next, action)
                }
                
                caching(testCache)
                
                middleware(transformerMiddleware)
            }
            
            reduceWith { state, action ->
                actionSequence.add("Reducer received: $action")
                
                when (action) {
                    is ResultAction -> {
                        if (action.id < 0) {
                            state.copy(
                                counter = state.counter + action.result,
                                cacheHits = state.cacheHits + 1
                            )
                        } else {
                            state.copy(counter = state.counter + action.result)
                        }
                    }
                    else -> state
                }
            }
        }
        
        actionSequence.add("=== FIRST DISPATCH (SHOULD TRANSFORM) ===")
        store.dispatch(CacheableTestAction(1, 5))
        advanceUntilIdle()
        
        assertEquals(10, store.state.value.counter, "Counter should be updated with transformed value")
        
        receivedActions.forEach { (_, list) -> list.clear() }
        
        actionSequence.add("=== SECOND DISPATCH (SHOULD USE CACHE) ===")
        store.dispatch(CacheableTestAction(1, 5))
        advanceUntilIdle()
        
        assertEquals(20, store.state.value.counter, "Counter should be updated twice")
        
        
        assertTrue(receivedActions["transformer"]?.get(0) is ResultAction, 
                 "Transformer should receive ResultAction from cache on second dispatch")
    }
    
    @Test
    fun `should handle multiple cacheable actions correctly`() = runTest(timeout = 5.seconds) {
        val transformerCalls = mutableListOf<String>()
        val testCache = TestActionCache()
        
        val store = createStoreForTest(TestState()) {
            middleware {
                caching(testCache)
                
                middleware { store, next, action ->
                    if (action is CacheableTestAction) {
                        transformerCalls.add("CacheableTestAction:${action.id}")
                        val resultAction = ResultAction(action.id, action.value * 2)
                        next(resultAction)
                        resultAction
                    } else if (action is ShortExpirationAction) {
                        transformerCalls.add("ShortExpirationAction:${action.value}")
                        val resultAction = ResultAction(-1, action.value * 3)
                        next(resultAction)
                        resultAction
                    } else {
                        next(action)
                        action
                    }
                }
            }
            
            reduceWith { state, action ->
                when (action) {
                    is ResultAction -> state.copy(counter = state.counter + action.result)
                    else -> state
                }
            }
        }
        
        store.dispatch(CacheableTestAction(1, 5))
        advanceUntilIdle()
        
        store.dispatch(CacheableTestAction(2, 3))
        advanceUntilIdle()
        
        store.dispatch(CacheableTestAction(1, 5))
        advanceUntilIdle()
        
        store.dispatch(ShortExpirationAction(7))
        advanceUntilIdle()
        
        store.dispatch(CacheableTestAction(1, 5))
        advanceUntilIdle()
        
        assertEquals(3, transformerCalls.size, "Transformer should be called 3 times")
        assertEquals("CacheableTestAction:1", transformerCalls[0])
        assertEquals("CacheableTestAction:2", transformerCalls[1])
        assertEquals("ShortExpirationAction:7", transformerCalls[2])
        
        assertEquals(57, store.state.value.counter, "Final counter should sum all contributions")
    }
}