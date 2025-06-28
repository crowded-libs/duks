@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package duks.storage

import duks.KStore
import duks.RestoreStateAction
import duks.StateModel
import duks.StoreLifecycleAware
import duks.logging.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Middleware that handles state persistence for any data format.
 * This allows efficient storage without forcing ByteArray conversions.
 * 
 * @param TState The type of state being persisted
 */
class PersistenceMiddleware<TState : StateModel>(
    private val storage: StateStorage<TState>,
    private val strategy: PersistenceStrategy = PersistenceStrategy.Debounced(500),
    private val errorHandler: (Exception) -> Unit = {},
    private val logger: Logger = Logger.default()
) : StoreLifecycleAware<TState> {
    
    init {
        logger.info { "PersistenceMiddleware created with strategy: ${strategy::class.simpleName}" }
    }
    
    internal var persistenceJob: Job? = null
    private var collectorJob: Job? = null
    internal var previousState: TState? = null
    private var isInitialized: Boolean = false
    private var restorationComplete: Boolean = false
    
    override suspend fun onStoreCreated(store: KStore<TState>) {
        logger.info { "PersistenceMiddleware.onStoreCreated called" }
        // Cancel any pending persistence from initial state
        persistenceJob?.cancel()
        persistenceJob = null
        
        // Notify other middleware that storage restoration is starting
        store.notifyStorageRestorationStarted()
        
        // Restore persisted state
        logger.info { "Attempting to restore persisted state" }
        var restored = false
        try {
            storage.load()?.let { storedState ->
                logger.info(storedState::class.simpleName) { "Successfully restored state of type: {stateType}" }
                // Set previous state BEFORE dispatching restore action
                previousState = storedState
                store.dispatch(RestoreStateAction(storedState))
                logger.debug { "State restoration completed successfully" }
                restored = true
            } ?: run {
                // No stored state found
                logger.debug { "No stored state found, using initial state" }
                previousState = store.state.value
            }
        } catch (e: Exception) {
            logger.warn(e.message ?: "Unknown error") { "Failed to restore state: {error}" }
            errorHandler(e)
            previousState = store.state.value
        }
        
        // Mark restoration as complete but NOT initialized yet
        // This prevents any actions from causing persistence until we explicitly allow it
        restorationComplete = true
        logger.info { "Restoration phase complete, persistence middleware ready" }
        
        // Notify other middleware that storage restoration has completed
        store.notifyStorageRestorationCompleted(restored)
        
        // Set up Flow-based persistence collector based on strategy
        setupFlowCollector(store)
    }
    
    private fun setupFlowCollector(store: KStore<TState>) {
        // Don't set up collector for OnAction-only strategies
        if (strategy is PersistenceStrategy.OnAction) {
            logger.debug { "OnAction strategy detected, skipping Flow collector setup" }
            return
        }
        
        // Check for combined strategies that only contain OnAction
        if (strategy is PersistenceStrategy.Combined && 
            strategy.strategies.all { it is PersistenceStrategy.OnAction }) {
            logger.debug { "Combined strategy with only OnAction strategies, skipping Flow collector setup" }
            return
        }
        
        logger.info(strategy::class.simpleName) { "Setting up Flow-based persistence collector for strategy: {strategy}" }
        
        collectorJob = store.ioScope.launch {
            // Build the flow based on strategy
            val persistenceFlow = when (strategy) {
                is PersistenceStrategy.OnEveryChange -> {
                    store.state
                }
                is PersistenceStrategy.Debounced -> {
                    store.state
                        .debounce(strategy.delayMs)
                }
                is PersistenceStrategy.Conditional -> {
                    store.state
                        .scan(Pair<TState?, TState?>(null, null)) { prevPair, current ->
                            Pair(prevPair.second, current)
                        }
                        .filter { (prev, current) ->
                            prev != null && current != null && strategy.shouldPersist(current, prev)
                        }
                        .map { it.second!! }
                }
                is PersistenceStrategy.Combined -> {
                    buildCombinedFlow(store, strategy.strategies)
                }
                else -> null
            }
            
            // Collect and persist
            persistenceFlow?.collect { state ->
                logger.trace { "Flow collector received state: $state, previousState: $previousState" }
                // Check if state is different from last persisted state
                if (state != previousState) {
                    logger.debug { "State differs from previousState, persisting" }
                    persist(state)
                    previousState = state
                } else {
                    logger.trace { "State equals previousState, skipping persistence" }
                }
            }
        }
    }
    
    private fun buildCombinedFlow(store: KStore<TState>, strategies: List<PersistenceStrategy>): Flow<TState>? {
        // Filter out OnAction strategies as they're handled separately
        val flowStrategies = strategies.filter { it !is PersistenceStrategy.OnAction }
        
        if (flowStrategies.isEmpty()) {
            return null
        }
        
        // Create individual flows for each strategy
        val flows = flowStrategies.mapNotNull { strategy ->
            when (strategy) {
                is PersistenceStrategy.OnEveryChange -> {
                    store.state
                }
                is PersistenceStrategy.Debounced -> {
                    store.state
                        .debounce(strategy.delayMs)
                }
                is PersistenceStrategy.Conditional -> {
                    store.state
                        .scan(Pair<TState?, TState?>(null, null)) { prevPair, current ->
                            Pair(prevPair.second, current)
                        }
                        .filter { (prev, current) ->
                            prev != null && current != null && strategy.shouldPersist(current, prev)
                        }
                        .map { it.second!! }
                }
                is PersistenceStrategy.Combined -> {
                    // Recursively build combined flows
                    buildCombinedFlow(store, strategy.strategies)
                }
                else -> null
            }
        }
        
        // Merge all flows - any strategy triggering will cause persistence
        return when (flows.size) {
            0 -> null
            1 -> flows.first()
            else -> merge(*flows.toTypedArray())
        }
    }

    private suspend fun persist(state: TState) {
        try {
            logger.info(strategy::class.simpleName, state.toString()) { 
                "Persisting state using strategy: {strategy}. State content: {stateContent}" 
            }
            storage.save(state)
            logger.info { "State persisted successfully" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to persist state" }
            errorHandler(e)
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
    
    
    /**
     * Marks the middleware as initialized, allowing persistence to begin.
     * This should be called after the first real action is processed.
     */
    fun markInitialized() {
        if (!isInitialized && restorationComplete) {
            isInitialized = true
            logger.debug { "Persistence middleware initialized - ready to persist state changes" }
        } else if (!restorationComplete) {
            logger.debug { "Cannot initialize - restoration not yet complete" }
        }
    }
    
    /**
     * Cleanup method to cancel collector job when store is destroyed
     */
    fun cleanup() {
        logger.debug { "Cleaning up persistence middleware" }
        collectorJob?.cancel()
        persistenceJob?.cancel()
        collectorJob = null
        persistenceJob = null
    }
    
    override suspend fun onStoreDestroyed() {
        cleanup()
    }
}


