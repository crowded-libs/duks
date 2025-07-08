package duks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

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
interface StateModel

interface StateAccessor {
    fun <TState:StateModel> getState(): TState
}

/**
 * Maps a specific slice of state from a [StateModel] for use in composable functions.
 *
 * This function allows extracting and transforming a portion of the state from a StateFlow
 * to prevent unnecessary recompositions when other parts of the state change. It uses
 * `map` to transform the StateFlow and `collectAsState` to convert it to a State object,
 * with an initial value taken from the current state.
 *
 * @param selector A function that extracts the desired slice of state from the current state.
 *                 This function cannot contain Composable calls as indicated by [DisallowComposableCalls].
 * @return A State object containing the selected slice of state, which will be updated when the source StateFlow changes.
 */
@Composable
inline fun <TState:StateModel, TProps> StateFlow<TState>.mapToPropsAsState(crossinline selector: @DisallowComposableCalls TState.() -> TProps) : State<TProps> {
    val slice = this.value.selector()
    return this.map { item -> item.selector() }.collectAsState(slice)
}