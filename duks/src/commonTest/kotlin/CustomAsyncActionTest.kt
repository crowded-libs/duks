package duks

import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CustomAsyncActionTest {

    data class TestState(
        val counter: Int = 0,
        val customActions: MutableList<String> = mutableListOf(),
        val customResults: MutableList<String> = mutableListOf()
    ) : StateModel

    data class CustomProcessingAction(override val initiatedBy: Action, val metadata: String) : AsyncInitiatedByAction
    data class CustomResultAction<T>(override val initiatedBy: Action, val result: T, val metadata: String) : AsyncInitiatedByAction
    data class CustomErrorAction(override val initiatedBy: Action, val error: Throwable, val metadata: String) : AsyncInitiatedByAction
    data class CustomCompleteAction(override val initiatedBy: Action, val metadata: String) : AsyncInitiatedByAction

    data class CustomizedAsyncAction(val value: Int, val metadata: String) : AsyncAction<Int> {
        override suspend fun getResult(stateAccessor: StateAccessor): Result<Int> {
            yield() // cooperative async boundary
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

    data class TypedResultAsyncAction<T : Any>(val value: T) : AsyncAction<T> {
        override suspend fun getResult(stateAccessor: StateAccessor): Result<T> {
            yield() // cooperative async boundary
            return Result.success(value)
        }
    }

    data class CustomErrorHandlingAction(val shouldFail: Boolean, val errorMessage: String) : AsyncAction<Int> {
        override suspend fun getResult(stateAccessor: StateAccessor): Result<Int> {
            yield() // cooperative async boundary
            return if (shouldFail) {
                Result.failure(RuntimeException(errorMessage))
            } else {
                Result.success(42)
            }
        }

        override fun createErrorAction(error: Throwable): Action =
            CustomErrorAction(this, error, "custom-error-$errorMessage")
    }

    @Test
    fun `should support custom action creation methods`() = runTest(timeout = 5.seconds) {
        val (store, dispatchedActions) = createTrackedStoreForTest(TestState()) {
            middleware { async() }
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

        awaitUntil { dispatchedActions.any { it is CustomCompleteAction } }
        awaitState(store.state) { it.customActions.contains("CustomComplete:$metadata") }

        val actions = dispatchedActions.toList()

        assertTrue(actions.any { it is CustomProcessingAction && it.metadata == metadata })
        assertTrue(actions.any { it is CustomResultAction<*> && it.metadata == metadata })
        assertTrue(actions.any { it is CustomCompleteAction && it.metadata == metadata })

        assertEquals(20, store.state.value.counter)
        assertTrue(store.state.value.customActions.contains("CustomProcessing:$metadata"))
        assertTrue(store.state.value.customActions.contains("CustomResult:$metadata"))
        assertTrue(store.state.value.customActions.contains("CustomComplete:$metadata"))
        assertTrue(store.state.value.customResults.contains("Result:20"))
    }

    @Test
    fun `should support typed result async actions`() = runTest(timeout = 5.seconds) {
        val (store, dispatchedActions) = createTrackedStoreForTest(TestState()) {
            middleware { async() }
            reduceWith { state, action ->
                when (action) {
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
                    else -> state
                }
            }
        }

        dispatchAndAdvance(store, TypedResultAsyncAction(42))
        awaitState(store.state) { it.customResults.any { r -> r.startsWith("Int:") } }

        dispatchAndAdvance(store, TypedResultAsyncAction("Hello"))
        awaitState(store.state) { it.customResults.any { r -> r.startsWith("String:") } }

        assertEquals(42, store.state.value.counter)
        assertTrue(store.state.value.customResults.contains("Int:42"))
        assertTrue(store.state.value.customResults.contains("String:Hello"))

        val actions = dispatchedActions.toList()
        assertEquals(
            6,
            actions.count { it is AsyncProcessing || it is AsyncResultAction<*> || it is AsyncComplete },
            "Should have dispatched 6 async lifecycle actions (3 for each async action)"
        )
    }

    @Test
    fun `should support custom error handling`() = runTest(timeout = 5.seconds) {
        val (store, dispatchedActions) = createTrackedStoreForTest(TestState()) {
            middleware { async() }
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

        awaitUntil { dispatchedActions.any { it is CustomErrorAction } }
        awaitState(store.state) { it.customActions.isNotEmpty() }

        val actions = dispatchedActions.toList()
        assertTrue(actions.any { it is CustomErrorAction })

        val customErrorAction = actions.find { it is CustomErrorAction } as CustomErrorAction
        val expectedMetadata = "custom-error-$errorMessage"
        assertEquals(expectedMetadata, customErrorAction.metadata)
        assertEquals(errorMessage, customErrorAction.error.message)

        assertTrue(store.state.value.customActions.contains("CustomError:$expectedMetadata"))
        assertTrue(store.state.value.customResults.contains("Error:$errorMessage"))
    }
}
