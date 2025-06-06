package duks

import duks.storage.PersistenceMiddleware
import duks.storage.PersistenceStrategy
import duks.storage.StateStorage
import duks.storage.SagaStorage
import duks.storage.SagaPersistenceStrategy
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
                                                     lifecycleAwareMiddleware: List<StoreLifecycleAware<TState>> = emptyList()) {
    
    private val _state = MutableStateFlow(initialState)
    private val _stateMutex = Mutex()

    init {
        // Notify lifecycle-aware middleware that the store has been created
        ioScope.launch {
            lifecycleAwareMiddleware.forEach { middleware ->
                middleware.onStoreCreated(this@KStore)
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
        _state.value = reducer(_state.value, action)
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
    private var reducer: Reducer<TState> = { state, action -> state }
    private var ioScope: CoroutineScope = CoroutineScope(backgroundDispatcher() + Job())

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
     * Sets the reducer function for the store.
     *
     * @param reducer The reducer function that will process actions and update state
     */
    fun reduceWith(reducer: Reducer<TState>) {
        this.reducer = reducer
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
     * Builds and returns a KStore instance with the configured options.
     *
     * @return A configured KStore instance
     * @throws IllegalStateException if the initial state has not been set
     */
    internal fun build() : KStore<TState> {
        if (initialState == null) {
            throw IllegalStateException("Initial state must be set")
        }
        return KStore(initialState!!, reducer, middlewareBuilder.compose(), ioScope, middlewareBuilder.lifecycleAwareMiddleware)
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
    private val middleware = mutableListOf<Middleware<TState>>()
    internal val lifecycleAwareMiddleware = mutableListOf<StoreLifecycleAware<TState>>()

    /**
     * Adds logging middleware to the chain.
     *
     * @param log The function to use for logging (defaults to println)
     */
    fun logging(log: (String) -> Unit = ::println) {
        middleware.add(loggerMiddleware(log))
    }

    /**
     * Adds exception handling middleware to the chain.
     */
    fun exceptionHandling() {
        middleware.add(exceptionMiddleware())
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
     * @param logError Error logging function
     * @param block A configuration block for defining sagas
     */
    fun sagas(
        storage: SagaStorage? = null,
        persistenceStrategy: SagaPersistenceStrategy = SagaPersistenceStrategy.OnEveryChange,
        logError: (String) -> Unit = ::println,
        block: SagaRegistry<TState>.() -> Unit
    ) {
        val registry = SagaRegistry<TState>()
        block(registry)
        val sagaMiddleware = sagaMiddleware(registry, storage, persistenceStrategy, logError)
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
        errorHandler: (Exception) -> Unit = {}
    ) {
        val persistenceMiddleware = PersistenceMiddleware(
            storage = storage,
            strategy = strategy,
            errorHandler = errorHandler
        )
        // Add as middleware to handle RestoreStateAction and OnAction strategies
        middleware { store, next, action ->
            // Special handling for RestoreStateAction
            if (action is RestoreStateAction<*>) {
                // Pass it through to let the reducer handle it
                next(action)
                action
            } else {
                // Process the action first
                val result = next(action)
                
                // Get the current state after processing
                val currentState = store.state.value
                
                // Handle state change for non-OnAction strategies
                when (strategy) {
                    is PersistenceStrategy.OnAction -> { /* Skip - handled below */ }
                    is PersistenceStrategy.Combined -> {
                        // Only handle non-OnAction strategies
                        if (strategy.strategies.any { it !is PersistenceStrategy.OnAction }) {
                            // Handle state change directly in the current coroutine context
                            persistenceMiddleware.handleStateChange(currentState, store)
                        }
                    }
                    else -> {
                        // All other strategies - handle directly in the current coroutine context
                        persistenceMiddleware.handleStateChange(currentState, store)
                    }
                }
                
                // Handle OnAction strategy
                if (strategy is PersistenceStrategy.OnAction &&
                    action::class in strategy.actionTypes) {
                    try {
                        storage.save(currentState)
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