package duks

import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
            yield() // cooperative async boundary
            return Result.success(value * 2)
        }
    }

    data class FailingAsyncAction(val error: String) : AsyncAction<Int> {
        override suspend fun getResult(stateAccessor: StateAccessor): Result<Int> {
            yield() // cooperative async boundary
            return Result.failure(RuntimeException(error))
        }
    }

    private fun trackLifecycle(
        state: TestState,
        label: String
    ): TestState {
        val newActions = state.actionsDispatched.toMutableList().apply { add(label) }
        return state.copy(actionsDispatched = newActions)
    }

    private fun defaultAsyncReducer(): Reducer<TestState> = { state, action ->
        when (action) {
            is IncrementAction -> state.copy(counter = state.counter + action.value)
            is AsyncProcessing -> trackLifecycle(
                state,
                "AsyncProcessing:${action.initiatedBy::class.simpleName}"
            )
            is AsyncResultAction<*> -> {
                val labeled = trackLifecycle(
                    state,
                    "AsyncResultAction:${action.initiatedBy::class.simpleName}"
                )
                val value = action.result
                if (value is Int) {
                    labeled.copy(counter = labeled.counter + value)
                } else {
                    labeled
                }
            }
            is AsyncError -> trackLifecycle(
                state,
                "AsyncError:${action.initiatedBy::class.simpleName}"
            ).copy(error = action.error.message ?: "Unknown error")
            is AsyncComplete -> trackLifecycle(
                state,
                "AsyncComplete:${action.initiatedBy::class.simpleName}"
            )
            else -> state
        }
    }

    @Test
    fun `should handle failing async actions correctly using asyncMiddleware`() = runTest(timeout = 5.seconds) {
        val (store, dispatchedActions) = createTrackedStoreForTest(TestState()) {
            middleware { async() }
            reduceWith(defaultAsyncReducer())
        }

        val errorMessage = "Test error message"
        dispatchAndAdvance(store, FailingAsyncAction(errorMessage))

        awaitState(store.state) {
            it.actionsDispatched.contains("AsyncComplete:FailingAsyncAction")
        }

        val actions = dispatchedActions.toList()
        assertEquals(errorMessage, store.state.value.error)

        assertTrue(actions.any { it is AsyncProcessing && it.initiatedBy is FailingAsyncAction })
        assertTrue(actions.any { it is AsyncError && it.initiatedBy is FailingAsyncAction })
        assertTrue(actions.any { it is AsyncComplete && it.initiatedBy is FailingAsyncAction })

        assertTrue(store.state.value.actionsDispatched.contains("AsyncProcessing:FailingAsyncAction"))
        assertTrue(store.state.value.actionsDispatched.contains("AsyncError:FailingAsyncAction"))
        assertTrue(store.state.value.actionsDispatched.contains("AsyncComplete:FailingAsyncAction"))
    }

    @Test
    fun `should process successful async actions using asyncMiddleware`() = runTest(timeout = 5.seconds) {
        val (store, dispatchedActions) = createTrackedStoreForTest(TestState()) {
            middleware { async() }
            reduceWith(defaultAsyncReducer())
        }

        dispatchAndAdvance(store, IncrementAction(5))
        awaitState(store.state) { it.counter == 5 }
        assertEquals(5, store.state.value.counter)

        dispatchAndAdvance(store, SimpleAsyncAction(10))

        awaitState(store.state) { it.counter == 25 }
        awaitUntil {
            dispatchedActions.any { it is AsyncComplete && it.initiatedBy is SimpleAsyncAction }
        }

        val actions = dispatchedActions.toList()
        assertEquals(25, store.state.value.counter)

        assertTrue(actions.any { it is AsyncProcessing && it.initiatedBy is SimpleAsyncAction })
        assertTrue(actions.any { it is AsyncResultAction<*> && it.initiatedBy is SimpleAsyncAction })
        assertTrue(actions.any { it is AsyncComplete && it.initiatedBy is SimpleAsyncAction })

        assertTrue(store.state.value.actionsDispatched.contains("AsyncProcessing:SimpleAsyncAction"))
        assertTrue(store.state.value.actionsDispatched.contains("AsyncResultAction:SimpleAsyncAction"))
        assertTrue(store.state.value.actionsDispatched.contains("AsyncComplete:SimpleAsyncAction"))
    }

    @Test
    fun `should handle multiple async actions sequentially using asyncMiddleware`() = runTest(timeout = 5.seconds) {
        val (store, dispatchedActions) = createTrackedStoreForTest(TestState()) {
            middleware { async() }
            reduceWith { state, action ->
                when (action) {
                    is AsyncProcessing -> trackLifecycle(
                        state,
                        "AsyncProcessing:${action.initiatedBy::class.simpleName}"
                    )
                    is AsyncResultAction<*> -> {
                        val labeled = trackLifecycle(
                            state,
                            "AsyncResultAction:${action.initiatedBy::class.simpleName}"
                        )
                        val value = action.result
                        if (value is Int) {
                            val newProcessed = labeled.processed.toMutableList().apply {
                                add("Processed:$value")
                            }
                            labeled.copy(counter = labeled.counter + value, processed = newProcessed)
                        } else {
                            labeled
                        }
                    }
                    is AsyncError -> trackLifecycle(
                        state,
                        "AsyncError:${action.initiatedBy::class.simpleName}"
                    ).copy(error = action.error.message ?: "Unknown error")
                    is AsyncComplete -> trackLifecycle(
                        state,
                        "AsyncComplete:${action.initiatedBy::class.simpleName}"
                    )
                    else -> state
                }
            }
        }

        dispatchAndAdvance(store, SimpleAsyncAction(10))
        dispatchAndAdvance(store, SimpleAsyncAction(20))
        dispatchAndAdvance(store, SimpleAsyncAction(30))

        // 10*2 + 20*2 + 30*2 = 120
        awaitState(store.state) { it.counter == 120 }
        awaitUntil { dispatchedActions.count { it is AsyncComplete } >= 3 }

        val actions = dispatchedActions.toList()
        assertEquals(120, store.state.value.counter)

        assertTrue(store.state.value.processed.contains("Processed:20"))
        assertTrue(store.state.value.processed.contains("Processed:40"))
        assertTrue(store.state.value.processed.contains("Processed:60"))

        assertEquals(3, actions.count { it is AsyncProcessing })
        assertEquals(3, actions.count { it is AsyncResultAction<*> })
        assertEquals(3, actions.count { it is AsyncComplete })
    }

    data class ProgressUpdateAction(val current: Int, val total: Int) : Action

    data class StreamingAsyncAction(val count: Int) : AsyncFlowAction {
        override suspend fun executeFlow(stateAccessor: StateAccessor): Flow<Action> = flow {
            emit(AsyncProcessing(this@StreamingAsyncAction))
            for (i in 1..count) {
                delay(50) // virtual-time progress spacing
                emit(ProgressUpdateAction(i, count))
            }
            emit(AsyncResultAction(this@StreamingAsyncAction, "Completed $count updates"))
            emit(AsyncComplete(this@StreamingAsyncAction))
        }
    }

    interface UserAsyncAction<T : Any> : AsyncAction<T> {
        data class Starting(val userId: String, override val initiatedBy: Action) : AsyncInitiatedByAction
        data class Success<T : Any>(val userId: String, val data: T, override val initiatedBy: Action) : AsyncInitiatedByAction
        data class Failed(val userId: String, val error: Throwable, override val initiatedBy: Action) : AsyncInitiatedByAction
        data class Completed(val userId: String, override val initiatedBy: Action) : AsyncInitiatedByAction

        val userId: String

        override fun createProcessingAction(): Action = Starting(userId, this)
        override fun createResultAction(result: T): Action = Success(userId, result, this)
        override fun createErrorAction(error: Throwable): Action = Failed(userId, error, this)
        override fun createCompleteAction(): Action = Completed(userId, this)
    }

    data class FetchUserProfile(override val userId: String) : UserAsyncAction<String> {
        override suspend fun getResult(stateAccessor: StateAccessor): Result<String> {
            yield() // cooperative async boundary
            return Result.success("User profile for $userId")
        }
    }

    @Test
    fun `should support custom async action interfaces with specific types`() = runTest(timeout = 5.seconds) {
        val (store, dispatchedActions) = createTrackedStoreForTest(TestState()) {
            middleware { async() }
            reduceWith { state, action ->
                when (action) {
                    is UserAsyncAction.Starting -> trackLifecycle(state, "UserStarting:${action.userId}")
                    is UserAsyncAction.Success<*> -> {
                        val labeled = trackLifecycle(state, "UserSuccess:${action.userId}:${action.data}")
                        val newProcessed = labeled.processed.toMutableList().apply {
                            add("${action.userId}:${action.data}")
                        }
                        labeled.copy(processed = newProcessed)
                    }
                    is UserAsyncAction.Failed -> trackLifecycle(
                        state,
                        "UserFailed:${action.userId}:${action.error.message}"
                    ).copy(error = action.error.message)
                    is UserAsyncAction.Completed -> trackLifecycle(state, "UserCompleted:${action.userId}")
                    else -> state
                }
            }
        }

        val userId = "user-123"
        dispatchAndAdvance(store, FetchUserProfile(userId))

        awaitState(store.state) {
            it.actionsDispatched.contains("UserCompleted:$userId")
        }

        val actions = dispatchedActions.toList()

        assertTrue(actions.any { it is UserAsyncAction.Starting && it.userId == userId })
        assertTrue(actions.any { it is UserAsyncAction.Success<*> && it.userId == userId })
        assertTrue(actions.any { it is UserAsyncAction.Completed && it.userId == userId })

        assertTrue(store.state.value.actionsDispatched.contains("UserStarting:$userId"))
        assertTrue(store.state.value.actionsDispatched.contains("UserSuccess:$userId:User profile for $userId"))
        assertTrue(store.state.value.actionsDispatched.contains("UserCompleted:$userId"))
        assertTrue(store.state.value.processed.contains("$userId:User profile for $userId"))
    }

    data class CustomProcessingAction(override val initiatedBy: Action, val metadata: String) : AsyncInitiatedByAction
    data class CustomResultAction<T>(override val initiatedBy: Action, val result: T, val metadata: String) : AsyncInitiatedByAction
    data class CustomErrorAction(override val initiatedBy: Action, val error: Throwable, val metadata: String) : AsyncInitiatedByAction
    data class CustomCompleteAction(override val initiatedBy: Action, val metadata: String) : AsyncInitiatedByAction

    data class CustomizedAsyncAction(val value: Int, val metadata: String) : AsyncAction<Int> {
        override suspend fun getResult(stateAccessor: StateAccessor): Result<Int> {
            yield() // cooperative async boundary
            return Result.success(value * 2)
        }

        override fun createProcessingAction(): Action = CustomProcessingAction(this, metadata)
        override fun createResultAction(result: Int): Action = CustomResultAction(this, result, metadata)
        override fun createErrorAction(error: Throwable): Action = CustomErrorAction(this, error, metadata)
        override fun createCompleteAction(): Action = CustomCompleteAction(this, metadata)
    }

    @Test
    fun `should support overriding create methods within an action`() = runTest(timeout = 5.seconds) {
        val (store, dispatchedActions) = createTrackedStoreForTest(TestState()) {
            middleware { async() }
            reduceWith { state, action ->
                when (action) {
                    is CustomProcessingAction -> trackLifecycle(state, "CustomProcessing:${action.metadata}")
                    is CustomResultAction<*> -> {
                        val labeled = trackLifecycle(state, "CustomResult:${action.metadata}:${action.result}")
                        val value = action.result
                        if (value is Int) labeled.copy(counter = value) else labeled
                    }
                    is CustomErrorAction -> trackLifecycle(
                        state,
                        "CustomError:${action.metadata}:${action.error.message}"
                    ).copy(error = action.error.message)
                    is CustomCompleteAction -> trackLifecycle(state, "CustomComplete:${action.metadata}")
                    else -> state
                }
            }
        }

        val metadata = "test-metadata"
        dispatchAndAdvance(store, CustomizedAsyncAction(10, metadata))

        awaitState(store.state) {
            it.actionsDispatched.contains("CustomComplete:$metadata")
        }

        val actions = dispatchedActions.toList()

        assertTrue(actions.any { it is CustomProcessingAction && it.metadata == metadata })
        assertTrue(actions.any { it is CustomResultAction<*> && it.metadata == metadata })
        assertTrue(actions.any { it is CustomCompleteAction && it.metadata == metadata })

        assertEquals(20, store.state.value.counter)
        assertTrue(store.state.value.actionsDispatched.contains("CustomProcessing:$metadata"))
        assertTrue(store.state.value.actionsDispatched.contains("CustomResult:$metadata:20"))
        assertTrue(store.state.value.actionsDispatched.contains("CustomComplete:$metadata"))
    }

    @Test
    fun `should handle custom async flow actions with progress updates`() = runTest(timeout = 5.seconds) {
        val (store, dispatchedActions) = createTrackedStoreForTest(TestState()) {
            middleware { async() }
            reduceWith { state, action ->
                when (action) {
                    is AsyncProcessing -> trackLifecycle(
                        state,
                        "AsyncProcessing:${action.initiatedBy::class.simpleName}"
                    )
                    is ProgressUpdateAction -> {
                        val labeled = trackLifecycle(state, "Progress:${action.current}/${action.total}")
                        labeled.copy(counter = action.current)
                    }
                    is AsyncResultAction<*> -> trackLifecycle(state, "AsyncResultAction:${action.result}")
                    is AsyncComplete -> trackLifecycle(
                        state,
                        "AsyncComplete:${action.initiatedBy::class.simpleName}"
                    )
                    else -> state
                }
            }
        }

        dispatchAndAdvance(store, StreamingAsyncAction(3))

        awaitState(store.state) {
            it.actionsDispatched.contains("AsyncComplete:StreamingAsyncAction")
        }

        val actions = dispatchedActions.toList()

        assertTrue(actions.any { it is AsyncProcessing && it.initiatedBy is StreamingAsyncAction })
        assertEquals(3, actions.count { it is ProgressUpdateAction })
        assertTrue(actions.any { it is AsyncResultAction<*> && it.initiatedBy is StreamingAsyncAction })
        assertTrue(actions.any { it is AsyncComplete && it.initiatedBy is StreamingAsyncAction })

        assertEquals(3, store.state.value.counter)
        assertTrue(store.state.value.actionsDispatched.contains("AsyncProcessing:StreamingAsyncAction"))
        assertTrue(store.state.value.actionsDispatched.contains("Progress:1/3"))
        assertTrue(store.state.value.actionsDispatched.contains("Progress:2/3"))
        assertTrue(store.state.value.actionsDispatched.contains("Progress:3/3"))
        assertTrue(store.state.value.actionsDispatched.contains("AsyncResultAction:Completed 3 updates"))
        assertTrue(store.state.value.actionsDispatched.contains("AsyncComplete:StreamingAsyncAction"))
    }
}
