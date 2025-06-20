package duks

import duks.storage.PersistenceMiddleware
import duks.storage.PersistenceStrategy
import duks.storage.StateStorage
import duks.storage.SagaStorage
import duks.storage.SagaPersistenceStrategy
import duks.logging.Logger
import duks.logging.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Represents a reducer function in the Duks state management system.
 *
 * A reducer is a pure function that takes the current state and an action,
 * and returns a new state object. Reducers should not cause side effects or
 * modify the current state - they should create a new state object if changes
 * are needed.
 *
 * @param TState The type of state model used in the store
 * @param state The current state object
 * @param action The action to process
 * @return A new state object (or the same object if no changes are needed)
 */
typealias Reducer<TState> = (TState, Action) -> TState

/**
 * The central store for state management in Duks.
 *
 * KStore is responsible for holding the application state, dispatching actions
 * through the middleware chain, and applying reducers to produce new state.
 * It integrates with Compose by exposing state as a Compose State object.
 *
 * @param TState The type of state model used in the store
 * @property initialState The initial state of the store
 * @property reducer The reducer function that processes actions and updates state
 * @property middleware The composed middleware chain to process actions
 * @property ioScope The coroutine scope used for IO operations and middleware processing
 */
class KStore<TState:StateModel> internal constructor(initialState: TState,
                                                     private val reducer: Reducer<TState>,
                                                     private val middleware: Middleware<TState>,
                                                     internal val ioScope: CoroutineScope,
                                                     private val lifecycleAwareMiddleware: List<StoreLifecycleAware<TState>> = emptyList(),
                                                     private val logger: Logger = Logger.default()) {
    
    private val _state = MutableStateFlow(initialState)
    private val _stateMutex = Mutex()

    init {
        logger.info(initialState::class.simpleName) { "Creating KStore with initial state type: {stateType}" }
        // Notify lifecycle-aware middleware that the store has been created
        ioScope.launch {
            lifecycleAwareMiddleware.forEach { middleware ->
                middleware.onStoreCreated(this@KStore)
            }
        }
    }
    
    /**
     * Notifies all lifecycle-aware middleware that storage restoration has started.
     * This method should only be called by persistence middleware.
     */
    internal suspend fun notifyStorageRestorationStarted() {
        lifecycleAwareMiddleware.forEach { middleware ->
            try {
                middleware.onStorageRestorationStarted()
            } catch (e: Exception) {
                logger.error(e) { "Error notifying middleware of storage restoration start" }
            }
        }
    }
    
    /**
     * Notifies all lifecycle-aware middleware that storage restoration has completed.
     * This method should only be called by persistence middleware.
     * 
     * @param restored true if state was successfully restored from storage
     */
    internal suspend fun notifyStorageRestorationCompleted(restored: Boolean) {
        lifecycleAwareMiddleware.forEach { middleware ->
            try {
                middleware.onStorageRestorationCompleted(restored)
            } catch (e: Exception) {
                logger.error(e) { "Error notifying middleware of storage restoration completion" }
            }
        }
    }

    /**
     * The current state of the store, exposed as a StateFlow.
     *
     * This property allows the store's state to be observed by UI components,
     * which will automatically update when the state changes. In UI frameworks
     * like Compose, this can be collected to trigger recomposition.
     */
    val state: StateFlow<TState> = _state.asStateFlow()

    /**
     * Dispatches an action to the store.
     *
     * The action is processed asynchronously through the middleware chain
     * and then delivered to the reducer to produce a new state if needed.
     *
     * @param action The action to dispatch
     */
    fun dispatch(action: Action) {
        logger.trace(action::class.simpleName) { "Dispatching action: {actionType}" }
        ioScope.launch {
            middleware(this@KStore, { a ->
                _stateMutex.withLock {
                    applyReducer(a)
                }
            }, action)
        }
    }

    internal val stateAccessor: StateAccessor = object : StateAccessor {
        override fun <TState : StateModel> getState(): TState {
            @Suppress("UNCHECKED_CAST")
            return _state.value as TState
        }

    }

    /**
     * Internally handles calling the reducer after middleware processing.
     *
     * This method applies the reducer to the current state and the action,
     * and updates the state if a new value is produced.
     *
     * @param action The action to dispatch
     * @return The original action (for middleware chaining)
     */
    private fun applyReducer(action: Action) : Action {
        val oldState = _state.value
        
        // Handle RestoreStateAction directly in the store to ensure state restoration
        // works even if the app's reducer doesn't handle it
        _state.value = when (action) {
            is RestoreStateAction<*> -> {
                @Suppress("UNCHECKED_CAST")
                action.state as TState
            }
            else -> reducer(oldState, action)
        }
        
        if (oldState !== _state.value) {
            logger.trace(oldState::class.simpleName, _state.value::class.simpleName) { "State updated from {oldType} to {newType}" }
        }
        return action
    }
}

