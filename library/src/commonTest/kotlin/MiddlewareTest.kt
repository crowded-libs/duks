package duks

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MiddlewareTest {
    
    data class TestState(
        val counter: Int = 0,
        val count: Int = 0,
        val error: String? = null,
        val log: List<String> = emptyList(),
        val processed: MutableList<String> = mutableListOf(),
        val values: List<String> = emptyList()
    ) : StateModel
    
    data class IncrementAction(val value: Int = 1) : Action
    data class AddAction(val value: Int) : Action
    data class ErrorAction(val message: String = "Test error") : Action
    
    data class SimpleAction(val value: String) : Action

    @Test
    fun `should execute middleware and update state`() = runTest {
        val middlewareCalled = mutableListOf<String>()
        
        val loggingMiddleware: Middleware<TestState> = { store, next, action ->
            middlewareCalled.add("logging")
            val result = next(action)
            result
        }
        
        val store = createStoreForTest(TestState()) {
            middleware {
                middleware(loggingMiddleware)
            }
            
            reduceWith { state, action ->
                when (action) {
                    is AddAction -> state.copy(count = state.count + action.value)
                    else -> state
                }
            }
        }
        
        store.dispatch(AddAction(1))
        advanceUntilIdle()
        
        assertTrue(middlewareCalled.isNotEmpty(), "Middleware should be called")
        
        assertEquals(1, store.state.value.count, "State should be updated")
    }
    
    @Test
    fun `should capture logs before and after action`() = runTest {
        val logs = mutableListOf<String>()
        
        val initialState = TestState()
        val store = createStoreForTest(initialState) {
            middleware {
                middleware(loggerMiddleware<TestState> { message -> 
                    logs.add(message)
                })
            }
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    else -> state
                }
            }
        }
        
        store.dispatch(IncrementAction(3))
        
        advanceUntilIdle()
        
        assertEquals(2, logs.size, "Logger should capture before and after messages")
        assertTrue(logs[0].contains("Action: IncrementAction"), 
                  "First log should mention action name")
        assertTrue(logs[1].contains("After Action: IncrementAction"), 
                  "Second log should mention being after action")
        
        assertEquals(3, store.state.value.counter)
    }
    
    @Test
    fun `should execute middleware in correct order and sequence`() = runTest {
        val logs = mutableListOf<String>()
        val middlewareExecutionOrder = mutableListOf<String>()
        
        val customMiddleware1: Middleware<TestState> = { store, next, action ->
            middlewareExecutionOrder.add("middleware1 before")
            val result = next(action)
            middlewareExecutionOrder.add("middleware1 after")
            result
        }
        
        val customMiddleware2: Middleware<TestState> = { store, next, action ->
            middlewareExecutionOrder.add("middleware2 before")
            val result = next(action)
            middlewareExecutionOrder.add("middleware2 after")
            result
        }
        
        val initialState = TestState()
        val store = createStoreForTest(initialState) {
            middleware {
                middleware(customMiddleware1)
                middleware(loggerMiddleware<TestState> { message -> 
                    logs.add(message)
                    middlewareExecutionOrder.add("logger ${if (message.startsWith("Action")) "before" else "after"}")
                })
                middleware(customMiddleware2)
            }
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    else -> state
                }
            }
        }
        
        store.dispatch(IncrementAction(1))
        
        advanceUntilIdle()
        
        assertEquals(6, middlewareExecutionOrder.size, "All middleware phases should execute")
        assertEquals("middleware1 before", middlewareExecutionOrder[0])
        assertEquals("logger before", middlewareExecutionOrder[1])
        assertEquals("middleware2 before", middlewareExecutionOrder[2])
        assertEquals("middleware2 after", middlewareExecutionOrder[3])
        assertEquals("logger after", middlewareExecutionOrder[4])
        assertEquals("middleware1 after", middlewareExecutionOrder[5])
        
        assertEquals(1, store.state.value.counter)
    }
    
    @Test
    fun `should catch exceptions from reducer`() = runTest {
        val initialState = TestState()
        
        val reducer: Reducer<TestState> = { state, action ->
            when (action) {
                is ErrorAction -> throw RuntimeException(action.message)
                is IncrementAction -> state.copy(counter = state.counter + action.value)
                else -> state
            }
        }
        
        val store = createStoreForTest(initialState) {
            middleware {
                exceptionHandling()
            }
            reduceWith(reducer)
        }
        
        store.dispatch(ErrorAction("Test error"))

        advanceUntilIdle()

        assertEquals(0, store.state.value.counter)
        
        store.dispatch(IncrementAction(5))

        advanceUntilIdle()

        assertEquals(5, store.state.value.counter)
    }
    
    @Test
    fun `should catch exceptions thrown by middleware`() = runTest {
        val initialState = TestState()
        val actionsProcessed = mutableListOf<String>()
        
        val errorMiddleware: Middleware<TestState> = { store, next, action ->
            actionsProcessed.add("error middleware before")
            if (action is ErrorAction) {
                throw RuntimeException(action.message)
            }
            val result = next(action)
            actionsProcessed.add("error middleware after")
            result
        }
        
        val afterMiddleware: Middleware<TestState> = { store, next, action ->
            actionsProcessed.add("after middleware before")
            val result = next(action)
            actionsProcessed.add("after middleware after")
            result
        }
        
        val store = createStoreForTest(initialState) {
            middleware {
                middleware(afterMiddleware)
                exceptionHandling()
                middleware(errorMiddleware)
            }
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    else -> state
                }
            }
        }
        
        actionsProcessed.clear()
        store.dispatch(ErrorAction("Test error in middleware"))

        advanceUntilIdle()

        assertEquals(3, actionsProcessed.size)
        assertEquals("after middleware before", actionsProcessed[0])
        assertEquals("error middleware before", actionsProcessed[1])
        assertEquals("after middleware after", actionsProcessed[2])

        actionsProcessed.clear()
        store.dispatch(IncrementAction(3))

        advanceUntilIdle()

        assertEquals(4, actionsProcessed.size)
        assertEquals("after middleware before", actionsProcessed[0])
        assertEquals("error middleware before", actionsProcessed[1])
        assertEquals("error middleware after", actionsProcessed[2])
        assertEquals("after middleware after", actionsProcessed[3])
        
        assertEquals(3, store.state.value.counter)
    }
    
    @Test
    fun `should follow nested middleware execution order`() = runTest(timeout = 5.seconds) {
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
        
        store.dispatch(IncrementAction(5))
        advanceUntilIdle()
        
        
        assertEquals("first-before", executionOrder[0], "First middleware should start execution first")
        assertEquals("second-before", executionOrder[1], "Second middleware should start execution second")
        assertEquals("reducer", executionOrder[2], "Reducer should execute after all middleware starts")
        assertEquals("second-after", executionOrder[3], "Second middleware should finish before first middleware")
        assertEquals("first-after", executionOrder[4], "First middleware should finish last")
    }
    
    @Test
    fun `should process actions through middleware chain in correct sequence`() = runTest(timeout = 5.seconds) {
        val executionOrder = mutableListOf<String>()
        
        val store = createStoreForTest(TestState()) {
            
            middleware {
                middleware { store, next, action ->
                    executionOrder.add("middleware1:before")
                    val result = next(action)
                    executionOrder.add("middleware1:after")
                    result
                }
                
                middleware { store, next, action ->
                    executionOrder.add("middleware2:before")
                    val result = next(action)
                    executionOrder.add("middleware2:after")
                    result
                }
            }
            
            reduceWith { state, action ->
                executionOrder.add("reducer")
                when (action) {
                    is SimpleAction -> 
                        state.copy(values = state.values + action.value)
                    else -> state
                }
            }
        }
        
        store.dispatch(SimpleAction("test"))
        runCurrent()
        advanceTimeBy(100)
        advanceUntilIdle()
        
        
        assertTrue(executionOrder.isNotEmpty(), "Middleware should have executed")
        
        assertEquals("middleware1:before", executionOrder[0], "First middleware should execute first")
        assertEquals("middleware2:before", executionOrder[1], "Second middleware should execute next")
        assertEquals("reducer", executionOrder[2], "Reducer should execute next")
        assertEquals("middleware2:after", executionOrder[3], "Second middleware after should execute next")
        assertEquals("middleware1:after", executionOrder[4], "First middleware after should execute last")
    }
}