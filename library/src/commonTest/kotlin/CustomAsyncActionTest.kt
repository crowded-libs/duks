package duks

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CustomAsyncActionTest {

    data class TestState(
        val counter: Int = 0,
        val customActions: MutableList<String> = mutableListOf(),
        val customResults: MutableList<String> = mutableListOf()
    ) : StateModel

    // Custom action types for testing overridden create methods
    data class CustomProcessingAction(override val initiatedBy: Action, val metadata: String) : AsyncInitiatedByAction
    data class CustomResultAction<T>(override val initiatedBy: Action, val result: T, val metadata: String) : AsyncInitiatedByAction
    data class CustomErrorAction(override val initiatedBy: Action, val error: Throwable, val metadata: String) : AsyncInitiatedByAction
    data class CustomCompleteAction(override val initiatedBy: Action, val metadata: String) : AsyncInitiatedByAction

    // AsyncAction implementation with custom action creation methods
    data class CustomizedAsyncAction(val value: Int, val metadata: String) : AsyncAction<Int> {
        override suspend fun execute(): Result<Int> {
            delay(10)
            return Result.success(value * 2)
        }

        override fun createProcessingAction(): Action = 
            CustomProcessingAction(this, metadata)

        override fun createResultAction(result: Int): Action = 
            CustomResultAction(this, result, metadata)

        override fun createErrorAction(error: Throwable): Action = 
            CustomErrorAction(this, error, metadata)

        override fun createCompleteAction(): Action = 
            CustomCompleteAction(this, metadata)
    }

    // AsyncAction implementation with a specific result type for type safety
    data class TypedResultAsyncAction<T : Any>(val value: T) : AsyncAction<T> {
        override suspend fun execute(): Result<T> {
            delay(10)
            return Result.success(value)
        }
    }

    // AsyncAction implementation with custom error handling
    data class CustomErrorHandlingAction(val shouldFail: Boolean, val errorMessage: String) : AsyncAction<Int> {
        override suspend fun execute(): Result<Int> {
            delay(10)
            return if (shouldFail) {
                Result.failure(RuntimeException(errorMessage))
            } else {
                Result.success(42)
            }
        }

        override fun createErrorAction(error: Throwable): Action = 
            CustomErrorAction(this, error, "custom-error-${errorMessage}")
    }

    @Test
    fun `should support custom action creation methods`() = runTest {
        val (store, dispatchedActions) = createTrackedStoreForTest(TestState()) {
            middleware {
                async()
            }

            reduceWith { state, action ->
                when (action) {
                    is CustomProcessingAction -> {
                        val newActions = state.customActions.toMutableList().apply {
                            add("CustomProcessing:${action.metadata}")
                        }
                        state.copy(customActions = newActions)
                    }
                    is CustomResultAction<*> -> {
                        val newActions = state.customActions.toMutableList().apply {
                            add("CustomResult:${action.metadata}")
                        }
                        val newResults = state.customResults.toMutableList().apply {
                            add("Result:${action.result}")
                        }
                        val value = action.result
                        if (value is Int) {
                            state.copy(
                                counter = state.counter + value,
                                customActions = newActions,
                                customResults = newResults
                            )
                        } else {
                            state.copy(
                                customActions = newActions,
                                customResults = newResults
                            )
                        }
                    }
                    is CustomErrorAction -> {
                        val newActions = state.customActions.toMutableList().apply {
                            add("CustomError:${action.metadata}")
                        }
                        state.copy(customActions = newActions)
                    }
                    is CustomCompleteAction -> {
                        val newActions = state.customActions.toMutableList().apply {
                            add("CustomComplete:${action.metadata}")
                        }
                        state.copy(customActions = newActions)
                    }
                    else -> state
                }
            }
        }

        val metadata = "test-metadata"
        dispatchAndAdvance(store, CustomizedAsyncAction(10, metadata))

        // Wait for all actions to be processed
        var allActionsProcessed = false
        while (!allActionsProcessed) {
            delay(10)
            val actions = dispatchedActions.toList()
            allActionsProcessed = actions.any { it is CustomCompleteAction }
        }

        advanceUntilIdle()
        val actions = dispatchedActions.toList()

        // Verify custom actions were dispatched
        assertTrue(actions.any { it is CustomProcessingAction && it.metadata == metadata },
            "CustomProcessingAction should be dispatched with correct metadata")
        assertTrue(actions.any { it is CustomResultAction<*> && it.metadata == metadata },
            "CustomResultAction should be dispatched with correct metadata")
        assertTrue(actions.any { it is CustomCompleteAction && it.metadata == metadata },
            "CustomCompleteAction should be dispatched with correct metadata")

        // Verify state was updated correctly
        assertEquals(20, store.state.value.counter,
            "Counter should be updated with result value")
        assertTrue(store.state.value.customActions.contains("CustomProcessing:$metadata"),
            "CustomProcessing action should be tracked in state")
        assertTrue(store.state.value.customActions.contains("CustomResult:$metadata"),
            "CustomResult action should be tracked in state")
        assertTrue(store.state.value.customActions.contains("CustomComplete:$metadata"),
            "CustomComplete action should be tracked in state")
        assertTrue(store.state.value.customResults.contains("Result:20"),
            "Result value should be tracked in state")
    }

    @Test
    fun `should support typed result async actions`() = runTest {
        val (store, dispatchedActions) = createTrackedStoreForTest(TestState()) {
            middleware {
                async()
            }

            reduceWith { state, action ->
                when (action) {
                    is AsyncProcessing -> {
                        state
                    }
                    is AsyncResultAction<*> -> {
                        val result = action.result
                        val newResults = state.customResults.toMutableList()

                        when (result) {
                            is Int -> {
                                newResults.add("Int:$result")
                                state.copy(counter = result, customResults = newResults)
                            }
                            is String -> {
                                newResults.add("String:$result")
                                state.copy(customResults = newResults)
                            }
                            else -> state
                        }
                    }
                    is AsyncComplete -> {
                        state
                    }
                    else -> state
                }
            }
        }

        // Test with Int result type
        dispatchAndAdvance(store, TypedResultAsyncAction(42))

        // Wait for Int result to be processed
        var intResultProcessed = false
        while (!intResultProcessed) {
            delay(10)
            intResultProcessed = store.state.value.customResults.any { it.startsWith("Int:") }
        }

        // Test with String result type
        dispatchAndAdvance(store, TypedResultAsyncAction("Hello"))

        // Wait for String result to be processed
        var stringResultProcessed = false
        while (!stringResultProcessed) {
            delay(10)
            stringResultProcessed = store.state.value.customResults.any { it.startsWith("String:") }
        }

        advanceUntilIdle()

        // Verify both result types were processed correctly
        assertEquals(42, store.state.value.counter, 
            "Counter should be updated with Int result")
        assertTrue(store.state.value.customResults.contains("Int:42"),
            "Int result should be tracked in state")
        assertTrue(store.state.value.customResults.contains("String:Hello"),
            "String result should be tracked in state")

        // Verify correct actions were dispatched
        val actions = dispatchedActions.toList()
        assertEquals(6, actions.count { it is AsyncProcessing || it is AsyncResultAction<*> || it is AsyncComplete },
            "Should have dispatched 6 async lifecycle actions (3 for each async action)")
    }

    @Test
    fun `should support custom error handling`() = runTest {
        val (store, dispatchedActions) = createTrackedStoreForTest(TestState()) {
            middleware {
                async()
            }

            reduceWith { state, action ->
                when (action) {
                    is CustomErrorAction -> {
                        val newActions = state.customActions.toMutableList().apply {
                            add("CustomError:${action.metadata}")
                        }
                        val newResults = state.customResults.toMutableList().apply {
                            add("Error:${action.error.message}")
                        }
                        state.copy(
                            customActions = newActions,
                            customResults = newResults
                        )
                    }
                    else -> state
                }
            }
        }

        val errorMessage = "test-error-message"
        dispatchAndAdvance(store, CustomErrorHandlingAction(true, errorMessage))

        // Wait for error to be processed
        var errorProcessed = false
        while (!errorProcessed) {
            delay(10)
            val actions = dispatchedActions.toList()
            errorProcessed = actions.any { it is CustomErrorAction }
        }

        advanceUntilIdle()
        val actions = dispatchedActions.toList()

        // Verify custom error action was dispatched
        assertTrue(actions.any { it is CustomErrorAction },
            "CustomErrorAction should be dispatched")

        // Verify the custom error action has the expected metadata
        val customErrorAction = actions.find { it is CustomErrorAction } as CustomErrorAction
        val expectedMetadata = "custom-error-$errorMessage"
        assertEquals(expectedMetadata, customErrorAction.metadata,
            "CustomErrorAction should have the expected metadata")
        assertEquals(errorMessage, customErrorAction.error.message,
            "CustomErrorAction should have the expected error message")

        // Wait for state to be updated
        var stateUpdated = false
        while (!stateUpdated) {
            delay(10)
            stateUpdated = store.state.value.customActions.isNotEmpty()
        }

        // Verify state was updated correctly
        assertTrue(store.state.value.customActions.contains("CustomError:$expectedMetadata"),
            "CustomError action should be tracked in state")
        assertTrue(store.state.value.customResults.contains("Error:$errorMessage"),
            "Error message should be tracked in state")
    }
}
