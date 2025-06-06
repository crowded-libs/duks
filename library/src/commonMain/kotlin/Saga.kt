package duks

import kotlin.reflect.KClass

/**
 * Core saga definition interface.
 * Sagas are stateful workflows that can be triggered by actions and maintain
 * their own state throughout their lifecycle.
 */
interface SagaDefinition<TSagaState : Any> {
    val name: String
    
    /**
     * Configure the saga's triggers and handlers.
     */
    fun configure(saga: SagaConfiguration<TSagaState>)
}

/**
 * Runtime representation of an active saga instance.
 */
data class SagaInstance<TSagaState>(
    val id: String,
    val sagaName: String,
    val state: TSagaState,
    val startedAt: Long,
    val lastUpdatedAt: Long
)

/**
 * Represents the result of a saga handler execution.
 */
sealed class SagaTransition<TSagaState> {
    /**
     * Continue the saga with updated state and optional effects.
     */
    data class Continue<TSagaState>(
        val newState: TSagaState,
        val effects: List<SagaEffect> = emptyList()
    ) : SagaTransition<TSagaState>()
    
    /**
     * Complete the saga and remove it from active instances.
     */
    data class Complete<TSagaState>(
        val effects: List<SagaEffect> = emptyList()
    ) : SagaTransition<TSagaState>()
}

/**
 * Effects that sagas can produce.
 */
sealed class SagaEffect {
    /**
     * Dispatch an action to the store.
     */
    data class Dispatch(val action: Action) : SagaEffect()
    
    /**
     * Delay execution for a specified duration.
     */
    data class Delay(val milliseconds: Long) : SagaEffect()
    
    /**
     * Start another saga with a trigger action.
     */
    data class StartSaga(val sagaName: String, val trigger: Action) : SagaEffect()
}

/**
 * Context provided to saga handlers for interacting with the store and other sagas.
 */
interface SagaContext<TSagaState> {
    /**
     * The unique ID of this saga instance.
     */
    val sagaId: String
    
    /**
     * Get the current store state.
     */
    fun <T : StateModel> getStoreState(): T
    
    /**
     * Dispatch an action to the store.
     */
    suspend fun dispatch(action: Action): Action
    
    /**
     * Delay execution.
     */
    suspend fun delay(milliseconds: Long)
    
    /**
     * Start another saga.
     */
    suspend fun startSaga(sagaName: String, trigger: Action)
}

/**
 * Handler types for saga configuration.
 */
sealed class SagaHandler<TSagaState> {
    abstract suspend fun canHandle(action: Action, state: TSagaState?): Boolean
    abstract suspend fun handle(
        action: Action, 
        state: TSagaState?, 
        context: SagaContext<TSagaState>
    ): SagaTransition<TSagaState>
}

class TypedStartHandler<TSagaState, T : Action>(
    private val actionClass: KClass<T>,
    private val condition: (T) -> Boolean,
    private val handler: suspend SagaContext<TSagaState>.(T) -> SagaTransition<TSagaState>
) : SagaHandler<TSagaState>() {
    override suspend fun canHandle(action: Action, state: TSagaState?): Boolean {
        @Suppress("UNCHECKED_CAST")
        return state == null && actionClass.isInstance(action) && condition(action as T)
    }
    
    override suspend fun handle(
        action: Action, 
        state: TSagaState?, 
        context: SagaContext<TSagaState>
    ): SagaTransition<TSagaState> {
        @Suppress("UNCHECKED_CAST")
        return context.handler(action as T)
    }
}

class PredicateStartHandler<TSagaState>(
    private val condition: (Action) -> Boolean,
    private val handler: suspend SagaContext<TSagaState>.(Action) -> SagaTransition<TSagaState>
) : SagaHandler<TSagaState>() {
    override suspend fun canHandle(action: Action, state: TSagaState?): Boolean {
        return state == null && condition(action)
    }
    
    override suspend fun handle(
        action: Action, 
        state: TSagaState?, 
        context: SagaContext<TSagaState>
    ): SagaTransition<TSagaState> {
        return context.handler(action)
    }
}