/**
 * Creates a new store with the specified initial state and configuration.
 *
 * This function provides a convenient builder-pattern API for configuring and
 * creating a KStore instance with reducers and middleware.
 *
 * @param initialState The initial state for the store
 * @param block A configuration block using the StoreBuilder DSL
 * @return A configured KStore instance
 */
fun <TState:StateModel> createStore(initialState: TState, block: StoreBuilder<TState>.() -> Unit) : KStore<TState> {
    val storeBuilder = StoreBuilder<TState>()
    storeBuilder.initialState(initialState)
    block(storeBuilder)
    return storeBuilder.build()
}

/**
 * Builder for configuring and creating a KStore instance.
 *
 * This class provides a DSL for specifying the reducer, middleware, and other
 * configuration options for a KStore.
 *
 * @param TState The type of state model used in the store
 */
class StoreBuilder<TState:StateModel> {
    private var initialState: TState? = null
    private val middlewareBuilder = MiddlewareBuilder<TState>()
    private val reducers = mutableListOf<Reducer<TState>>()
    private var ioScope: CoroutineScope = CoroutineScope(backgroundDispatcher() + SupervisorJob())

    val scope: CoroutineScope get() = ioScope

    /**
     * Sets the initial state for the store.
     *
     * @param state The initial state
     */
    internal fun initialState(state: TState) {
        initialState = state
    }

    /**
     * Configures middleware for the store using the MiddlewareBuilder DSL.
     *
     * @param block A configuration block for middleware
     */
    fun middleware(block: MiddlewareBuilder<TState>.() -> Unit) {
        block(middlewareBuilder)
    }

    /**
     * Adds a reducer function to the store.
     * Multiple reducers can be added and will be composed into a single reducer.
     * Reducers are executed in the order they were added.
     *
     * @param reducer The reducer function that will process actions and update state
     */
    fun reduceWith(reducer: Reducer<TState>) {
        reducers.add(reducer)
    }

    /**
     * Sets custom coroutine scopes for UI and IO operations.
     *
     * @param ioScope The coroutine scope for IO operations and middleware
     */
    fun scope(ioScope: CoroutineScope) {
        this.ioScope = ioScope
    }

    /**
     * Composes multiple reducers into a single reducer function.
     * The reducers are applied in sequence, with each reducer receiving
     * the state produced by the previous reducer.
     *
     * @return A single composed reducer function
     */
    private fun composeReducers(): Reducer<TState> {
        return when (reducers.size) {
            0 -> { state, _ -> state } // Identity reducer if no reducers added
            1 -> reducers[0] // Return single reducer directly for efficiency
            else -> { state, action ->
                // Compose reducers without iteration using fold
                reducers.fold(state) { currentState, reducer ->
                    reducer(currentState, action)
                }
            }
        }
    }

    /**
     * Builds and returns a KStore instance with the configured options.
     *
     * @return A configured KStore instance
     * @throws IllegalStateException if the initial state has not been set
     */
    internal fun build() : KStore<TState> {
        if (initialState == null) {
            throw IllegalStateException("Initial state must be set")
        }
        val logger = Logger.default()
        logger.debug(middlewareBuilder.middleware.size) { "Composing {count} middleware functions" }
        logger.debug(reducers.size) { "Composing {count} reducer functions" }
        logger.debug(middlewareBuilder.lifecycleAwareMiddleware.size) { "Registered {count} lifecycle-aware middleware" }
        return KStore(initialState!!, composeReducers(), middlewareBuilder.compose(), ioScope, middlewareBuilder.lifecycleAwareMiddleware, logger)
    }
}

/**
 * Builder for configuring middleware in a KStore instance.
 *
 * This class provides a DSL for adding and composing middleware functions
 * to process actions before they reach the reducer.
 *
 * @param TState The type of state model used in the store
 */
class MiddlewareBuilder<TState:StateModel> {
    internal val middleware = mutableListOf<Middleware<TState>>()
    internal val lifecycleAwareMiddleware = mutableListOf<StoreLifecycleAware<TState>>()

    /**
     * Adds logging middleware to the chain.
     *
     * @param logger The logger to use for logging (defaults to Logger.default())
     */
    fun logging(logger: Logger = Logger.default()) {
        middleware.add(loggerMiddleware(logger))
    }

    /**
     * Adds exception handling middleware to the chain.
     * 
     * @param logger The logger to use for logging errors (defaults to Logger.default())
     */
    fun exceptionHandling(logger: Logger = Logger.default()) {
        middleware.add(exceptionMiddleware(logger))
    }

    /**
     * Adds caching middleware to the chain.
     *
     * @param cache The cache implementation to use (defaults to MapActionCache)
     */
    fun caching(cache: ActionCache = MapActionCache()) {
        middleware.add(cachingMiddleware(cache))
    }

    /**
     * Adds async handling middleware to the chain.
     */
    fun async() {
        middleware.add(asyncMiddleware())
    }
    
