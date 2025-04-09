package duks

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow

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
 * @property uiScope The coroutine scope used for UI updates
 * @property ioScope The coroutine scope used for IO operations and middleware processing
 */
class KStore<TState:StateModel> internal constructor(initialState: TState,
                                                     private val reducer: Reducer<TState>,
                                                     private val middleware: Middleware<TState>,
                                                     private val uiScope: CoroutineScope,
                                                     internal val ioScope: CoroutineScope) {
    
    private val _state = mutableStateOf(initialState)
    private val _dispatchFlow = MutableSharedFlow<Action>(extraBufferCapacity = 100)

    init {
        ioScope.launch {
            _dispatchFlow.collect { action ->
                middleware(this@KStore, { a -> this@KStore.applyReducer(a) }, action)
            }
        }
    }

    /**
     * The current state of the store, exposed as a Compose State object.
     *
     * This property allows the store's state to be observed by Compose UI components,
     * which will automatically recompose when the state changes.
     */
    val state: State<TState>
        get() = _state

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
            _dispatchFlow.tryEmit(action)
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
        val originalValue = _state.value
        val newValue = reducer(originalValue, action)
        if(newValue != originalValue) {
            _state.value = newValue
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
    private var reducer: Reducer<TState> = { state, action -> state }
    private var uiScope: CoroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private var ioScope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())

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
     * @param uiScope The coroutine scope for UI updates
     * @param ioScope The coroutine scope for IO operations and middleware
     */
    fun scope(uiScope: CoroutineScope, ioScope: CoroutineScope) {
        this.uiScope = uiScope
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
        return KStore(initialState!!, reducer, middlewareBuilder.compose(), uiScope, ioScope)
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
     * @param block A configuration block for defining sagas
     */
    fun sagas(logError: (String) -> Unit = ::println, block: SagaBuilder.() -> Unit) {
        middleware.add(sagaMiddleware(logError, block))
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