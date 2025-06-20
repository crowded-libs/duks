package duks

import duks.logging.Logger
import duks.logging.debug
import duks.logging.error

/**
 * Represents a middleware function in the Duks state management system.
 *
 * Middleware intercepts actions before they reach the reducer, allowing for side effects,
 * async operations, logging, error handling, and other cross-cutting concerns.
 *
 * @param TState The type of state model used in the store
 * @property store The store instance that dispatched the action
 * @property next The function to call the next middleware in the chain
 * @property action The current action being processed
 * @return The action to be passed down the middleware chain
 */
typealias Middleware<TState> = suspend (store: KStore<TState>, next: suspend (Action) -> Action, action: Action) -> Action

/**
 * Interface for middleware that needs to be notified of store lifecycle events.
 *
 * Middleware can implement this interface to receive callbacks when the store
 * is created, allowing for initialization or setup operations that require
 * access to the store instance.
 *
 * @param TState The type of state model used in the store
 */
interface StoreLifecycleAware<TState : StateModel> {
    /**
     * Called when the store has been created and is ready for use.
     *
     * This method is invoked after the store is fully constructed but before
     * any actions are dispatched, allowing middleware to perform initialization
     * that requires access to the store instance.
     *
     * @param store The newly created store instance
     */
    suspend fun onStoreCreated(store: KStore<TState>)
    
    /**
     * Called when the store is being destroyed.
     * 
     * This method allows middleware to clean up resources, cancel background
     * jobs, and perform any necessary cleanup operations.
     * 
     * Default implementation does nothing.
     */
    suspend fun onStoreDestroyed() {}
    
    /**
     * Called when storage restoration begins.
     * 
     * This method is invoked when persistence middleware starts loading
     * stored state from storage. Middleware can use this to defer their
     * initialization or adjust their behavior during restoration.
     * 
     * Default implementation does nothing.
     */
    suspend fun onStorageRestorationStarted() {}
    
    /**
     * Called when storage restoration completes.
     * 
     * This method is invoked after persistence middleware finishes attempting
     * to restore state from storage. The restored parameter indicates whether
     * state was actually restored or if the store is using its initial state.
     * 
     * @param restored true if state was successfully restored from storage,
     *                 false if no stored state was found or restoration failed
     * 
     * Default implementation does nothing.
     */
    suspend fun onStorageRestorationCompleted(restored: Boolean) {}
}

/**
 * Action dispatched after a batch of async actions have been processed.
 *
 * This action is emitted by the asyncMiddleware after all the actions from an
 * AsyncAction's execution flow have been dispatched and processed.
 *
 * @property actions The list of actions that were dispatched during async processing
 */
data class AsyncActionsDispatched(val actions: List<Action>) : Action

/**
 * Creates middleware that handles asynchronous actions.
 *
 * This middleware intercepts AsyncAction instances and:
 * 1. Executes their async operation
 * 2. Collects resulting actions from the operation's flow
 * 3. Dispatches each action through the middleware chain and directly to the store
 * 4. Emits an AsyncActionsDispatched action when complete
 *
 * @param TState The type of state model used in the store
 * @return A middleware function that processes async actions
 */
fun <TState:StateModel> asyncMiddleware(): Middleware<TState> = { store, next, action ->
    when (action) {
        is AsyncFlowAction -> {
            val flow = action.executeFlow(store.stateAccessor)
            val dispatchedActions = mutableListOf<Action>()

            next(action)
            flow.collect { actionFromFlow ->
                dispatchedActions.add(actionFromFlow)
                store.dispatch(actionFromFlow)
            }
            AsyncActionsDispatched(dispatchedActions)
        }
        else -> {
            next(action)
        }
    }
}

/**
 * Creates middleware that logs actions as they flow through the middleware chain.
 *
 * This middleware logs each action before and after it has been processed by
 * subsequent middleware and the reducer, allowing for easy debugging of action
 * flow and state changes.
 *
 * @param logger The logger to use for logging (defaults to Logger.default())
 * @param TState The type of state model used in the store
 * @return A middleware function that logs actions
 */
fun <TState:StateModel> loggerMiddleware(logger: Logger = Logger.default()): Middleware<TState> = { store, next, action ->
    logger.debug(action) { "Action: {action}" }
    next(action)
    logger.debug(action) { "After Action: {action}" }
    action
}

/**
 * Creates middleware that catches exceptions in the middleware chain.
 *
 * This middleware wraps the next middleware in a try-catch block, preventing
 * exceptions from crashing the application and logging them instead.
 *
 * @param logger The logger to use for logging errors (defaults to Logger.default())
 * @param TState The type of state model used in the store
 * @return A middleware function that handles exceptions
 */
fun <TState:StateModel> exceptionMiddleware(logger: Logger = Logger.default()): Middleware<TState> = { store, next, action ->
    try {
        next(action)
    } catch (e: Exception) {
        logger.error(e, action) { "Exception processing action: {action}" }
    }
    action
}

/**
 * Interface for caching action results to avoid redundant processing.
 *
 * Implementations of this interface provide storage and retrieval mechanisms
 * for caching the results of CacheableAction processing, enabling performance
 * optimization by avoiding repeated execution of expensive operations.
 */
interface ActionCache {
    /**
     * Checks if the cache contains an entry for the given action.
     *
     * @param action The cacheable action to check
     * @return true if the action is cached and not expired, false otherwise
     */
    fun has(action: CacheableAction): Boolean
    
    /**
     * Stores an action result in the cache.
     *
     * @param action The cacheable action to use as a key
     * @param cached The cached action result with expiration time
     */
    fun put(action: CacheableAction, cached: CachedActions)
    
    /**
     * Retrieves a cached action result.
     *
     * @param action The cacheable action to look up
     * @return The cached action result or null if not found or expired
     */
    fun get(action: CacheableAction): CachedActions?
}

/**
 * Represents a cached action result with its expiration time.
 *
 * @property expiresAfter The time at which this cached result should be considered expired
 * @property action The action that resulted from processing the original cacheable action
 */
data class CachedActions(val expiresAfter: kotlinx.datetime.Instant,
                         val action: Action)

/**
 * Creates middleware that caches the results of cacheable actions.
 *
 * This middleware intercepts CacheableAction instances and:
 * 1. Checks if the action is already in the cache
 * 2. If cached and not expired, returns the cached result
 * 3. If not cached or expired, processes the action and caches the result
 *
 * This can significantly improve performance for expensive operations that
 * are called repeatedly with the same parameters.
 *
 * @param cache The ActionCache implementation to use for storing results
 * @param TState The type of state model used in the store
 * @return A middleware function that implements caching behavior
 */
fun <TState:StateModel> cachingMiddleware(cache: ActionCache): Middleware<TState> = { store, next, action ->
    suspend fun nextWithCache(action: CacheableAction) : Action {
        val newAction = next(action)

        if (newAction is CacheableAction) {
            throw IllegalStateException("Caching middleware should not be used to cache cacheable actions otherwise resulting in a recursive loop")
        }
        cache.put(action, CachedActions(action.expiresAfter, newAction))
        return newAction
    }
    when (action) {
        is CacheableAction -> {
            if (cache.has(action)) {
                val cachedActions = cache.get(action)
                if (cachedActions != null) {
                    next(cachedActions.action)
                    cachedActions.action
                }
                else {
                    nextWithCache(action)
                }
            }
            else {
                nextWithCache(action)
            }
        }
        else -> {
            next(action)
        }
    }
}
