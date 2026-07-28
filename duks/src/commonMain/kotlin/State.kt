package duks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * Maps a [StateFlow] of [StateModel] to a cold [Flow] of selected props, emitting only when
 * the selected value changes according to [Any.equals].
 *
 * Prefer data classes (or other equality-friendly types) for [TProps] so that unchanged
 * slices do not produce notifications. This is the non-Compose selection contract shared
 * with [mapToPropsAsState].
 *
 * @param selector Extracts the desired slice of state. Should be pure and free of side effects.
 * @return A flow that emits the selected props when they change.
 */
fun <TState : StateModel, TProps> StateFlow<TState>.mapToProps(
    selector: TState.() -> TProps
): Flow<TProps> = map { state -> state.selector() }.distinctUntilChanged()

/**
 * Maps a specific slice of state from a [StateModel] for use in composable functions.
 *
 * Collects the upstream [StateFlow] once and derives props via [derivedStateOf], so Compose
 * only invalidates readers when the selected [TProps] value changes ([Any.equals]), not when
 * unrelated parts of the store state change.
 *
 * Prefer data classes (or other equality-friendly types) for [TProps].
 *
 * @param selector A function that extracts the desired slice of state from the current state.
 *                 This function cannot contain Composable calls as indicated by [DisallowComposableCalls].
 * @return A [State] containing the selected slice, updated when that slice changes.
 */
@Composable
fun <TState : StateModel, TProps> StateFlow<TState>.mapToPropsAsState(
    selector: @DisallowComposableCalls TState.() -> TProps
): State<TProps> {
    val fullState = collectAsState()
    val currentSelector = rememberUpdatedState(selector)
    return remember {
        derivedStateOf {
            fullState.value.run(currentSelector.value)
        }
    }
}
