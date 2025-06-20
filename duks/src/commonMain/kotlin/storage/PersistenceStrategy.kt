package duks.storage

import duks.Action
import duks.StateModel
import kotlin.reflect.KClass

/**
 * Defines various strategies for when state should be persisted
 */
sealed class PersistenceStrategy {
    /**
     * Persist on every state change
     */
    object OnEveryChange : PersistenceStrategy()
    
    /**
     * Persist after a delay with no further changes
     * @param delayMs The delay in milliseconds before persisting
     */
    data class Debounced(val delayMs: Long) : PersistenceStrategy()
    
    /**
     * Persist only when specific actions are dispatched
     * @param actionTypes The set of action types that trigger persistence
     */
    data class OnAction(val actionTypes: Set<KClass<out Action>>) : PersistenceStrategy()
    
    /**
     * Persist based on state comparison
     * @param shouldPersist Function to determine if state should be persisted
     */
    data class Conditional(
        val shouldPersist: (currentState: StateModel, previousState: StateModel?) -> Boolean
    ) : PersistenceStrategy()
    
    /**
     * Combine multiple strategies - persistence occurs if any strategy triggers
     * @param strategies The list of strategies to combine
     */
    data class Combined(val strategies: List<PersistenceStrategy>) : PersistenceStrategy()
}