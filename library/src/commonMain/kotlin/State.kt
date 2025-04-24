package duks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.State
import androidx.compose.runtime.remember

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

interface StateAccessor {
    fun <TState:StateModel> getState(): TState
}

/**
 * Maps a specific slice of state from a [StateModel] for use in composable functions.
 *
 * This function allows extracting and memoizing a portion of the state to prevent
 * unnecessary recompositions when other parts of the state change. It uses [remember]
 * to memoize the selected slice or props of state and only triggers recomposition when the
 * selected data actually changes.
 *
 * @param selector A function that extracts the desired slice of state from the current state.
 *                 This function cannot contain Composable calls as indicated by [DisallowComposableCalls].
 * @return The selected slice of state, memoized based on both the full state and the selected slice.
 */
@Composable
inline fun <TState:StateModel, Props> State<TState>.mapToProps(crossinline selector: @DisallowComposableCalls TState.() -> Props) : Props {
    val slice = this.value.selector()
    val result = remember(this.value, slice) { slice }
    return result
}