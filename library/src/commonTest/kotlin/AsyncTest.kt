package duks

import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AsyncTest {
    
    data class TestState(
        val counter: Int = 0,
        val error: String? = null,
        val processed: MutableList<String> = mutableListOf(),
        val actionsDispatched: MutableList<String> = mutableListOf()
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
    fun `should handle failing async actions correctly using asyncMiddleware`() = runTest {

        val (store, dispatchedActions) = createTrackedStoreForTest(TestState()) {
            middleware {
                async()
            }
            
            reduceWith { state, action ->
                when (action) {
                    is AsyncProcessing -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("AsyncProcessing:${action.initiatedBy::class.simpleName}")
                        }
                        state.copy(actionsDispatched = newActions)
                    }
                    is AsyncResultAction<*> -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("AsyncResultAction:${action.initiatedBy::class.simpleName}")
                        }
                        if (action.result.isFailure) {
                            val error = action.result.exceptionOrNull()?.message ?: "Unknown error"
                            state.copy(error = error, actionsDispatched = newActions)
                        } else {
                            state.copy(actionsDispatched = newActions)
                        }
                    }
                    is AsyncComplete -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("AsyncComplete:${action.initiatedBy::class.simpleName}")
                        }
                        state.copy(actionsDispatched = newActions)
                    }
                    else -> state
                }
            }
        }

        val errorMessage = "Test error message"
        dispatchAndAdvance(store, FailingAsyncAction(errorMessage))

        var allMessagesProcessed = false
        while(!allMessagesProcessed) {
            delay(10)
            val actions = dispatchedActions.toList()
            allMessagesProcessed = actions.any { it is AsyncComplete && it.initiatedBy is FailingAsyncAction }
        }
        advanceUntilIdle()
        val actions = dispatchedActions.toList()
        assertEquals(errorMessage, store.state.value.error,
            "Error from failing async action should be captured in state")
        
        assertTrue(actions.any { it is AsyncProcessing && it.initiatedBy is FailingAsyncAction },
            "AsyncProcessing action should be dispatched")
        assertTrue(actions.any { it is AsyncResultAction<*> && it.initiatedBy is FailingAsyncAction },
            "AsyncResultAction action should be dispatched")
        assertTrue(actions.any { it is AsyncComplete && it.initiatedBy is FailingAsyncAction },
            "AsyncComplete action should be dispatched")

        assertTrue(store.state.value.actionsDispatched.contains("AsyncProcessing:FailingAsyncAction"),
            "AsyncProcessing action should be tracked in state")
        assertTrue(store.state.value.actionsDispatched.contains("AsyncResultAction:FailingAsyncAction"),
            "AsyncResultAction action should be tracked in state")
        assertTrue(store.state.value.actionsDispatched.contains("AsyncComplete:FailingAsyncAction"),
            "AsyncComplete action should be tracked in state")
    }
    
    @Test
    fun `should process successful async actions using asyncMiddleware`() = runTest {

        val (store, dispatchedActions) = createTrackedStoreForTest(TestState()) {
            middleware {
                async()
            }
            
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> {
                        state.copy(counter = state.counter + action.value)
                    }
                    is AsyncProcessing -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("AsyncProcessing:${action.initiatedBy::class.simpleName}")
                        }
                        state.copy(actionsDispatched = newActions)
                    }
                    is AsyncResultAction<*> -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("AsyncResultAction:${action.initiatedBy::class.simpleName}")
                        }
                        if (action.result.isSuccess) {
                            val value = action.result.getOrNull()
                            if (value is Int) {
                                state.copy(counter = state.counter + value, actionsDispatched = newActions)
                            } else {
                                state.copy(actionsDispatched = newActions)
                            }
                        } else {
                            state.copy(actionsDispatched = newActions)
                        }
                    }
                    is AsyncComplete -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("AsyncComplete:${action.initiatedBy::class.simpleName}")
                        }
                        state.copy(actionsDispatched = newActions)
                    }
                    else -> state
                }
            }
        }
        
        store.dispatch(IncrementAction(5))
        assertEquals(5, store.state.value.counter, "Regular action should update counter")

        dispatchAndAdvance(store, SimpleAsyncAction(10))

        while(store.state.value.counter != 25) {
            delay(200)
        }

        advanceUntilIdle()
        val actions = dispatchedActions.toList()
        assertEquals(25, store.state.value.counter,
            "Async action should update counter with value*2 added to current count")
        
        assertTrue(actions.any { it is AsyncProcessing && it.initiatedBy is SimpleAsyncAction },
            "AsyncProcessing action should be dispatched")
        assertTrue(actions.any { it is AsyncResultAction<*> && it.initiatedBy is SimpleAsyncAction },
            "AsyncResultAction action should be dispatched")
        assertTrue(actions.any { it is AsyncComplete && it.initiatedBy is SimpleAsyncAction },
            "AsyncComplete action should be dispatched")
        
        assertTrue(store.state.value.actionsDispatched.contains("AsyncProcessing:SimpleAsyncAction"),
            "AsyncProcessing action should be tracked in state")
        assertTrue(store.state.value.actionsDispatched.contains("AsyncResultAction:SimpleAsyncAction"),
            "AsyncResultAction action should be tracked in state")
        assertTrue(store.state.value.actionsDispatched.contains("AsyncComplete:SimpleAsyncAction"),
            "AsyncComplete action should be tracked in state")
    }
    
    @Test
    fun `should handle multiple async actions sequentially using asyncMiddleware`() = runTest {

        val (store, dispatchedActions) = createTrackedStoreForTest(TestState()) {
            middleware {
                async()
            }
            
            reduceWith { state, action ->
                when (action) {
                    is AsyncProcessing -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("AsyncProcessing:${action.initiatedBy::class.simpleName}")
                        }
                        state.copy(actionsDispatched = newActions)
                    }
                    is AsyncResultAction<*> -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("AsyncResultAction:${action.initiatedBy::class.simpleName}")
                        }
                        if (action.result.isSuccess) {
                            val value = action.result.getOrNull()
                            if (value is Int) {
                                val newProcessed = state.processed.toMutableList().apply {
                                    add("Processed:$value")
                                }
                                state.copy(
                                    counter = state.counter + value,
                                    processed = newProcessed,
                                    actionsDispatched = newActions
                                )
                            } else {
                                state.copy(actionsDispatched = newActions)
                            }
                        } else {
                            state.copy(error = action.result.exceptionOrNull()?.message, actionsDispatched = newActions)
                        }
                    }
                    is AsyncComplete -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("AsyncComplete:${action.initiatedBy::class.simpleName}")
                        }
                        state.copy(actionsDispatched = newActions)
                    }
                    else -> state
                }
            }
        }

        dispatchAndAdvance(store, SimpleAsyncAction(10))

        dispatchAndAdvance(store, SimpleAsyncAction(20))

        dispatchAndAdvance(store, SimpleAsyncAction(30))

        while(store.state.value.counter != 120) {
            delay(10)
        }

        val actions = dispatchedActions.toList()
        // Verify that counter has the sum of all async action results (10*2 + 20*2 + 30*2 = 120)
        assertEquals(120, store.state.value.counter, 
            "Counter should include all processed results")
        
        // Verify that all async actions were processed
        assertTrue(store.state.value.processed.contains("Processed:20"), 
            "Should have processed first action")
        assertTrue(store.state.value.processed.contains("Processed:40"), 
            "Should have processed second action")
        assertTrue(store.state.value.processed.contains("Processed:60"), 
            "Should have processed third action")
        
        // Verify the correct number of lifecycle actions were dispatched
        assertEquals(3, actions.count { it is AsyncProcessing },
            "Should have dispatched 3 AsyncProcessing actions")
        assertEquals(3, actions.count { it is AsyncResultAction<*> },
            "Should have dispatched 3 AsyncResultAction actions")
        assertEquals(3, actions.count { it is AsyncComplete },
            "Should have dispatched 3 AsyncComplete actions")
    }
}