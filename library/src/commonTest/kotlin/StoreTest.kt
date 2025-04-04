package duks

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class StoreTest {
    
    data class TestState(
        val counter: Int = 0, 
        val value: Int = 0,
        val messages: List<String> = emptyList()
    ) : StateModel
    
    data class IncrementAction(val value: Int = 1) : Action
    data class DecrementAction(val value: Int = 1) : Action
    data class AddMessageAction(val message: String) : Action
    class ErrorAction(val message: String = "Test error") : Action
    
    class TestAsyncAction(private val delayMillis: Long = 0) : AsyncAction<Int> {
        override suspend fun execute(): Result<Int> {
            if (delayMillis > 0) {
                delay(delayMillis)
            }
            return Result.success(42)
        }
    }
    
    @Test
    fun `should create store with initial state`() = runTest {
        val store = createStoreForTest(TestState()) {
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    is DecrementAction -> state.copy(counter = state.counter - action.value)
                    else -> state
                }
            }
        }
        
        assertEquals(0, store.state.value.counter)
    }
    
    @Test
    fun `should apply reducer functions directly to state`() {
        val reducer: Reducer<TestState> = { state, action ->
            when (action) {
                is IncrementAction -> state.copy(counter = state.counter + action.value)
                is DecrementAction -> state.copy(counter = state.counter - action.value)
                else -> state
            }
        }
        
        val initialState = TestState()
        val state1 = reducer(initialState, IncrementAction(5))
        assertEquals(5, state1.counter)
        
        val state2 = reducer(state1, DecrementAction(3))
        assertEquals(2, state2.counter)
    }
    
    @Test
    fun `should update state when dispatching actions`() = runTest {
        val store = createStoreForTest(TestState()) {
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    is DecrementAction -> state.copy(counter = state.counter - action.value)
                    else -> state
                }
            }
        }
        
        store.dispatch(IncrementAction(5))
        
        advanceUntilIdle()
        
        assertEquals(5, store.state.value.counter)
    }
    
    @Test
    fun `should track actions processed by the store`() = runTest {
        val initialState = TestState()
        
        val (store, actionsProcessed) = createTrackedStoreForTest(initialState) {
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    is DecrementAction -> state.copy(counter = state.counter - action.value)
                    else -> state
                }
            }
        }
        
        store.dispatch(IncrementAction(5))
        
        advanceUntilIdle()
        
        assertEquals(1, actionsProcessed.size)
        assertTrue(actionsProcessed[0] is IncrementAction)
        assertEquals(5, store.state.value.counter)
        
        store.dispatch(DecrementAction(2))
        
        advanceUntilIdle()
        
        assertEquals(2, actionsProcessed.size)
        assertTrue(actionsProcessed[1] is DecrementAction)
        assertEquals(3, store.state.value.counter)
    }
    
    @Test
    fun `should execute middleware before and after actions`() = runTest {
        val initialState = TestState()
        val actionsProcessed = mutableListOf<String>()
        
        val trackingMiddleware: Middleware<TestState> = { store, next, action ->
            actionsProcessed.add("Before: ${action::class.simpleName}")
            val result = next(action)
            actionsProcessed.add("After: ${action::class.simpleName}")
            result
        }
        
        val store = createStoreForTest(initialState) {
            middleware {
                middleware(trackingMiddleware)
            }
            reduceWith { state, _ -> state }
        }
        
        store.dispatch(IncrementAction(5))
        
        advanceUntilIdle()
        
        assertEquals(2, actionsProcessed.size)
        assertEquals("Before: IncrementAction", actionsProcessed[0])
        assertEquals("After: IncrementAction", actionsProcessed[1])
    }
    
    @Test
    fun `should update multiple state properties correctly`() = runTest {
        val initialState = TestState()
        val store = createStoreForTest(initialState) {
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    is DecrementAction -> state.copy(counter = state.counter - action.value)
                    is AddMessageAction -> state.copy(messages = state.messages + action.message)
                    else -> state
                }
            }
        }
        
        store.dispatch(IncrementAction(5))
        store.dispatch(AddMessageAction("Hello"))
        
        advanceUntilIdle()
        
        assertEquals(5, store.state.value.counter)
        assertEquals(1, store.state.value.messages.size)
        assertEquals("Hello", store.state.value.messages[0])
    }
    
    @Test
    fun `should log actions with logger middleware`() = runTest {
        val logs = mutableListOf<String>()
        
        val store = createStoreForTest(TestState()) {
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
        
        assertEquals(2, logs.size)
        assertTrue(logs[0].contains("Action:"))
        assertTrue(logs[1].contains("After Action:"))
    }
    
    @Test
    fun `should handle exceptions with exception middleware`() = runTest {
        val errors = mutableListOf<String>()
        
        val store = createStoreForTest(TestState()) {
            middleware {
                middleware(exceptionMiddleware<TestState> { error ->
                    errors.add(error)
                })
            }
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    is ErrorAction -> throw RuntimeException(action.message)
                    else -> state
                }
            }
        }
        
        store.dispatch(IncrementAction(1))
        advanceUntilIdle()
        
        assertEquals(1, store.state.value.counter)
        
        store.dispatch(ErrorAction("Test error"))
        advanceUntilIdle()
        
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("Test error"))
    }
    
    @Test
    fun `should process async actions correctly`() = runTest {
        val (store, processedActions) = createTrackedStoreForTest(TestState()) {
            middleware {
                middleware({ store, next, action ->
                    if (action is AsyncAction<*>) {
                        val processingAction = AsyncProcessing(action)
                        store.dispatch(processingAction)
                        
                        val result = (action as TestAsyncAction).execute()
                        val resultAction = AsyncResultAction(action, result)
                        store.dispatch(resultAction)
                        
                        val completeAction = AsyncComplete(action)
                        store.dispatch(completeAction)
                        
                        action
                    } else {
                        next(action)
                    }
                })
            }
            
            reduceWith { state, action ->
                when (action) {
                    is AsyncResultAction<*> -> {
                        if (action.result.isSuccess) {
                            val value = action.result.getOrNull()
                            if (value is Int) {
                                state.copy(counter = value)
                            } else {
                                state
                            }
                        } else {
                            state
                        }
                    }
                    else -> state
                }
            }
        }
        
        store.dispatch(TestAsyncAction())
        
        advanceUntilIdle()
        
        assertTrue(processedActions.size >= 4, "Should have processed at least 4 actions")
        assertTrue(processedActions.any { it is TestAsyncAction })
        assertTrue(processedActions.any { it is AsyncProcessing })
        assertTrue(processedActions.any { it is AsyncResultAction<*> })
        assertTrue(processedActions.any { it is AsyncComplete })
        
        assertEquals(42, store.state.value.counter)
    }
    
    @Test
    fun `should trigger saga-like side effects after actions`() = runTest {
        val (store, processedActions) = createTrackedStoreForTest(TestState()) {
            middleware {
                middleware({ store, next, action ->
                    val result = next(action)
                    
                    if (action is IncrementAction) {
                        val message = AddMessageAction("Increment: ${action.value}")
                        store.dispatch(message)
                    }
                    
                    result
                })
            }
            
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    is AddMessageAction -> state.copy(messages = state.messages + action.message)
                    else -> state
                }
            }
        }
        
        store.dispatch(IncrementAction(5))
        
        advanceUntilIdle()
        
        assertTrue(processedActions.size >= 2, "Should have processed at least 2 actions")
        assertTrue(processedActions.any { it is IncrementAction })
        assertTrue(processedActions.any { it is AddMessageAction })
        
        assertEquals(5, store.state.value.counter)
        assertEquals(1, store.state.value.messages.size)
        assertEquals("Increment: 5", store.state.value.messages[0])
    }
}