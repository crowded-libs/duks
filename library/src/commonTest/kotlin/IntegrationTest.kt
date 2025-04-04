package duks

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntegrationTest {
    
    data class TestState(
        val counter: Int = 0,
        val messages: List<String> = emptyList(),
        val errors: List<String> = emptyList(),
        val asyncOperations: Int = 0,
        val cacheHits: Int = 0,
        val asyncResults: List<String> = emptyList(),
        val sagaResults: List<String> = emptyList()
    ) : StateModel
    
    data class IncrementAction(val value: Int = 1) : Action
    data class AddMessageAction(val message: String) : Action
    data class ErrorAction(val errorMessage: String = "Test error") : Action
    
    data class TestAsyncAction(val id: Int, val value: Int) : AsyncAction<Int> {
        override suspend fun execute(): Result<Int> {
            return Result.success(value * 2)
        }
    }
    
    data class FailingAsyncAction(val id: Int, val errorMessage: String) : AsyncAction<Int> {
        override suspend fun execute(): Result<Int> {
            return Result.failure(RuntimeException(errorMessage))
        }
    }
    
    @Test
    fun `should log actions with logger middleware integration`() = runTest {
        val logMessages = mutableListOf<String>()
        
        val store = createStoreForTest(TestState()) {
            middleware {
                logging({ msg ->
                    logMessages.add(msg)
                })
            }
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    else -> state
                }
            }
        }
        
        store.dispatch(IncrementAction(5))
        
        advanceUntilIdle()
        
        assertEquals(2, logMessages.size)
        assertTrue(logMessages[0].contains("Action: IncrementAction"))
        assertTrue(logMessages[1].contains("After Action: IncrementAction"))
        
        assertEquals(5, store.state.value.counter)
    }
    
    @Test
    fun `should handle exceptions in integrated middleware chain`() = runTest {
        val errorLogs = mutableListOf<String>()
        
        val store = createStoreForTest(TestState()) {
            middleware {
                middleware { s, next, action ->
                    next(action)
                }
                
                middleware(exceptionMiddleware<TestState> { errorMessage ->
                    errorLogs.add(errorMessage)
                })
            }
            
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    is ErrorAction -> throw RuntimeException(action.errorMessage)
                    else -> state
                }
            }
        }
        
        store.dispatch(IncrementAction(1))
        advanceUntilIdle()
        
        assertEquals(1, store.state.value.counter)
        assertTrue(errorLogs.isEmpty())
        
        store.dispatch(ErrorAction("Test error"))
        advanceUntilIdle()
        
        assertTrue(errorLogs.isNotEmpty())
        assertTrue(errorLogs.any { it.contains("Test error") })
    }
    
    @Test
    fun `should integrate async actions with saga-like effects`() = runTest {
        val store = createStoreForTest(TestState()) {
            reduceWith { state, action ->
                when (action) {
                    is AsyncProcessing -> state.copy(asyncOperations = state.asyncOperations + 1)
                    is AsyncComplete -> state.copy(asyncOperations = state.asyncOperations - 1)
                    is AsyncResultAction<*> -> {
                        if (action.result.isSuccess) {
                            val value = action.result.getOrNull()
                            if (value is Int) {
                                state.copy(counter = state.counter + value)
                            } else {
                                state
                            }
                        } else {
                            val errorMsg = action.result.exceptionOrNull()?.message ?: "Unknown error"
                            state.copy(errors = state.errors + errorMsg)
                        }
                    }
                    is AddMessageAction -> state.copy(messages = state.messages + action.message)
                    else -> state
                }
            }
        }
        
        val successAction = TestAsyncAction(1, 5)
        store.dispatch(AsyncProcessing(successAction))
        runCurrent()
        assertEquals(1, store.state.value.asyncOperations, "Async operations should be incremented")
        
        store.dispatch(AsyncResultAction(successAction, Result.success(10)))
        runCurrent()
        assertEquals(10, store.state.value.counter, "Counter should include successful result")
        
        store.dispatch(AddMessageAction("Saga processed async result: 10"))
        runCurrent()
        
        store.dispatch(AsyncComplete(successAction))
        runCurrent()
        assertEquals(0, store.state.value.asyncOperations, "Async operations should be back to 0")
        
        assertEquals(10, store.state.value.counter, "Counter should include successful result")
        assertEquals(1, store.state.value.messages.size, "Should have one message")
        assertEquals("Saga processed async result: 10", store.state.value.messages[0], 
                     "Message should contain result")
        
        val failingAction = FailingAsyncAction(2, "Test failure")
        store.dispatch(AsyncProcessing(failingAction))
        runCurrent()
        assertEquals(1, store.state.value.asyncOperations, "Async operations should be incremented")
        
        val failureResult = Result.failure<Int>(RuntimeException("Test failure"))
        store.dispatch(AsyncResultAction(failingAction, failureResult))
        runCurrent()
        assertEquals(1, store.state.value.errors.size, "Should have one error")
        assertEquals("Test failure", store.state.value.errors[0], "Error message should match")
        
        store.dispatch(AddMessageAction("Saga handled error: Test failure"))
        runCurrent()
        
        store.dispatch(AsyncComplete(failingAction))
        runCurrent()
        assertEquals(0, store.state.value.asyncOperations, "Async operations should be back to 0")
        
        assertEquals(10, store.state.value.counter, "Counter should remain unchanged")
        assertEquals(2, store.state.value.messages.size, "Should have two messages")
        assertEquals("Saga handled error: Test failure", store.state.value.messages[1], 
                     "Second message should contain error info")
        assertEquals(1, store.state.value.errors.size, "Should have one error")
    }
    
    @Test
    fun `should integrate all middleware types together`() = runTest {
        val logs = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val testCache = TestActionCache()
        
        val store = createStoreForTest(TestState()) {
            middleware {
                middleware(loggerMiddleware<TestState> { logs.add(it) })
                
                middleware(exceptionMiddleware<TestState> { errors.add(it) })
                
                caching(testCache)
            }
            
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    is AddMessageAction -> state.copy(messages = state.messages + action.message)
                    is AsyncProcessing -> state.copy(asyncOperations = state.asyncOperations + 1)
                    is AsyncComplete -> state.copy(asyncOperations = state.asyncOperations - 1)
                    is AsyncResultAction<*> -> {
                        if (action.result.isSuccess) {
                            val value = action.result.getOrNull()
                            if (value is Int) {
                                state.copy(counter = state.counter + value)
                            } else {
                                state
                            }
                        } else {
                            val errorMsg = action.result.exceptionOrNull()?.message ?: "Unknown error"
                            state.copy(errors = state.errors + errorMsg)
                        }
                    }
                    is ErrorAction -> throw RuntimeException(action.errorMessage)
                    else -> state
                }
            }
        }
        
        store.dispatch(IncrementAction(2))
        runCurrent()
        
        assertEquals(2, store.state.value.counter, "Counter should be 2")
        assertTrue(logs.any { it.contains("Action: IncrementAction") }, "Logger should log action")
        
        val asyncAction = TestAsyncAction(1, 3)
        
        store.dispatch(AsyncProcessing(asyncAction))
        runCurrent()
        assertEquals(1, store.state.value.asyncOperations, "Should have one async operation in progress")
        
        store.dispatch(AsyncResultAction(asyncAction, Result.success(6)))
        runCurrent()
        assertEquals(6, store.state.value.counter - 2, "Counter should be incremented by async result")
        
        store.dispatch(AsyncComplete(asyncAction))
        runCurrent()
        assertEquals(0, store.state.value.asyncOperations, "Async operations should be back to 0")
        
        store.dispatch(AddMessageAction("Async result processed: 6"))
        runCurrent()
        
        assertEquals(8, store.state.value.counter, "Counter should be 2 + 6 = 8")
        assertEquals(1, store.state.value.messages.size, "Should have one message")
        
        errors.clear()
        store.dispatch(ErrorAction("Integration error"))
        runCurrent()
        advanceUntilIdle()
        
        assertTrue(errors.isNotEmpty(), "Error should be caught by exception middleware")
        assertEquals(8, store.state.value.counter, "Counter should remain unchanged after error")
        
        val transformedAction = IncrementAction(10)
        store.dispatch(transformedAction)
        runCurrent()
        advanceUntilIdle()
        
        assertEquals(18, store.state.value.counter, "Counter should be 8 + 10 = 18")
        
        store.dispatch(IncrementAction(10))
        runCurrent()
        advanceUntilIdle()
        
        assertEquals(28, store.state.value.counter, "Counter should be 18 + 10 = 28")
        
        val asyncAction2 = TestAsyncAction(2, 5)
        store.dispatch(AsyncProcessing(asyncAction2))
        store.dispatch(AsyncResultAction(asyncAction2, Result.success(10)))
        store.dispatch(AsyncComplete(asyncAction2))
        runCurrent()
        
        store.dispatch(AddMessageAction("Combined test result"))
        runCurrent()
        
        store.dispatch(IncrementAction(5))
        runCurrent()
        advanceUntilIdle()
        
        assertEquals(43, store.state.value.counter, "Counter should include all increments (28 + 10 + 5)")
        assertEquals(2, store.state.value.messages.size, "Should have two messages")
        assertEquals("Combined test result", store.state.value.messages[1], "Second message should match")
    }
}