    /**
     * Adds saga middleware to the chain.
     *
     * @param storage Optional storage for saga persistence. If provided, sagas will be persisted and restored automatically.
     * @param persistenceStrategy The strategy for when to persist saga state (defaults to OnEveryChange)
     * @param logger Logger for error logging (defaults to Logger.default())
     * @param block A configuration block for defining sagas
     */
    fun sagas(
        storage: SagaStorage? = null,
        persistenceStrategy: SagaPersistenceStrategy = SagaPersistenceStrategy.OnEveryChange,
        logger: Logger = Logger.default(),
        block: SagaRegistry<TState>.() -> Unit
    ) {
        val registry = SagaRegistry<TState>()
        block(registry)
        val sagaMiddleware = sagaMiddleware(registry, storage, persistenceStrategy, logger)
        middleware.add(sagaMiddleware)
        
        // If the middleware is lifecycle-aware, register it
        if (sagaMiddleware is StoreLifecycleAware<*>) {
            @Suppress("UNCHECKED_CAST")
            lifecycleAware(sagaMiddleware as StoreLifecycleAware<TState>)
        }
    }
    
    /**
     * Adds persistence middleware to the chain.
     * 
     * The persistence middleware should be added after exception handling but before async middleware to ensure:
     * 1. Exceptions during persistence are properly handled
     * 2. State restoration happens before any async operations
     * 3. The middleware chain maintains proper execution order
     * 
     * @param storage The storage implementation to use for persistence
     * @param strategy The persistence strategy determining when to persist
     * @param errorHandler Error handler for persistence failures
     */
    fun persistence(
        storage: StateStorage<TState>,
        strategy: PersistenceStrategy = PersistenceStrategy.Debounced(500),
        errorHandler: (Exception) -> Unit = {},
        logger: Logger = Logger.default()
    ) {
        val persistenceMiddleware = PersistenceMiddleware(
            storage = storage,
            strategy = strategy,
            errorHandler = errorHandler,
            logger = logger
        )
        // Add as middleware to handle RestoreStateAction and OnAction strategies
        middleware { store, next, action ->
            // Special handling for RestoreStateAction
            if (action is RestoreStateAction<*>) {
                // Pass it through to let the reducer handle it
                val result = next(action)
                // Update the previous state to match restored state to prevent re-saving
                @Suppress("UNCHECKED_CAST")
                persistenceMiddleware.previousState = action.state as TState
                // Now we can mark as initialized after restore is processed
                persistenceMiddleware.markInitialized()
                result
            } else {
                // Mark as initialized on first real action (after restoration is complete)
                persistenceMiddleware.markInitialized()
                
                // Process the action first
                val result = next(action)
                
                // Handle OnAction strategy (Flow-based strategies are handled by the collector)
                val currentState = store.state.value
                if (strategy is PersistenceStrategy.OnAction &&
                    action::class in strategy.actionTypes) {
                    try {
                        storage.save(currentState)
                        logger.debug { "Persisted state for OnAction strategy" }
                    } catch (e: Exception) {
                        errorHandler(e)
                    }
                }
                
                // Handle OnAction in combined strategies
                if (strategy is PersistenceStrategy.Combined) {
                    strategy.strategies.filterIsInstance<PersistenceStrategy.OnAction>().forEach { onActionStrategy ->
                        if (action::class in onActionStrategy.actionTypes) {
                            try {
                                storage.save(currentState)
                                logger.debug { "Persisted state for OnAction in combined strategy" }
                            } catch (e: Exception) {
                                errorHandler(e)
                            }
                        }
                    }
                }
                
                result
            }
        }
        // Add as lifecycle-aware to handle state restoration
        lifecycleAware(persistenceMiddleware)
    }

    /**
     * Adds a custom middleware function to the chain.
     *
     * @param block The custom middleware function
     */
    fun middleware(block: Middleware<TState>) {
        middleware.add(block)
    }

    /**
     * Adds lifecycle-aware middleware to the chain.
     *
     * @param lifecycleAware The lifecycle-aware middleware instance
     */
    fun lifecycleAware(lifecycleAware: StoreLifecycleAware<TState>) {
        lifecycleAwareMiddleware.add(lifecycleAware)
    }

    /**
     * Composes all added middleware into a single middleware function.
     *
     * @return A composed middleware function
     */
    internal fun compose(): Middleware<TState> {
        return compose(*middleware.toTypedArray())
    }

    /**
     * Composes multiple middleware functions into a single middleware function.
     *
     * This implementation follows the Redux middleware composition pattern,
     * where each middleware wraps the next middleware in the chain.
     *
     * @param middlewares The middleware functions to compose
     * @return A composed middleware function
     */
    private fun compose(vararg middlewares: Middleware<TState>): Middleware<TState> {
        return { store, next, action ->
            // Start with the final `next` function (e.g., the store's dispatch)
            var currentNext: suspend (Action) -> Action = next

            // Wrap each middleware around the previous `next`, from right to left
            for (middleware in middlewares.reversed()) {
                val previousNext = currentNext
                currentNext = { act ->
                    middleware(store, previousNext, act)
                }
            }

            // Invoke the composed chain with the initial action
            currentNext(action)
        }
    }
}