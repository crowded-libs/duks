package duks

/**
 * Base interface for state models in the Duks state management system.
 *
 * This marker interface is used to represent the state shape of an application.
 * Implementing classes should typically be immutable data classes that hold
 * the complete state of the application or a specific feature.
 *
 * State objects should be treated as immutable, and all changes should be made
 * by creating new instances through reducers in response to actions.
 */
interface StateModel {}