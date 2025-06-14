package duks

import kotlinx.coroutines.test.*

fun <TState : StateModel> TestScope.createStoreForTest(
    initialState: TState,
    block: StoreBuilder<TState>.() -> Unit
): KStore<TState> {
    return createStore(initialState) {
        // Use the test scope to ensure coroutines are synchronized with tests
        scope(this@createStoreForTest)
        
        block()
    }
}

class TestActionCache : ActionCache {
    private val cache: MutableMap<String, CachedActions> = mutableMapOf()
    private var expired = false
    
    val stats = mutableMapOf<String, Int>()
    
    private fun keyFor(action: CacheableAction): String = action.toString()

    override fun has(action: CacheableAction): Boolean {
        val key = keyFor(action)
        val result = cache.containsKey(key) && !expired
        
        if (result) {
            stats["hits"] = (stats["hits"] ?: 0) + 1
        } else {
            stats["misses"] = (stats["misses"] ?: 0) + 1
        }
        
        return result
    }

    override fun put(action: CacheableAction, cached: CachedActions) {
        val key = keyFor(action)
        cache[key] = cached
        stats["puts"] = (stats["puts"] ?: 0) + 1
    }

    override fun get(action: CacheableAction): CachedActions? {
        val key = keyFor(action)
        val result = if (expired) null else cache[key]
        
        if (result != null) {
        } else {
        }
        
        return result
    }

    fun expireAll() {
        expired = true
    }
}

fun <TState : StateModel> TestScope.createTrackedStoreForTest(
    initialState: TState,
    block: StoreBuilder<TState>.() -> Unit
): Pair<KStore<TState>, MutableList<Action>> {
    val dispatchedActions = mutableListOf<Action>()
    
    val trackingMiddleware: Middleware<TState> = { store, next, action ->
        dispatchedActions.add(action)
        next(action)
    }
    
    val store = createStoreForTest(initialState) {
        middleware {
            middleware(trackingMiddleware)
        }
        
        block()
    }
    
    return store to dispatchedActions
}

/**
 * Creates a middleware that tracks the execution order of actions through the middleware.
 * 
 * @param executionOrder A mutable list that will be filled with execution events
 * @param tag A string identifier to prefix the execution events 
 * @return A middleware that logs before and after action execution
 */
fun <TState : StateModel> createTracingMiddleware(
    executionOrder: MutableList<String>,
    tag: String
): Middleware<TState> = { _, next, action ->
    executionOrder.add("$tag:before")
    val result = next(action)
    executionOrder.add("$tag:after")
    result
}

/**
 * A utility function to execute an action on a store and advance the test time until idle
 * 
 * @param store The store to dispatch the action to
 * @param action The action to dispatch
 */
fun <TState : StateModel> TestScope.dispatchAndAdvance(store: KStore<TState>, action: Action) {
    store.dispatch(action)
    runCurrent()
    advanceUntilIdle()
}