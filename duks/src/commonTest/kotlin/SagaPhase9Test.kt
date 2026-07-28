package duks

import duks.storage.InMemorySagaStorage
import duks.storage.SagaPersistenceStrategy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SagaPhase9Test {

    data class AppState(
        val messages: List<String> = emptyList(),
        val done: Boolean = false
    ) : StateModel

    data class StartLoad(val id: String) : Action
    data class StepDone(val id: String, val step: String) : Action
    data class StepFailed(val id: String, val step: String, val message: String) : Action
    data class Abort(val id: String) : Action
    data class AllDone(val id: String) : Action
    data class FailedMsg(val message: String) : Action
    data class ParallelA(val n: Int) : Action
    data class ParallelB(val n: Int) : Action
    data object StartParallel : Action

    enum class LoadStep { A, B, C }

    data class LoadState(
        val id: String,
        val pending: Set<LoadStep> = LoadStep.entries.toSet(),
        val completed: Set<LoadStep> = emptySet(),
        val errors: List<String> = emptyList()
    )

    @Test
    fun `Fail removes instance and runs effects`() = runTest(timeout = 5.seconds) {
        val storage = InMemorySagaStorage()
        val store = createStoreForTest(AppState()) {
            middleware {
                sagas(storage = storage, persistenceStrategy = SagaPersistenceStrategy.OnEveryChange) {
                    saga<LoadState>(name = "fail-saga") {
                        startsOn<StartLoad> { action ->
                            SagaTransition.Continue(LoadState(id = action.id))
                        }
                        on<Abort>({ action, state -> action.id == state.id }) { _, _ ->
                            SagaTransition.Fail(
                                error = RuntimeException("aborted"),
                                effects = listOf(SagaEffect.Dispatch(FailedMsg("aborted")))
                            )
                        }
                        on<StepDone>({ action, state -> action.id == state.id }) { _, state ->
                            SagaTransition.Continue(state)
                        }
                    }
                }
            }
            reduceWith { state, action ->
                when (action) {
                    is FailedMsg -> state.copy(messages = state.messages + action.message)
                    else -> state
                }
            }
        }

        dispatchAndAdvance(store, StartLoad("x"))
        assertTrue(storage.getAllSagaIds().isNotEmpty())

        dispatchAndAdvance(store, Abort("x"))
        awaitState(store.state) { it.messages.contains("aborted") }
        assertTrue(storage.getAllSagaIds().isEmpty())

        // Further steps must not resurrect the saga
        dispatchAndAdvance(store, StepDone("x", "A"))
        assertTrue(storage.getAllSagaIds().isEmpty())
    }

    @Test
    fun `saga ids are monotonic and unique under burst`() = runTest(timeout = 5.seconds) {
        val seen = mutableListOf<String>()
        val store = createStoreForTest(AppState()) {
            middleware {
                sagas {
                    saga<LoadState>(name = "id-saga") {
                        startsOn<StartLoad> { action ->
                            seen.add(sagaId)
                            SagaTransition.Complete()
                        }
                    }
                }
            }
            reduceWith { state, _ -> state }
        }

        repeat(20) { i ->
            dispatchAndAdvance(store, StartLoad("b$i"))
        }

        assertEquals(20, seen.size)
        assertEquals(seen.toSet().size, seen.size, "IDs must be unique")
        assertTrue(seen.all { it.startsWith("id-saga-") })
        val numbers = seen.map { it.removePrefix("id-saga-").toLong() }
        assertEquals(numbers.sorted(), numbers, "IDs should be monotonic for single-threaded starts")
    }

    @Test
    fun `close cancels saga processing and clears instances`() = runTest(timeout = 5.seconds) {
        val storage = InMemorySagaStorage()
        val store = createStoreForTest(AppState()) {
            middleware {
                sagas(storage = storage, persistenceStrategy = SagaPersistenceStrategy.OnEveryChange) {
                    saga<LoadState>(name = "close-saga") {
                        startsOn<StartLoad> { action ->
                            SagaTransition.Continue(LoadState(id = action.id))
                        }
                    }
                }
            }
            reduceWith { state, _ -> state }
        }

        dispatchAndAdvance(store, StartLoad("keep"))
        assertEquals(1, storage.getAllSagaIds().size)

        store.close()
        runCurrent()
        advanceUntilIdle()

        // Runtime instances cleared; further dispatch is ignored by the closed store.
        store.dispatch(StartLoad("after-close"))
        runCurrent()
        advanceUntilIdle()
        assertTrue(store.isClosed)
        assertEquals(1, storage.getAllSagaIds().size, "close does not wipe durable storage by default")
    }

    @Test
    fun `afterFanInStep completes when all steps done`() = runTest(timeout = 5.seconds) {
        val store = createStoreForTest(AppState()) {
            middleware {
                sagas {
                    saga<LoadState>(name = "fan-in") {
                        startsOn<StartLoad> { action ->
                            SagaTransition.Continue(
                                LoadState(
                                    id = action.id,
                                    pending = LoadStep.entries.toSet(),
                                    completed = emptySet()
                                )
                            )
                        }
                        on<StepDone>({ a, s -> a.id == s.id }) { action, state ->
                            afterFanInStep(
                                allSteps = state.pending,
                                completedSteps = state.completed,
                                step = LoadStep.valueOf(action.step),
                                updateState = { done -> state.copy(completed = done) },
                                onAllComplete = {
                                    dispatch(AllDone(state.id))
                                    SagaTransition.Complete()
                                }
                            )
                        }
                    }
                }
            }
            reduceWith { state, action ->
                when (action) {
                    is AllDone -> state.copy(done = true, messages = state.messages + action.id)
                    else -> state
                }
            }
        }

        dispatchAndAdvance(store, StartLoad("job1"))
        dispatchAndAdvance(store, StepDone("job1", "A"))
        assertFalse(store.state.value.done)

        dispatchAndAdvance(store, StepDone("job1", "B"))
        assertFalse(store.state.value.done)

        dispatchAndAdvance(store, StepDone("job1", "C"))
        awaitState(store.state) { it.done }
        assertEquals(listOf("job1"), store.state.value.messages)
    }

    @Test
    fun `afterFanInStepError still finishes fan-in`() = runTest(timeout = 5.seconds) {
        val store = createStoreForTest(AppState()) {
            middleware {
                sagas {
                    saga<LoadState>(name = "fan-in-err") {
                        startsOn<StartLoad> { action ->
                            SagaTransition.Continue(LoadState(id = action.id))
                        }
                        on<StepDone>({ a, s -> a.id == s.id }) { action, state ->
                            afterFanInStep(
                                allSteps = LoadStep.entries.toSet(),
                                completedSteps = state.completed,
                                step = LoadStep.valueOf(action.step),
                                updateState = { done -> state.copy(completed = done) },
                                onAllComplete = {
                                    dispatch(AllDone(state.id))
                                    SagaTransition.Complete()
                                }
                            )
                        }
                        on<StepFailed>({ a, s -> a.id == s.id }) { action, state ->
                            afterFanInStepError(
                                allSteps = LoadStep.entries.toSet(),
                                completedSteps = state.completed,
                                step = LoadStep.valueOf(action.step),
                                error = RuntimeException(action.message),
                                updateStateWithError = { done, err ->
                                    state.copy(
                                        completed = done,
                                        errors = state.errors + (err.message ?: "err")
                                    )
                                },
                                onAllComplete = {
                                    dispatch(AllDone(state.id))
                                    SagaTransition.Complete()
                                }
                            )
                        }
                    }
                }
            }
            reduceWith { state, action ->
                when (action) {
                    is AllDone -> state.copy(done = true)
                    else -> state
                }
            }
        }

        dispatchAndAdvance(store, StartLoad("j2"))
        dispatchAndAdvance(store, StepDone("j2", "A"))
        dispatchAndAdvance(store, StepFailed("j2", "B", "boom"))
        dispatchAndAdvance(store, StepDone("j2", "C"))
        awaitState(store.state) { it.done }
    }

    @Test
    fun `Parallel effect runs child effects`() = runTest(timeout = 5.seconds) {
        val store = createStoreForTest(AppState()) {
            middleware {
                sagas {
                    saga<LoadState>(name = "parallel") {
                        startsOn<StartParallel> {
                            SagaTransition.Complete(
                                effects = listOf(
                                    SagaEffect.Parallel(
                                        listOf(
                                            SagaEffect.Dispatch(ParallelA(1)),
                                            SagaEffect.Dispatch(ParallelB(2))
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }
            reduceWith { state, action ->
                when (action) {
                    is ParallelA -> state.copy(messages = state.messages + "A${action.n}")
                    is ParallelB -> state.copy(messages = state.messages + "B${action.n}")
                    else -> state
                }
            }
        }

        dispatchAndAdvance(store, StartParallel)
        awaitUntil {
            store.state.value.messages.contains("A1") && store.state.value.messages.contains("B2")
        }
    }

    @Test
    fun `fanInProgress pure helper reports completion`() {
        val p1 = fanInProgress(setOf(1, 2, 3), emptySet(), 1)
        assertEquals(setOf(1), p1.completed)
        assertFalse(p1.isComplete)

        val p2 = fanInProgress(setOf(1, 2, 3), setOf(1, 2), 3)
        assertTrue(p2.isComplete)
        assertEquals(setOf(1, 2, 3), p2.completed)
    }
}
