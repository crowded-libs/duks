package duks

import duks.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Saga lifecycle events for determining when to persist.
 */
private enum class SagaEvent {
    Started,
    Updated,
    Completed
}

/**
 * Determines if a saga should be persisted based on the strategy and event.
 */
private fun shouldPersist(strategy: SagaPersistenceStrategy, event: SagaEvent): Boolean {
    return when (strategy) {
        is SagaPersistenceStrategy.OnEveryChange -> true
        is SagaPersistenceStrategy.Debounced -> event != SagaEvent.Completed // Debounced handles its own timing
        is SagaPersistenceStrategy.OnCheckpoint -> false // Only on explicit checkpoints
        is SagaPersistenceStrategy.OnCompletion -> event == SagaEvent.Completed
        is SagaPersistenceStrategy.Combined -> strategy.strategies.any { shouldPersist(it, event) }
    }
}

/**
 * Creates saga middleware that manages saga instances and their lifecycle.
 * 
 * @param registry The saga registry containing all saga definitions
 * @param storage Optional storage for saga persistence. If provided, sagas will be persisted and restored automatically.
 * @param persistenceStrategy The strategy for when to persist saga state (defaults to OnEveryChange)
 * @param logError Error logging function
 */
fun <TState : StateModel> sagaMiddleware(
    registry: SagaRegistry<TState>,
    storage: SagaStorage? = null,
    persistenceStrategy: SagaPersistenceStrategy = SagaPersistenceStrategy.OnEveryChange,
    logError: (String) -> Unit = ::println
): Middleware<TState> = SagaMiddlewareImpl(registry, storage, persistenceStrategy, logError)

/**
 * Implementation of saga middleware with built-in persistence support.
 */