class TypedActionHandler<TSagaState, T : Action>(
    private val actionClass: KClass<T>,
    private val condition: (T, TSagaState) -> Boolean,
    private val handler: suspend SagaContext<TSagaState>.(T, TSagaState) -> SagaTransition<TSagaState>
) : SagaHandler<TSagaState>() {
    override suspend fun canHandle(action: Action, state: TSagaState?): Boolean {
        @Suppress("UNCHECKED_CAST")
        return state != null && actionClass.isInstance(action) && condition(action as T, state)
    }
    
    override suspend fun handle(
        action: Action, 
        state: TSagaState?, 
        context: SagaContext<TSagaState>
    ): SagaTransition<TSagaState> {
        @Suppress("UNCHECKED_CAST")
        return context.handler(action as T, state!!)
    }
}

class PredicateActionHandler<TSagaState>(
    private val condition: (Action, TSagaState) -> Boolean,
    private val handler: suspend SagaContext<TSagaState>.(Action, TSagaState) -> SagaTransition<TSagaState>
) : SagaHandler<TSagaState>() {
    override suspend fun canHandle(action: Action, state: TSagaState?): Boolean {
        return state != null && condition(action, state)
    }
    
    override suspend fun handle(
        action: Action, 
        state: TSagaState?, 
        context: SagaContext<TSagaState>
    ): SagaTransition<TSagaState> {
        return context.handler(action, state!!)
    }
}

/**
 * Configuration builder for defining saga behavior.
 */
class SagaConfiguration<TSagaState> {
    private val handlers = mutableListOf<SagaHandler<TSagaState>>()
    
    /**
     * Register a handler for starting the saga when a specific action type is dispatched.
     */
    inline fun <reified T : Action> startsOn(
        noinline condition: (T) -> Boolean = { true },
        noinline handler: suspend SagaContext<TSagaState>.(T) -> SagaTransition<TSagaState>
    ) {
        addHandler(TypedStartHandler(T::class, condition, handler))
    }
    
    /**
     * Register a handler for starting the saga when a condition is met.
     */
    fun startsWhen(
        condition: (Action) -> Boolean,
        handler: suspend SagaContext<TSagaState>.(Action) -> SagaTransition<TSagaState>
    ) {
        addHandler(PredicateStartHandler(condition, handler))
    }
    
    /**
     * Register a handler for active saga instances when a specific action type is dispatched.
     */
    inline fun <reified T : Action> on(
        noinline condition: (T, TSagaState) -> Boolean = { _, _ -> true },
        noinline handler: suspend SagaContext<TSagaState>.(T, TSagaState) -> SagaTransition<TSagaState>
    ) {
        addHandler(TypedActionHandler(T::class, condition, handler))
    }
    
    /**
     * Register a handler for active saga instances when a condition is met.
     */
    fun `when`(
        condition: (Action, TSagaState) -> Boolean,
        handler: suspend SagaContext<TSagaState>.(Action, TSagaState) -> SagaTransition<TSagaState>
    ) {
        addHandler(PredicateActionHandler(condition, handler))
    }
    
    fun addHandler(handler: SagaHandler<TSagaState>) {
        handlers.add(handler)
    }
    
    internal fun getHandlers(): List<SagaHandler<TSagaState>> = handlers.toList()
}

/**
 * Registry for managing saga definitions.
 */
class SagaRegistry<TStoreState : StateModel> {
    internal val sagas = mutableMapOf<String, ConfiguredSaga<*>>()
    
    /**
     * Register a class-based saga definition.
     */
    fun <TSagaState : Any> register(saga: SagaDefinition<TSagaState>) {
        val configuration = SagaConfiguration<TSagaState>()
        saga.configure(configuration)
        sagas[saga.name] = ConfiguredSaga(
            name = saga.name,
            configuration = configuration
        )
    }
    
    /**
     * Define and register a saga inline.
     */
    fun <TSagaState : Any> saga(
        name: String,
        initialState: () -> TSagaState,
        block: SagaConfiguration<TSagaState>.() -> Unit
    ) {
        val configuration = SagaConfiguration<TSagaState>()
        configuration.apply(block)
        sagas[name] = ConfiguredSaga(
            name = name,
            configuration = configuration,
            initialStateFactory = initialState
        )
    }
}

/**
 * Internal representation of a configured saga.
 */
internal data class ConfiguredSaga<TSagaState>(
    val name: String,
    val configuration: SagaConfiguration<TSagaState>,
    val initialStateFactory: (() -> TSagaState)? = null
)

