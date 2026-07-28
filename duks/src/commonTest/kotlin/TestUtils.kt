package duks

import duks.logging.LogLevel
import duks.logging.Logger
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*

/**
 * Test logger that collects formatted log lines for assertions.
 */
class TestLogger : Logger {
    override var logLevel: LogLevel = LogLevel.TRACE
    val messages = mutableListOf<String>()

    override fun trace(message: String, vararg args: Any?) {
        messages.add("[TRACE] ${formatMessage(message, *args)}")
    }

    override fun debug(message: String, vararg args: Any?) {
        messages.add("[DEBUG] ${formatMessage(message, *args)}")
    }

    override fun info(message: String, vararg args: Any?) {
        messages.add("[INFO] ${formatMessage(message, *args)}")
    }

    override fun warn(message: String, vararg args: Any?) {
        messages.add("[WARN] ${formatMessage(message, *args)}")
    }

    override fun error(message: String, vararg args: Any?) {
        messages.add("[ERROR] ${formatMessage(message, *args)}")
    }

    override fun fatal(message: String, vararg args: Any?) {
        messages.add("[FATAL] ${formatMessage(message, *args)}")
    }

    override fun warn(message: String, throwable: Throwable, vararg args: Any?) {
        messages.add("[WARN] ${formatMessage(message, *args)} - ${throwable.message}")
    }

    override fun error(message: String, throwable: Throwable, vararg args: Any?) {
        messages.add("[ERROR] ${formatMessage(message, *args)} - ${throwable.message}")
    }

    override fun fatal(message: String, throwable: Throwable, vararg args: Any?) {
        messages.add("[FATAL] ${formatMessage(message, *args)} - ${throwable.message}")
    }
}

/**
 * Creates a store whose [KStore.ioScope] is a child of this [TestScope.backgroundScope]
 * so store work is driven by the test scheduler and cancelled when the test ends.
 *
 * Prefer [dispatchAndAdvance] or [KStore.dispatchAsync] over ad-hoc [delay] loops.
 */
fun <TState : StateModel> TestScope.createStoreForTest(
    initialState: TState,
    block: StoreBuilder<TState>.() -> Unit
): KStore<TState> {
    return createStore(initialState) {
        scope(this@createStoreForTest.backgroundScope)
        block()
    }
}

/**
 * In-memory [ActionCache] for tests with hit/miss stats and forced expiry.
 *
 * Keys use [CacheableAction.cacheKey] to match production [MapActionCache].
 */
class TestActionCache : ActionCache {
    private val cache: MutableMap<String, CachedActions> = mutableMapOf()
    private var expired = false

    val stats = mutableMapOf<String, Int>()

    private fun keyFor(action: CacheableAction): String = action.cacheKey

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
        cache[keyFor(action)] = cached
        stats["puts"] = (stats["puts"] ?: 0) + 1
    }

    override fun get(action: CacheableAction): CachedActions? {
        if (expired) return null
        return cache[keyFor(action)]
    }

    fun expireAll() {
        expired = true
    }
}

/**
 * Creates a store with outermost tracking middleware that records every action
 * entering the chain (including nested [KStore.dispatch] re-entries).
 *
 * **Caveat:** the user [block] should register its own middleware via
 * `middleware { }` after this helper's tracker is installed. Tracker is outermost.
 */
fun <TState : StateModel> TestScope.createTrackedStoreForTest(
    initialState: TState,
    block: StoreBuilder<TState>.() -> Unit
): Pair<KStore<TState>, MutableList<Action>> {
    val dispatchedActions = mutableListOf<Action>()

    val trackingMiddleware: Middleware<TState> = { _, next, action ->
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
 * Middleware that records before/after markers for composition-order tests.
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
 * Processes [action] through middleware + reducer ([KStore.dispatchAsync]), then
 * pumps the test scheduler so nested fire-and-forget work (async flows, sagas,
 * persistence collectors) can settle.
 */
suspend fun <TState : StateModel> TestScope.dispatchAndAdvance(
    store: KStore<TState>,
    action: Action
) {
    store.dispatchAsync(action)
    runCurrent()
    advanceUntilIdle()
}

/**
 * Pumps the test scheduler until [condition] is true or [maxPumps] is exceeded.
 *
 * Does **not** use wall-clock [kotlinx.coroutines.delay]; use this instead of
 * polling loops with arbitrary delays.
 */
fun TestScope.awaitUntil(
    maxPumps: Int = 1_000,
    condition: () -> Boolean
) {
    var pumps = 0
    while (!condition() && pumps < maxPumps) {
        runCurrent()
        advanceUntilIdle()
        pumps++
    }
    if (!condition()) {
        throw AssertionError("Condition not met after $maxPumps scheduler pumps")
    }
}

/**
 * Waits until [StateFlow] emits a value matching [predicate].
 * Uses the test scheduler only (no wall-clock sleep).
 */
fun <T> TestScope.awaitState(
    stateFlow: StateFlow<T>,
    maxPumps: Int = 1_000,
    predicate: (T) -> Boolean
) {
    if (predicate(stateFlow.value)) return

    var matched = predicate(stateFlow.value)
    val collectJob = backgroundScope.launch {
        stateFlow.first { predicate(it) }
        matched = true
    }

    var pumps = 0
    while (collectJob.isActive && pumps < maxPumps) {
        runCurrent()
        advanceUntilIdle()
        pumps++
    }

    if (collectJob.isActive) {
        collectJob.cancel()
        throw AssertionError("awaitState predicate not satisfied after $maxPumps pumps; last value=${stateFlow.value}")
    }
    if (!matched && !predicate(stateFlow.value)) {
        throw AssertionError("awaitState finished without matching value; last value=${stateFlow.value}")
    }
}
