package duks.storage

import duks.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * Middleware that handles state persistence for any data format.
 * This allows efficient storage without forcing ByteArray conversions.
 * 
 * @param TState The type of state being persisted
 */
class PersistenceMiddleware<TState : StateModel>(
    private val storage: StateStorage<TState>,
    private val strategy: PersistenceStrategy = PersistenceStrategy.Debounced(500),
    private val errorHandler: (Exception) -> Unit = {}
) : StoreLifecycleAware<TState> {
    
    private var persistenceJob: Job? = null
    private var lastThrottledPersist: Long = 0L
    private var previousState: TState? = null
    
    override suspend fun onStoreCreated(store: KStore<TState>) {
        // Restore persisted state
        try {
            storage.load()?.let { storedState ->
                store.dispatch(RestoreStateAction(storedState))
            }
        } catch (e: Exception) {
            errorHandler(e)
        }
    }
    
    suspend fun handleStateChange(current: TState, store: KStore<TState>) {
        // Skip if state hasn't changed
        if (current == previousState) {
            return
        }
        
        val previous = previousState
        previousState = current
        
        when (strategy) {
            is PersistenceStrategy.OnEveryChange -> {
                persist(current)
            }
            is PersistenceStrategy.Debounced -> {
                persistenceJob?.cancel()
                persistenceJob = store.ioScope.launch {
                    delay(strategy.delayMs)
                    persist(current)
                }
            }
            is PersistenceStrategy.Throttled -> {
                persistThrottled(current, strategy.intervalMs)
            }
            is PersistenceStrategy.Conditional -> {
                if (previous != null && strategy.shouldPersist(current, previous)) {
                    persist(current)
                }
            }
            // OnAction handled in middleware process function
            is PersistenceStrategy.OnAction -> { /* Handled in process() */ }
            is PersistenceStrategy.Combined -> {
                handleCombinedStrategies(current, previous, strategy.strategies, store)
            }
        }
    }
    
    
    private suspend fun persist(state: TState) {
        try {
            storage.save(state)
        } catch (e: Exception) {
            errorHandler(e)
        }
    }
    
    private suspend fun persistThrottled(state: TState, intervalMs: Long) {
        // Note: This uses real system time, not virtual test time
        // Tests using throttled persistence may not work correctly with test time advancement
        val now = Clock.System.now().toEpochMilliseconds()
        if (now - lastThrottledPersist >= intervalMs) {
            lastThrottledPersist = now
            persist(state)
        }
    }
    
    private suspend fun handleCombinedStrategies(
        current: TState,
        previous: TState?,
        strategies: List<PersistenceStrategy>,
        store: KStore<TState>
    ) {
        for (strategy in strategies) {
            when (strategy) {
                is PersistenceStrategy.OnEveryChange -> {
                    persist(current)
                    return // Any strategy triggering is enough
                }
                is PersistenceStrategy.Debounced -> {
                    persistenceJob?.cancel()
                    persistenceJob = store.ioScope.launch {
                        delay(strategy.delayMs)
                        persist(current)
                    }
                    return
                }
                is PersistenceStrategy.Throttled -> {
                    if (shouldPersistThrottled(strategy.intervalMs)) {
                        persistThrottled(current, strategy.intervalMs)
                        return
                    }
                }
                is PersistenceStrategy.Conditional -> {
                    if (previous != null && strategy.shouldPersist(current, previous)) {
                        persist(current)
                        return
                    }
                }
                // OnAction handled in process()
                is PersistenceStrategy.OnAction -> { /* Handled in process() */ }
                is PersistenceStrategy.Combined -> {
                    // Nested combined strategies - recursively handle
                    handleCombinedStrategies(current, previous, strategy.strategies, store)
                }
            }
        }
    }
    
    private fun shouldPersistThrottled(intervalMs: Long): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        return now - lastThrottledPersist >= intervalMs
    }
}

