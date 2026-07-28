package duks

/**
 * Marks [step] complete within a multi-step fan-in and returns the appropriate transition.
 *
 * Use this to avoid boilerplate like mixfit's multi-load sagas: track a fixed set of steps,
 * continue until all are done, then complete (or fail).
 *
 * Example:
 * ```kotlin
 * on<ProfilesLoaded> { _, state ->
 *     afterFanInStep(
 *         allSteps = LoadingStep.entries.toSet(),
 *         completedSteps = state.completed,
 *         step = LoadingStep.PROFILES,
 *         updateState = { done -> state.copy(completed = done) },
 *         onAllComplete = {
 *             dispatch(InitialDataLoaded)
 *             SagaTransition.Complete()
 *         }
 *     )
 * }
 * ```
 *
 * @param allSteps Full set of steps that must complete
 * @param completedSteps Steps already finished before this call
 * @param step The step that just finished
 * @param updateState Build continued saga state from the new completed set
 * @param onAllComplete Invoked when every step in [allSteps] is present in completed
 */
suspend fun <TSagaState, Step> SagaContext<TSagaState>.afterFanInStep(
    allSteps: Set<Step>,
    completedSteps: Set<Step>,
    step: Step,
    updateState: (newCompleted: Set<Step>) -> TSagaState,
    onAllComplete: suspend SagaContext<TSagaState>.(newCompleted: Set<Step>) -> SagaTransition<TSagaState>
): SagaTransition<TSagaState> {
    val newCompleted = completedSteps + step
    return if (allSteps.isNotEmpty() && allSteps.all { it in newCompleted }) {
        onAllComplete(newCompleted)
    } else {
        SagaTransition.Continue(updateState(newCompleted))
    }
}

/**
 * Like [afterFanInStep] but records [error] via [updateStateWithError] and still counts
 * [step] toward completion (so one failing step does not stall the fan-in forever).
 *
 * When all steps are accounted for, [onAllComplete] runs (inspect accumulated errors there
 * and return [SagaTransition.Fail] if desired).
 */
suspend fun <TSagaState, Step> SagaContext<TSagaState>.afterFanInStepError(
    allSteps: Set<Step>,
    completedSteps: Set<Step>,
    step: Step,
    error: Throwable,
    updateStateWithError: (newCompleted: Set<Step>, error: Throwable) -> TSagaState,
    onAllComplete: suspend SagaContext<TSagaState>.(newCompleted: Set<Step>) -> SagaTransition<TSagaState>
): SagaTransition<TSagaState> {
    val newCompleted = completedSteps + step
    return if (allSteps.isNotEmpty() && allSteps.all { it in newCompleted }) {
        onAllComplete(newCompleted)
    } else {
        SagaTransition.Continue(updateStateWithError(newCompleted, error))
    }
}

/**
 * Pure helper: add [step] to [completedSteps] and report whether [allSteps] are done.
 */
fun <Step> fanInProgress(
    allSteps: Set<Step>,
    completedSteps: Set<Step>,
    step: Step
): FanInProgress<Step> {
    val newCompleted = completedSteps + step
    return FanInProgress(
        completed = newCompleted,
        isComplete = allSteps.isNotEmpty() && allSteps.all { it in newCompleted }
    )
}

/**
 * Snapshot of fan-in progress after applying one step.
 */
data class FanInProgress<Step>(
    val completed: Set<Step>,
    val isComplete: Boolean
)
