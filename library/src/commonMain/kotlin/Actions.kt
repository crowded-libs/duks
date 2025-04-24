package duks

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock.System.now
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/**
 * Base interface for all actions in the Duks state management system.
 * 
 * Actions represent events or commands that can trigger state changes in the application.
 * They are dispatched to the store and processed by reducers and middleware to update state.
 * Implementing classes should typically be immutable data classes that contain any data
 * needed for state updates.
 */
interface Action

/**
 * Interface for actions that are triggered as part of an asynchronous operation.
 * 
 * These actions are associated with an original initiating action that started
 * the asynchronous operation, providing traceability for async workflows.
 */
interface AsyncInitiatedByAction : Action {
    /**
     * The original action that initiated the asynchronous operation.
     */
    val initiatedBy: Action
}

/**
 * Represents the result of an asynchronous operation.
 * 
 * @param initiatedBy The original action that initiated the async operation
 * @param result The result of the asynchronous operation, wrapped in a Result class
 * @param TResult The type of the result value
 */
data class AsyncResultAction<TResult>(override val initiatedBy: Action, val result: TResult) : AsyncInitiatedByAction

/**
 * Indicates that an asynchronous operation has started processing.
 * 
 * This action is dispatched at the beginning of an async operation to allow the UI
 * to show loading indicators or prepare for upcoming state changes.
 * 
 * @param initiatedBy The original action that initiated the async operation
 */
data class AsyncProcessing(override val initiatedBy: Action) : AsyncInitiatedByAction

/**
 * Indicates that an asynchronous operation has completed, regardless of success or failure.
 * 
 * This action is typically dispatched after an AsyncResultAction to signal that any loading
 * indicators can be dismissed and cleanup operations can be performed.
 * 
 * @param initiatedBy The original action that initiated the async operation
 */
data class AsyncComplete(override val initiatedBy: Action) : AsyncInitiatedByAction

data class AsyncError(override val initiatedBy: Action, val error: Throwable) : AsyncInitiatedByAction

interface AsyncFlowAction : Action {
    suspend fun executeFlow(stateAccessor: StateAccessor) : Flow<Action>
}

/**
 * Interface for actions that perform asynchronous operations.
 * 
 * Async actions encapsulate asynchronous logic and provide a standardized way to
 * handle the async operation lifecycle including start, result, and completion phases.
 * 
 * @param TResponse The type of result that the async operation will produce
 */
interface AsyncAction<TResponse:Any> : AsyncFlowAction {
    /**
     * Executes the asynchronous operation.
     * 
     * Implementations should perform the actual async work and return a Result object
     * that contains either a successful value or a failure exception.
     * 
     * @return A Result object containing either the successful response or a failure exception
     */
    suspend fun getResult(stateAccessor: StateAccessor) : Result<TResponse>
    
    /**
     * Executes the asynchronous operation and emits lifecycle actions for the async middleware.
     * 
     * This function automatically handles the emission of AsyncProcessing, AsyncResultAction,
     * and AsyncComplete actions to provide a consistent lifecycle for async operations.
     * 
     * @param getState A function that provides access to the current state
     * @return A Flow of actions representing the async operation lifecycle
     */
    override suspend fun executeFlow(stateAccessor: StateAccessor) : Flow<Action> = flow {
        emit(createProcessingAction())
        val result = getResult(stateAccessor)
        if (result.isSuccess) {
            emit(createResultAction(result.getOrThrow()))
        }
        else {
            emit(createErrorAction(result.exceptionOrNull()!!))
        }
        emit(createCompleteAction())
    }

    fun createProcessingAction() : Action = AsyncProcessing(this@AsyncAction)
    fun createResultAction(result: TResponse) : Action = AsyncResultAction(this@AsyncAction, result)
    fun createErrorAction(error: Throwable) : Action = AsyncError(this@AsyncAction, error)
    fun createCompleteAction() : Action = AsyncComplete(this@AsyncAction)
}

/**
 * Interface for actions that can be cached.
 * 
 * Cacheable actions are stored in an action cache to avoid redundant processing
 * of identical actions, improving performance for frequently dispatched actions.
 */
interface CacheableAction : Action {
    /**
     * The timestamp when this cached action should expire.
     * 
     * By default, cacheable actions expire after one day, but implementations
     * can override this property to provide custom expiration times.
     */
    val expiresAfter: kotlinx.datetime.Instant
        get() = now().plus(1, DateTimeUnit.DAY, TimeZone.currentSystemDefault())
}
