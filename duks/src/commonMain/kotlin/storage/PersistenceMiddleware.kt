@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package duks.storage

import duks.KStore
import duks.RestoreStateAction
import duks.StateModel
import duks.StoreLifecycleAware
import duks.logging.*
import kotlinx.coroutines.Job
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

    private var collectorJob: Job? = null
    internal var previousState: TState? = null
    private var isInitialized: Boolean = false
    private var restorationComplete: Boolean = false

    /**
     * Whether the middleware has finished restoration and is allowed to persist.
     * Exposed for tests and OnAction handling in the store builder.
     */
    internal val canPersist: Boolean
        get() = isInitialized && restorationComplete

    override suspend fun onStoreCreated(store: KStore<TState>) {
        logger.info { "PersistenceMiddleware.onStoreCreated called" }

        // Notify other middleware that storage restoration is starting
        store.notifyStorageRestorationStarted()

        // Restore persisted state
        logger.info { "Attempting to restore persisted state" }
        var restored = false
        try {
            storage.load()?.let { storedState ->
                logger.info(storedState::class.simpleName) { "Successfully restored state of type: {stateType}" }
                // Align previousState before applying restore so equality checks never
                // treat the restored value as a dirty write.
                previousState = storedState
                // Await middleware + reducer so store.state is restored before the
                // Flow collector starts (fire-and-forget dispatch races with collect).
                store.processAction(RestoreStateAction(storedState))
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

        // Restoration finished. processAction(Restore) may have tried markInitialized while
        // restorationComplete was still false; enable persistence only after this flag flips.
        restorationComplete = true
        if (restored) {
            // Restored state is already applied and previousState matches — safe to allow
            // collectors (equality short-circuit prevents rewriting storage with the same value).
            markInitialized()
        }
        logger.info { "Restoration phase complete, persistence middleware ready" }

        // Notify other middleware that storage restoration has completed
        store.notifyStorageRestorationCompleted(restored)

        // Only open the collector after restore has been applied to store.state
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
            val persistenceFlow = createStrategyFlow(store, strategy)

            persistenceFlow?.collect { state ->
                logger.trace { "Flow collector received state: $state, previousState: $previousState" }
                if (!canPersist) {
                    logger.trace { "Skipping persist - middleware not initialized" }
                    return@collect
                }
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

    /**
     * Creates a flow for a single persistence strategy.
     */
    private fun createStrategyFlow(
        store: KStore<TState>,
        strategy: PersistenceStrategy
    ): Flow<TState>? {
        return when (strategy) {
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
    }

    private fun buildCombinedFlow(store: KStore<TState>, strategies: List<PersistenceStrategy>): Flow<TState>? {
        // Filter out OnAction strategies as they're handled separately
        val flowStrategies = strategies.filter { it !is PersistenceStrategy.OnAction }

        if (flowStrategies.isEmpty()) {
            return null
        }

        val flows = flowStrategies.mapNotNull { strategy ->
            createStrategyFlow(store, strategy)
        }

        return when (flows.size) {
            0 -> null
            1 -> flows.first()
            else -> merge(*flows.toTypedArray())
        }
    }

    private suspend fun persist(state: TState) {
        if (!canPersist) {
            logger.debug { "Skipping persist - middleware not initialized" }
            return
        }
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

    /**
     * Marks the middleware as initialized, allowing persistence to begin.
     * Called after restoration completes and the first eligible action is processed.
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
        collectorJob = null
    }

    override suspend fun onStoreDestroyed() {
        cleanup()
    }
}
