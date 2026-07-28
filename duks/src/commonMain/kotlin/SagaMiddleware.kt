package duks

import duks.storage.*
import duks.logging.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Saga lifecycle events for persistence decisions.
 */
private enum class SagaEvent {
    Started,
    Updated,
    Completed
}

/**
 * Creates saga middleware that manages saga instances and their lifecycle.
 *
 * @param registry The saga registry containing all saga definitions
 * @param storage Optional storage for saga persistence. If provided, sagas will be persisted and restored automatically.
 * @param persistenceStrategy The strategy for when to persist saga state (defaults to OnEveryChange)
 * @param logger Logger for error logging
 */
fun <TState : StateModel> sagaMiddleware(
    registry: SagaRegistry<TState>,
    storage: SagaStorage? = null,
    persistenceStrategy: SagaPersistenceStrategy = SagaPersistenceStrategy.OnEveryChange,
    logger: Logger = Logger.default()
): Middleware<TState> = SagaMiddlewareImpl(registry, storage, persistenceStrategy, logger)

/**
 * Implementation of saga middleware with built-in persistence support.
 */
private class SagaMiddlewareImpl<TState : StateModel>(
    private val registry: SagaRegistry<TState>,
    private val storage: SagaStorage?,
    private val persistenceStrategy: SagaPersistenceStrategy,
    private val logger: Logger
) : Middleware<TState>, StoreLifecycleAware<TState> {

    private val instanceManager = SagaInstanceManager()
    private var store: KStore<TState>? = null
    private var persistence: SagaPersistenceController? = null

    override suspend fun onStoreCreated(store: KStore<TState>) {
        this.store = store
        persistence = SagaPersistenceController(
            storage = storage,
            strategy = persistenceStrategy,
            scope = store.ioScope,
            logger = logger,
            getInstance = { id -> instanceManager.getInstance(id) }
        )

        if (storage != null) {
            try {
                val sagaIds = storage.getAllSagaIds()
                sagaIds.forEach { sagaId ->
                    val instance = storage.load(sagaId)
                    if (instance != null) {
                        instanceManager.addInstance(instance)
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Error restoring sagas" }
            }
        }
    }

    override suspend fun onStoreDestroyed() {
        persistence?.cancelAll()
        persistence = null
        store = null
    }

    override suspend fun invoke(
        store: KStore<TState>,
        next: suspend (Action) -> Action,
        action: Action
    ): Action {
        val result = next(action)

        store.ioScope.launch {
            try {
                processAction(
                    action = action,
                    store = store,
                    registry = registry,
                    instanceManager = instanceManager,
                    persistence = persistence,
                    logger = logger
                )
            } catch (e: Exception) {
                logger.error(e) { "Error in saga middleware" }
            }
        }

        return result
    }
}

/**
 * Handles write/remove/debounce/checkpoint persistence for sagas.
 */
private class SagaPersistenceController(
    private val storage: SagaStorage?,
    private val strategy: SagaPersistenceStrategy,
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val getInstance: suspend (String) -> SagaInstance<*>?
) {
    private val debounceJobs = mutableMapOf<String, Job>()
    private val mutex = Mutex()

    suspend fun onStarted(instance: SagaInstance<*>) {
        persistForEvent(instance, SagaEvent.Started)
    }

    suspend fun onUpdated(instance: SagaInstance<*>) {
        persistForEvent(instance, SagaEvent.Updated)
    }

    suspend fun onCompleted(instanceId: String) {
        cancelDebounce(instanceId)
        if (storage == null) return
        try {
            storage.remove(instanceId)
        } catch (e: Exception) {
            logger.error(e, instanceId) { "Failed to remove completed saga instance {id}" }
        }
    }

    suspend fun checkpoint(instanceId: String) {
        if (storage == null) return
        if (!includesCheckpoint(strategy)) {
            logger.debug(instanceId) {
                "Saga checkpoint ignored for {id} — strategy does not include OnCheckpoint"
            }
            return
        }
        val instance = getInstance(instanceId) ?: run {
            logger.warn(instanceId) { "Saga checkpoint failed — instance {id} not found" }
            return
        }
        try {
            storage.save(instanceId, instance)
            logger.debug(instanceId) { "Saga checkpoint persisted for {id}" }
        } catch (e: Exception) {
            logger.error(e, instanceId) { "Failed to checkpoint saga instance {id}" }
        }
    }

    suspend fun cancelAll() {
        mutex.withLock {
            debounceJobs.values.forEach { it.cancel() }
            debounceJobs.clear()
        }
    }

    private suspend fun persistForEvent(instance: SagaInstance<*>, event: SagaEvent) {
        if (storage == null) return
        applyStrategy(strategy, instance, event)
    }

    private suspend fun applyStrategy(
        strategy: SagaPersistenceStrategy,
        instance: SagaInstance<*>,
        event: SagaEvent
    ) {
        when (strategy) {
            is SagaPersistenceStrategy.OnEveryChange -> {
                if (event != SagaEvent.Completed) {
                    saveNow(instance)
                }
            }
            is SagaPersistenceStrategy.Debounced -> {
                if (event != SagaEvent.Completed) {
                    scheduleDebounced(instance, strategy.delayMs)
                }
            }
            is SagaPersistenceStrategy.OnCheckpoint -> {
                // Explicit checkpoint() only
            }
            is SagaPersistenceStrategy.OnCompletion -> {
                // Intermediate writes skipped; completion removes via onCompleted
            }
            is SagaPersistenceStrategy.Combined -> {
                strategy.strategies.forEach { applyStrategy(it, instance, event) }
            }
        }
    }

    private suspend fun saveNow(instance: SagaInstance<*>) {
        val storage = storage ?: return
        try {
            storage.save(instance.id, instance)
        } catch (e: Exception) {
            logger.error(e, instance.id) { "Failed to persist saga instance {id}" }
        }
    }

    private suspend fun scheduleDebounced(instance: SagaInstance<*>, delayMs: Long) {
        val storage = storage ?: return
        mutex.withLock {
            debounceJobs[instance.id]?.cancel()
            debounceJobs[instance.id] = scope.launch {
                delay(delayMs)
                val latest = getInstance(instance.id) ?: return@launch
                try {
                    storage.save(latest.id, latest)
                } catch (e: Exception) {
                    logger.error(e, latest.id) { "Failed to persist debounced saga instance {id}" }
                } finally {
                    mutex.withLock {
                        debounceJobs.remove(latest.id)
                    }
                }
            }
        }
    }

    private suspend fun cancelDebounce(instanceId: String) {
        mutex.withLock {
            debounceJobs.remove(instanceId)?.cancel()
        }
    }

    private fun includesCheckpoint(strategy: SagaPersistenceStrategy): Boolean {
        return when (strategy) {
            is SagaPersistenceStrategy.OnCheckpoint -> true
            is SagaPersistenceStrategy.Combined -> strategy.strategies.any { includesCheckpoint(it) }
            else -> false
        }
    }
}

/**
 * Internal saga instance manager.
 */
private class SagaInstanceManager {
    private val instances = mutableMapOf<String, SagaInstance<*>>()
    private val mutex = Mutex()

    suspend fun addInstance(instance: SagaInstance<*>) {
        mutex.withLock {
            instances[instance.id] = instance
        }
    }

    suspend fun updateInstance(instanceId: String, newState: Any) {
        mutex.withLock {
            val current = instances[instanceId]
            if (current != null) {
                @Suppress("UNCHECKED_CAST")
                instances[instanceId] = (current as SagaInstance<Any>).copy(
                    state = newState,
                    lastUpdatedAt = Clock.System.now().toEpochMilliseconds()
                )
            }
        }
    }

    suspend fun removeInstance(instanceId: String) {
        mutex.withLock {
            instances.remove(instanceId)
        }
    }

    suspend fun getActiveInstances(): List<SagaInstance<*>> {
        mutex.withLock {
            return instances.values.toList()
        }
    }

    suspend fun getInstance(instanceId: String): SagaInstance<*>? {
        mutex.withLock {
            return instances[instanceId]
        }
    }
}

/**
 * Process an action through all sagas and active instances.
 */
private suspend fun <TState : StateModel> processAction(
    action: Action,
    store: KStore<TState>,
    registry: SagaRegistry<TState>,
    instanceManager: SagaInstanceManager,
    persistence: SagaPersistenceController?,
    logger: Logger
) {
    registry.sagas.values.forEach { configuredSaga ->
        try {
            checkAndStartSaga(action, configuredSaga, store, instanceManager, persistence, logger)
        } catch (e: Exception) {
            logger.error(e, configuredSaga.name) { "Error starting saga {name}" }
        }
    }

    val activeInstances = instanceManager.getActiveInstances()
    activeInstances.forEach { instance ->
        try {
            val saga = registry.sagas[instance.sagaName]
            if (saga != null) {
                processInstanceAction(action, instance, saga, store, instanceManager, persistence, logger)
            }
        } catch (e: Exception) {
            logger.error(e, instance.id) { "Error processing saga instance {id}" }
        }
    }
}

/**
 * Check if an action should start a new saga instance.
 */
private suspend fun <TState : StateModel> checkAndStartSaga(
    action: Action,
    saga: ConfiguredSaga<*>,
    store: KStore<TState>,
    instanceManager: SagaInstanceManager,
    persistence: SagaPersistenceController?,
    logger: Logger
) {
    @Suppress("UNCHECKED_CAST")
    val typedSaga = saga as ConfiguredSaga<Any>

    val allHandlers = typedSaga.configuration.getHandlers()
    val startHandlers = mutableListOf<SagaHandler<Any>>()

    for (handler in allHandlers) {
        if (handler.canHandle(action, null)) {
            startHandlers.add(handler)
        }
    }

    if (startHandlers.isNotEmpty()) {
        val instanceId = generateSagaId(saga.name)
        val context = createContext(instanceId, store, persistence, logger)

        val handler = startHandlers.first()
        val transition = try {
            handler.handle(action, null, context)
        } catch (e: Exception) {
            logger.error(e, saga.name, instanceId, e.message) {
                "Saga {sagaName} with id {sagaId} failed during start: {error}"
            }
            throw e
        }

        when (transition) {
            is SagaTransition.Continue -> {
                val instance = SagaInstance(
                    id = instanceId,
                    sagaName = saga.name,
                    state = transition.newState,
                    startedAt = Clock.System.now().toEpochMilliseconds(),
                    lastUpdatedAt = Clock.System.now().toEpochMilliseconds()
                )
                instanceManager.addInstance(instance)
                logger.info(saga.name, instanceId) { "Saga started: {sagaName} with id {sagaId}" }
                persistence?.onStarted(instance)
                executeEffects(transition.effects, context)
            }
            is SagaTransition.Complete -> {
                executeEffects(transition.effects, context)
            }
        }
    }
}

/**
 * Process an action through an active saga instance.
 */
private suspend fun <TState : StateModel> processInstanceAction(
    action: Action,
    instance: SagaInstance<*>,
    saga: ConfiguredSaga<*>,
    store: KStore<TState>,
    instanceManager: SagaInstanceManager,
    persistence: SagaPersistenceController?,
    logger: Logger
) {
    @Suppress("UNCHECKED_CAST")
    val typedSaga = saga as ConfiguredSaga<Any>
    @Suppress("UNCHECKED_CAST")
    val typedInstance = instance as SagaInstance<Any>

    val allHandlers = typedSaga.configuration.getHandlers()
    val activeHandlers = mutableListOf<SagaHandler<Any>>()

    for (handler in allHandlers) {
        if (handler.canHandle(action, typedInstance.state)) {
            activeHandlers.add(handler)
        }
    }

    if (activeHandlers.isNotEmpty()) {
        val context = createContext(instance.id, store, persistence, logger)

        val handler = activeHandlers.first()
        val transition = try {
            handler.handle(action, typedInstance.state, context)
        } catch (e: Exception) {
            logger.error(e, instance.id, e.message) { "Saga {sagaId} failed during action handling: {error}" }
            throw e
        }

        when (transition) {
            is SagaTransition.Continue -> {
                instanceManager.updateInstance(instance.id, transition.newState)
                val updated = instanceManager.getInstance(instance.id)
                if (updated != null) {
                    persistence?.onUpdated(updated)
                }
                executeEffects(transition.effects, context)
            }
            is SagaTransition.Complete -> {
                instanceManager.removeInstance(instance.id)
                logger.info(instance.id) { "Saga completed: {sagaId}" }
                persistence?.onCompleted(instance.id)
                executeEffects(transition.effects, context)
            }
        }
    }
}

private fun <TState : StateModel> createContext(
    instanceId: String,
    store: KStore<TState>,
    persistence: SagaPersistenceController?,
    logger: Logger
): SagaContextImpl<Any> {
    return SagaContextImpl(
        sagaId = instanceId,
        store = store,
        dispatchFn = { action ->
            store.dispatch(action)
            action
        },
        logger = logger,
        checkpointFn = { persistence?.checkpoint(instanceId) }
    )
}

/**
 * Execute saga effects.
 */
private suspend fun executeEffects(
    effects: List<SagaEffect>,
    context: SagaContextImpl<*>
) {
    effects.forEach { effect ->
        when (effect) {
            is SagaEffect.Dispatch -> {
                context.dispatch(effect.action)
            }
            is SagaEffect.Delay -> {
                delay(effect.milliseconds)
            }
            is SagaEffect.StartSaga -> {
                context.startSaga(effect.sagaName, effect.trigger)
            }
        }
    }
}

/**
 * Implementation of SagaContext.
 */
private class SagaContextImpl<TSagaState>(
    override val sagaId: String,
    private val store: KStore<*>,
    private val dispatchFn: suspend (Action) -> Action,
    private val logger: Logger,
    private val checkpointFn: suspend () -> Unit
) : SagaContext<TSagaState> {

    @Suppress("UNCHECKED_CAST")
    override fun <T : StateModel> getStoreState(): T {
        return store.state.value as T
    }

    override suspend fun dispatch(action: Action): Action {
        logger.debug(sagaId, action::class.simpleName) { "Saga {sagaId} dispatching action: {action}" }
        return dispatchFn(action)
    }

    override suspend fun delay(milliseconds: Long) {
        kotlinx.coroutines.delay(milliseconds)
    }

    override suspend fun startSaga(sagaName: String, trigger: Action) {
        dispatch(trigger)
    }

    override suspend fun checkpoint() {
        checkpointFn()
    }
}

/**
 * Generate a unique saga instance ID.
 */
private fun generateSagaId(sagaName: String): String {
    return "$sagaName-${Clock.System.now().toEpochMilliseconds()}-${(0..9999).random()}"
}