private class SagaMiddlewareImpl<TState : StateModel>(
    private val registry: SagaRegistry<TState>,
    private val storage: SagaStorage?,
    private val persistenceStrategy: SagaPersistenceStrategy,
    private val logError: (String) -> Unit
) : Middleware<TState>, StoreLifecycleAware<TState> {
    
    private val instanceManager = SagaInstanceManager()
    private var store: KStore<TState>? = null
    
    override suspend fun onStoreCreated(store: KStore<TState>) {
        this.store = store
        
        // Restore persisted sagas on startup
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
                logError("Error restoring sagas: ${e.message}")
            }
        }
    }
    
    override suspend fun invoke(
        store: KStore<TState>,
        next: suspend (Action) -> Action,
        action: Action
    ): Action {
        val result = next(action)
        
        // Process action through all registered sagas
        store.ioScope.launch {
            try {
                processAction(action, store, next, registry, instanceManager, storage, persistenceStrategy, logError)
            } catch (e: Exception) {
                logError("Error in saga middleware: ${e.message}")
            }
        }
        
        return result
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
                    lastUpdatedAt = currentTimeMillis()
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
    dispatch: suspend (Action) -> Action,
    registry: SagaRegistry<TState>,
    instanceManager: SagaInstanceManager,
    storage: SagaStorage?,
    persistenceStrategy: SagaPersistenceStrategy,
    logError: (String) -> Unit
) {
    // Check for new saga triggers
    registry.sagas.values.forEach { configuredSaga ->
        try {
            checkAndStartSaga(action, configuredSaga, store, instanceManager, storage, persistenceStrategy, logError)
        } catch (e: Exception) {
            logError("Error starting saga ${configuredSaga.name}: ${e.message}")
        }
    }
    
    // Process action through active saga instances
    val activeInstances = instanceManager.getActiveInstances()
    activeInstances.forEach { instance ->
        try {
            val saga = registry.sagas[instance.sagaName]
            if (saga != null) {
                processInstanceAction(action, instance, saga, store, instanceManager, storage, persistenceStrategy, logError)
            }
        } catch (e: Exception) {
            logError("Error processing saga instance ${instance.id}: ${e.message}")
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
    storage: SagaStorage?,
    persistenceStrategy: SagaPersistenceStrategy,
    logError: (String) -> Unit
) {
    @Suppress("UNCHECKED_CAST")
    val typedSaga = saga as ConfiguredSaga<Any>
    
    // Find matching start handlers
    val allHandlers = typedSaga.configuration.getHandlers()
    val startHandlers = mutableListOf<SagaHandler<Any>>()
    
    for (handler in allHandlers) {
        if (handler.canHandle(action, null)) {
            startHandlers.add(handler)
        }
    }
    
    if (startHandlers.isNotEmpty()) {
        val instanceId = generateSagaId(saga.name)
        val context = SagaContextImpl<Any>(instanceId, store, { action ->
            store.dispatch(action)
            action
        }, instanceManager, logError)
        
        // Execute the first matching handler
        val handler = startHandlers.first()
        val transition = handler.handle(action, null, context)
        
        when (transition) {
            is SagaTransition.Continue -> {
                // Create new saga instance
                val instance = SagaInstance(
                    id = instanceId,
                    sagaName = saga.name,
                    state = transition.newState,
                    startedAt = currentTimeMillis(),
                    lastUpdatedAt = currentTimeMillis()
                )
                instanceManager.addInstance(instance)
                
                // Persist the new saga instance if storage is configured
                if (storage != null && shouldPersist(persistenceStrategy, SagaEvent.Started)) {
                    try {
                        storage.save(instanceId, instance)
                    } catch (e: Exception) {
                        logError("Failed to persist saga instance ${instanceId}: ${e.message}")
                    }
                }
                
                // Execute effects
                executeEffects(transition.effects, context)
            }
            is SagaTransition.Complete -> {
                // Just execute effects, no instance to create
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
    storage: SagaStorage?,
    persistenceStrategy: SagaPersistenceStrategy,
    logError: (String) -> Unit
) {
    @Suppress("UNCHECKED_CAST")
    val typedSaga = saga as ConfiguredSaga<Any>
    @Suppress("UNCHECKED_CAST")
    val typedInstance = instance as SagaInstance<Any>
    
    // Find matching handlers for active instances
    val allHandlers = typedSaga.configuration.getHandlers()
    val activeHandlers = mutableListOf<SagaHandler<Any>>()
    
    for (handler in allHandlers) {
        if (handler.canHandle(action, typedInstance.state)) {
            activeHandlers.add(handler)
        }
    }
    
    if (activeHandlers.isNotEmpty()) {
        val context = SagaContextImpl<Any>(instance.id, store, { action ->
            store.dispatch(action)
            action
        }, instanceManager, logError)
        
        // Execute the first matching handler
        val handler = activeHandlers.first()
        val transition = handler.handle(action, typedInstance.state, context)
        
        when (transition) {
            is SagaTransition.Continue -> {
                // Update instance state
                instanceManager.updateInstance(instance.id, transition.newState)
                
                // Persist the updated saga instance if storage is configured
                if (storage != null && shouldPersist(persistenceStrategy, SagaEvent.Updated)) {
                    val updatedInstance = instanceManager.getInstance(instance.id)
                    if (updatedInstance != null) {
                        try {
                            storage.save(instance.id, updatedInstance)
                        } catch (e: Exception) {
                            logError("Failed to persist updated saga instance ${instance.id}: ${e.message}")
                        }
                    }
                }
                
                // Execute effects
                executeEffects(transition.effects, context)
            }
            is SagaTransition.Complete -> {
                // Remove instance
                instanceManager.removeInstance(instance.id)
                
                // Remove from persistence if storage is configured
                if (storage != null) {
                    try {
                        storage.remove(instance.id)
                    } catch (e: Exception) {
                        logError("Failed to remove completed saga instance ${instance.id}: ${e.message}")
                    }
                }
                
                // Execute effects
                executeEffects(transition.effects, context)
            }
        }
    }
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
    private val instanceManager: SagaInstanceManager,
    private val logError: (String) -> Unit
) : SagaContext<TSagaState> {
    
    @Suppress("UNCHECKED_CAST")
    override fun <T : StateModel> getStoreState(): T {
        return store.state.value as T
    }
    
    override suspend fun dispatch(action: Action): Action {
        return dispatchFn(action)
    }
    
    override suspend fun delay(milliseconds: Long) {
        kotlinx.coroutines.delay(milliseconds)
    }
    
    override suspend fun startSaga(sagaName: String, trigger: Action) {
        // This will be picked up by the middleware on the next action
        dispatch(trigger)
    }
}

/**
 * Generate a unique saga instance ID.
 */
private fun generateSagaId(sagaName: String): String {
    return "$sagaName-${currentTimeMillis()}-${(0..9999).random()}"
}

/**
 * Get current time in milliseconds (multiplatform).
 */
internal expect fun currentTimeMillis(): Long