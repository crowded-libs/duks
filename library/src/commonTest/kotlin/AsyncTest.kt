package duks

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AsyncTest {
    
    data class TestState(
        val counter: Int = 0,
        val error: String? = null,
        val processed: MutableList<String> = mutableListOf()
    ) : StateModel
    
    data class IncrementAction(val value: Int = 1) : Action
    
    data class SimpleAsyncAction(val value: Int) : AsyncAction<Int> {
        override suspend fun execute(): Result<Int> {
            delay(10)
            return Result.success(value * 2)
        }
    }
    
    data class FailingAsyncAction(val error: String) : AsyncAction<Int> {
        override suspend fun execute(): Result<Int> {
            delay(10)
            return Result.failure(RuntimeException(error))
        }
    }
    
    @Test
    fun `should handle failing async actions correctly`() = runTest {
        val initialState = TestState()
        
        val testMiddleware: Middleware<TestState> = { store, next, action ->
            if (action is FailingAsyncAction) {
                store.dispatch(AsyncProcessing(action))
                
                val result = Result.failure<Int>(RuntimeException(action.error))
                store.dispatch(AsyncResultAction(action, result))
                
                store.dispatch(AsyncComplete(action))
                
                action
            } else {
                next(action)
            }
        }
        
        val store = createStoreForTest(initialState) {
            middleware {
                middleware(testMiddleware)
            }
            
            reduceWith { state, action ->
                when (action) {
                    is AsyncResultAction<*> -> {
                        if (action.result.isFailure) {
                            val error = action.result.exceptionOrNull()?.message ?: "Unknown error"
                            state.copy(error = error)
                        } else {
                            state
                        }
                    }
                    else -> state
                }
            }
        }
        
        val errorMessage = "Test error message"
        store.dispatch(FailingAsyncAction(errorMessage))
        
        assertEquals(errorMessage, store.state.value.error, 
            "Error from failing async action should be captured in state")
    }
    
    @Test
    fun `should process successful async actions`() = runTest {
        val initialState = TestState()
        val actionsProcessed = mutableListOf<String>()
        
        val testMiddleware: Middleware<TestState> = { store, next, action ->
            actionsProcessed.add("Received:${action::class.simpleName}")
            
            if (action is SimpleAsyncAction) {
                val processingAction = AsyncProcessing(action)
                store.dispatch(processingAction)
                
                val result = Result.success(action.value * 2)
                val resultAction = AsyncResultAction(action, result)
                store.dispatch(resultAction)
                
                val completeAction = AsyncComplete(action)
                store.dispatch(completeAction)
                
                action
            } else {
                next(action)
            }
        }
        
        val store = createStoreForTest(initialState) {
            middleware {
                middleware(testMiddleware)
            }
            
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> {
                        state.copy(counter = state.counter + action.value)
                    }
                    is AsyncResultAction<*> -> {
                        if (action.result.isSuccess) {
                            val value = action.result.getOrNull()
                            if (value is Int) {
                                state.copy(counter = state.counter + value)
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
        
        store.dispatch(IncrementAction(5))
        assertEquals(5, store.state.value.counter, "Regular action should update counter")
        
        store.dispatch(SimpleAsyncAction(10))
        
        assertEquals(25, store.state.value.counter, 
            "Async action should update counter with value*2 added to current count")
        
        assertTrue(actionsProcessed.contains("Received:SimpleAsyncAction"),
            "Should have processed the original action")
    }
    
    @Test
    fun `should handle multiple async actions sequentially`() = runTest {
        val initialState = TestState()
        val processed = mutableListOf<Int>()
        
        val testMiddleware: Middleware<TestState> = { store, next, action ->
            if (action is SimpleAsyncAction) {
                store.dispatch(AsyncProcessing(action))
                
                val value = action.value * 2
                processed.add(value)
                val result = Result.success(value)
                store.dispatch(AsyncResultAction(action, result))
                
                store.dispatch(AsyncComplete(action))
                
                action
            } else {
                next(action)
            }
        }
        
        val store = createStoreForTest(initialState) {
            middleware {
                middleware(testMiddleware)
            }
            
            reduceWith { state, action ->
                when (action) {
                    is AsyncResultAction<*> -> {
                        if (action.result.isSuccess) {
                            val value = action.result.getOrNull()
                            if (value is Int) {
                                val newProcessed = state.processed.toMutableList().apply {
                                    add("Processed:$value")
                                }
                                state.copy(
                                    counter = state.counter + value,
                                    processed = newProcessed
                                )
                            } else {
                                state
                            }
                        } else {
                            state.copy(error = action.result.exceptionOrNull()?.message)
                        }
                    }
                    else -> state
                }
            }
        }
        
        store.dispatch(SimpleAsyncAction(10))
        store.dispatch(SimpleAsyncAction(20))
        store.dispatch(SimpleAsyncAction(30))
        
        assertEquals(120, store.state.value.counter, 
            "Counter should include all processed results")
        
        assertTrue(store.state.value.processed.contains("Processed:20"), 
            "Should have processed first action")
        assertTrue(store.state.value.processed.contains("Processed:40"), 
            "Should have processed second action")
        assertTrue(store.state.value.processed.contains("Processed:60"), 
            "Should have processed third action")
    }
}