package duks

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

fun <TState : StateModel> TestScope.createStoreForTest(
    initialState: TState,
    block: StoreBuilder<TState>.() -> Unit
): KStore<TState> {
    return createStore(initialState) {
        val testScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        scope(testScope, testScope)
        
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