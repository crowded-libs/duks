package duks

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AsyncTest {

    data class TestState(
        val counter: Int = 0,
        val error: String? = null,
        val processed: MutableList<String> = mutableListOf(),
        val actionsDispatched: MutableList<String> = mutableListOf()
    ) : StateModel

    data class IncrementAction(val value: Int = 1) : Action

    data class SimpleAsyncAction(val value: Int) : AsyncAction<Int> {
        override suspend fun getResult(stateAccessor: StateAccessor): Result<Int> {
            delay(10)
            return Result.success(value * 2)
        }
    }

    data class FailingAsyncAction(val error: String) : AsyncAction<Int> {
        override suspend fun getResult(stateAccessor: StateAccessor): Result<Int> {
            delay(10)
            return Result.failure(RuntimeException(error))
        }
    }

    @Test
    fun `should handle failing async actions correctly using asyncMiddleware`() = runTest(timeout = 5.seconds) {

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
                        state.copy(actionsDispatched = newActions)
                    }
                    is AsyncError -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("AsyncError:${action.initiatedBy::class.simpleName}")
                        }
                        val error = action.error.message ?: "Unknown error"
                        state.copy(error = error, actionsDispatched = newActions)
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

        // Wait for AsyncComplete action to be tracked in state
        var completeActionTracked = false
        while(!completeActionTracked) {
            delay(10)
            completeActionTracked = store.state.value.actionsDispatched.contains("AsyncComplete:FailingAsyncAction")
        }
        advanceUntilIdle()
        val actions = dispatchedActions.toList()
        assertEquals(errorMessage, store.state.value.error,
            "Error from failing async action should be captured in state")

        assertTrue(actions.any { it is AsyncProcessing && it.initiatedBy is FailingAsyncAction },
            "AsyncProcessing action should be dispatched")
        assertTrue(actions.any { it is AsyncError && it.initiatedBy is FailingAsyncAction },
            "AsyncError action should be dispatched")
        assertTrue(actions.any { it is AsyncComplete && it.initiatedBy is FailingAsyncAction },
            "AsyncComplete action should be dispatched")

        assertTrue(store.state.value.actionsDispatched.contains("AsyncProcessing:FailingAsyncAction"),
            "AsyncProcessing action should be tracked in state")
        assertTrue(store.state.value.actionsDispatched.contains("AsyncError:FailingAsyncAction"),
            "AsyncError action should be tracked in state")
        assertTrue(store.state.value.actionsDispatched.contains("AsyncComplete:FailingAsyncAction"),
            "AsyncComplete action should be tracked in state")
    }

    @Test
    fun `should process successful async actions using asyncMiddleware`() = runTest(timeout = 5.seconds) {

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
                        val value = action.result
                        if (value is Int) {
                            state.copy(counter = state.counter + value, actionsDispatched = newActions)
                        } else {
                            state.copy(actionsDispatched = newActions)
                        }
                    }
                    is AsyncError -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("AsyncError:${action.initiatedBy::class.simpleName}")
                        }
                        val error = action.error.message ?: "Unknown error"
                        state.copy(error = error, actionsDispatched = newActions)
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
        store.state.first { it.counter == 5 }
        assertEquals(5, store.state.value.counter, "Regular action should update counter")

        dispatchAndAdvance(store, SimpleAsyncAction(10))

        // Wait for counter to reach expected value
        store.state.first { it.counter == 25 }

        // Wait for AsyncComplete action to be dispatched
        var completeActionFound = false
        while(!completeActionFound) {
            delay(10)
            completeActionFound = dispatchedActions.any { it is AsyncComplete && it.initiatedBy is SimpleAsyncAction }
        }

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
    fun `should handle multiple async actions sequentially using asyncMiddleware`() = runTest(timeout = 5.seconds) {

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
                        val value = action.result
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
                    }
                    is AsyncError -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("AsyncError:${action.initiatedBy::class.simpleName}")
                        }
                        val error = action.error.message ?: "Unknown error"
                        state.copy(error = error, actionsDispatched = newActions)
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

        // Wait for counter to reach expected value
        while(store.state.value.counter != 120) {
            delay(10)
        }

        // Wait for all AsyncComplete actions to be dispatched
        var completeActionsCount = 0
        while(completeActionsCount < 3) {
            delay(10)
            completeActionsCount = dispatchedActions.count { it is AsyncComplete }
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

    // Custom action for progress updates
    data class ProgressUpdateAction(val current: Int, val total: Int) : Action

    // Custom AsyncFlowAction implementation
    data class StreamingAsyncAction(val count: Int) : AsyncFlowAction {
        override suspend fun executeFlow(stateAccessor: StateAccessor): Flow<Action> = flow {
            // Emit a starting action
            emit(AsyncProcessing(this@StreamingAsyncAction))

            // Emit progress updates
            for (i in 1..count) {
                delay(50)
                emit(ProgressUpdateAction(i, count))
            }

            // Emit a result action
            emit(AsyncResultAction(this@StreamingAsyncAction, "Completed $count updates"))

            // Emit a completion action
            emit(AsyncComplete(this@StreamingAsyncAction))
        }
    }

    // Custom async action interface with specific types
    interface UserAsyncAction<T : Any> : AsyncAction<T> {
        // Custom action types with more specific information
        data class Starting(val userId: String, override val initiatedBy: Action) : AsyncInitiatedByAction
        data class Success<T : Any>(val userId: String, val data: T, override val initiatedBy: Action) : AsyncInitiatedByAction
        data class Failed(val userId: String, val error: Throwable, override val initiatedBy: Action) : AsyncInitiatedByAction
        data class Completed(val userId: String, override val initiatedBy: Action) : AsyncInitiatedByAction

        // Get the user ID associated with this action
        val userId: String

        // Override the create methods to return our custom action types
        override fun createProcessingAction(): Action = Starting(userId, this)
        override fun createResultAction(result: T): Action = Success(userId, result, this)
        override fun createErrorAction(error: Throwable): Action = Failed(userId, error, this)
        override fun createCompleteAction(): Action = Completed(userId, this)
    }

    // Implementation of the custom interface
    data class FetchUserProfile(override val userId: String) : UserAsyncAction<String> {
        override suspend fun getResult(stateAccessor: StateAccessor): Result<String> {
            delay(10)
            return Result.success("User profile for $userId")
        }
    }

    @Test
    fun `should support custom async action interfaces with specific types`() = runTest(timeout = 5.seconds) {
        val (store, dispatchedActions) = createTrackedStoreForTest(TestState()) {
            middleware {
                async()
            }

            reduceWith { state, action ->
                when (action) {
                    is UserAsyncAction.Starting -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("UserStarting:${action.userId}")
                        }
                        state.copy(actionsDispatched = newActions)
                    }
                    is UserAsyncAction.Success<*> -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("UserSuccess:${action.userId}:${action.data}")
                        }
                        val newProcessed = state.processed.toMutableList().apply {
                            add("${action.userId}:${action.data}")
                        }
                        state.copy(
                            actionsDispatched = newActions,
                            processed = newProcessed
                        )
                    }
                    is UserAsyncAction.Failed -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("UserFailed:${action.userId}:${action.error.message}")
                        }
                        state.copy(
                            error = action.error.message,
                            actionsDispatched = newActions
                        )
                    }
                    is UserAsyncAction.Completed -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("UserCompleted:${action.userId}")
                        }
                        state.copy(actionsDispatched = newActions)
                    }
                    else -> state
                }
            }
        }

        // Dispatch the custom async action
        val userId = "user-123"
        dispatchAndAdvance(store, FetchUserProfile(userId))

        // Wait for completion
        var completionFound = false
        while (!completionFound) {
            delay(10)
            completionFound = store.state.value.actionsDispatched.contains("UserCompleted:$userId")
        }

        advanceUntilIdle()
        val actions = dispatchedActions.toList()

        // Verify all expected actions were dispatched
        assertTrue(actions.any { it is UserAsyncAction.Starting && it.userId == userId },
            "UserAsyncAction.Starting should be dispatched")
        assertTrue(actions.any { it is UserAsyncAction.Success<*> && it.userId == userId },
            "UserAsyncAction.Success should be dispatched")
        assertTrue(actions.any { it is UserAsyncAction.Completed && it.userId == userId },
            "UserAsyncAction.Completed should be dispatched")

        // Verify state was updated correctly
        assertTrue(store.state.value.actionsDispatched.contains("UserStarting:$userId"),
            "UserStarting action should be tracked in state")
        assertTrue(store.state.value.actionsDispatched.contains("UserSuccess:$userId:User profile for $userId"),
            "UserSuccess action should be tracked in state")
        assertTrue(store.state.value.actionsDispatched.contains("UserCompleted:$userId"),
            "UserCompleted action should be tracked in state")
        assertTrue(store.state.value.processed.contains("$userId:User profile for $userId"),
            "User profile should be processed and stored in state")
    }

    // Custom action types for testing overridden create methods
    data class CustomProcessingAction(override val initiatedBy: Action, val metadata: String) : AsyncInitiatedByAction
    data class CustomResultAction<T>(override val initiatedBy: Action, val result: T, val metadata: String) : AsyncInitiatedByAction
    data class CustomErrorAction(override val initiatedBy: Action, val error: Throwable, val metadata: String) : AsyncInitiatedByAction
    data class CustomCompleteAction(override val initiatedBy: Action, val metadata: String) : AsyncInitiatedByAction

    // AsyncAction implementation with custom action creation methods
    data class CustomizedAsyncAction(val value: Int, val metadata: String) : AsyncAction<Int> {
        override suspend fun getResult(stateAccessor: StateAccessor): Result<Int> {
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

    @Test
    fun `should support overriding create methods within an action`() = runTest(timeout = 5.seconds) {
        val (store, dispatchedActions) = createTrackedStoreForTest(TestState()) {
            middleware {
                async()
            }

            reduceWith { state, action ->
                when (action) {
                    is CustomProcessingAction -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("CustomProcessing:${action.metadata}")
                        }
                        state.copy(actionsDispatched = newActions)
                    }
                    is CustomResultAction<*> -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("CustomResult:${action.metadata}:${action.result}")
                        }
                        val value = action.result
                        if (value is Int) {
                            state.copy(
                                counter = value,
                                actionsDispatched = newActions
                            )
                        } else {
                            state.copy(actionsDispatched = newActions)
                        }
                    }
                    is CustomErrorAction -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("CustomError:${action.metadata}:${action.error.message}")
                        }
                        state.copy(
                            error = action.error.message,
                            actionsDispatched = newActions
                        )
                    }
                    is CustomCompleteAction -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("CustomComplete:${action.metadata}")
                        }
                        state.copy(actionsDispatched = newActions)
                    }
                    else -> state
                }
            }
        }

        // Dispatch the customized async action
        val metadata = "test-metadata"
        dispatchAndAdvance(store, CustomizedAsyncAction(10, metadata))

        // Wait for completion
        var completionFound = false
        while (!completionFound) {
            delay(10)
            completionFound = store.state.value.actionsDispatched.contains("CustomComplete:$metadata")
        }

        advanceUntilIdle()
        val actions = dispatchedActions.toList()

        // Verify all expected actions were dispatched
        assertTrue(actions.any { it is CustomProcessingAction && it.metadata == metadata },
            "CustomProcessingAction should be dispatched with correct metadata")
        assertTrue(actions.any { it is CustomResultAction<*> && it.metadata == metadata },
            "CustomResultAction should be dispatched with correct metadata")
        assertTrue(actions.any { it is CustomCompleteAction && it.metadata == metadata },
            "CustomCompleteAction should be dispatched with correct metadata")

        // Verify state was updated correctly
        assertEquals(20, store.state.value.counter,
            "Counter should be updated with result value")
        assertTrue(store.state.value.actionsDispatched.contains("CustomProcessing:$metadata"),
            "CustomProcessing action should be tracked in state")
        assertTrue(store.state.value.actionsDispatched.contains("CustomResult:$metadata:20"),
            "CustomResult action should be tracked in state")
        assertTrue(store.state.value.actionsDispatched.contains("CustomComplete:$metadata"),
            "CustomComplete action should be tracked in state")
    }

    @Test
    fun `should handle custom async flow actions with progress updates`() = runTest(timeout = 5.seconds) {
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
                    is ProgressUpdateAction -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("Progress:${action.current}/${action.total}")
                        }
                        state.copy(
                            counter = action.current,
                            actionsDispatched = newActions
                        )
                    }
                    is AsyncResultAction<*> -> {
                        val newActions = state.actionsDispatched.toMutableList().apply {
                            add("AsyncResultAction:${action.result}")
                        }
                        state.copy(actionsDispatched = newActions)
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

        // Dispatch the streaming async action
        dispatchAndAdvance(store, StreamingAsyncAction(3))

        // Wait for all progress updates and completion
        var completeActionFound = false
        while (!completeActionFound) {
            delay(10)
            completeActionFound = store.state.value.actionsDispatched.contains("AsyncComplete:StreamingAsyncAction")
        }

        advanceUntilIdle()
        val actions = dispatchedActions.toList()

        // Verify all expected actions were dispatched
        assertTrue(actions.any { it is AsyncProcessing && it.initiatedBy is StreamingAsyncAction },
            "AsyncProcessing action should be dispatched")

        // Verify progress updates
        assertEquals(3, actions.count { it is ProgressUpdateAction },
            "Should have dispatched 3 progress update actions")

        // Verify result action
        assertTrue(actions.any { it is AsyncResultAction<*> && it.initiatedBy is StreamingAsyncAction },
            "AsyncResultAction should be dispatched")

        // Verify complete action
        assertTrue(actions.any { it is AsyncComplete && it.initiatedBy is StreamingAsyncAction },
            "AsyncComplete action should be dispatched")

        // Verify state was updated with progress
        assertEquals(3, store.state.value.counter,
            "Counter should be updated to final progress value")

        // Verify all actions were tracked in state
        assertTrue(store.state.value.actionsDispatched.contains("AsyncProcessing:StreamingAsyncAction"),
            "AsyncProcessing action should be tracked in state")
        assertTrue(store.state.value.actionsDispatched.contains("Progress:1/3"),
            "First progress update should be tracked in state")
        assertTrue(store.state.value.actionsDispatched.contains("Progress:2/3"),
            "Second progress update should be tracked in state")
        assertTrue(store.state.value.actionsDispatched.contains("Progress:3/3"),
            "Third progress update should be tracked in state")
        assertTrue(store.state.value.actionsDispatched.contains("AsyncResultAction:Completed 3 updates"),
            "AsyncResultAction should be tracked in state")
        assertTrue(store.state.value.actionsDispatched.contains("AsyncComplete:StreamingAsyncAction"),
            "AsyncComplete action should be tracked in state")
    }
}
