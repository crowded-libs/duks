package duks

import kotlin.test.*
import kotlinx.coroutines.test.*
import kotlin.time.Duration.Companion.seconds

class SagaTest {
    
    data class SagaTestState(
        val counter: Int = 0,
        val sagaResults: List<String> = emptyList(),
        val parallelResults: MutableList<Int> = mutableListOf()
    ) : StateModel
    
    // Test saga states
    data class CounterSagaState(
        val triggerId: String,
        val currentValue: Int = 0,
        val operations: List<String> = emptyList()
    )
    
    data class MultiStepSagaState(
        val startAction: String,
        val completedSteps: List<Int> = emptyList(),
        val isParallel: Boolean = false
    )
    
    // Test actions
    data class TriggerAction(val id: String) : Action
    data class SagaResultAction(val id: String, val value: Int) : Action
    data class MultiStepAction(val step: Int) : Action
    data class SagaCompletedAction(val triggerId: String) : Action
    data class IncrementSagaCounter(val sagaId: String, val amount: Int) : Action
    data class CompleteSaga(val sagaId: String) : Action
    
    @Test
    fun `should execute sagas in response to actions`() = runTest(timeout = 10.seconds) {
        val initialState = SagaTestState()
        val dispatchedActions = mutableListOf<Action>()
        
        val store = createStoreForTest(initialState) {
            middleware {
                middleware { store, next, action ->
                    dispatchedActions.add(action)
                    next(action)
                }
                
                sagas {
                    saga<CounterSagaState>(
                        name = "CounterSaga",
                        initialState = { CounterSagaState("") }
                    ) {
                        startsOn<TriggerAction> { action ->
                            SagaTransition.Continue(
                                CounterSagaState(
                                    triggerId = action.id,
                                    currentValue = 0,
                                    operations = listOf("started")
                                ),
                                effects = listOf(
                                    SagaEffect.Dispatch(SagaResultAction(action.id, 42))
                                )
                            )
                        }
                        
                        on<SagaResultAction>(
                            condition = { action, state -> action.id == state.triggerId }
                        ) { action, state ->
                            SagaTransition.Continue(
                                state.copy(
                                    currentValue = state.currentValue + action.value,
                                    operations = state.operations + "added:${action.value}"
                                ),
                                effects = listOf(
                                    SagaEffect.Dispatch(SagaCompletedAction(state.triggerId))
                                )
                            )
                        }
                        
                        on<SagaCompletedAction>(
                            condition = { action, state -> action.triggerId == state.triggerId }
                        ) { action, state ->
                            SagaTransition.Complete(
                                effects = listOf(
                                    SagaEffect.Dispatch(AddMessageAction("Saga completed for ${state.triggerId}"))
                                )
                            )
                        }
                    }
                }
            }
            
            reduceWith { state, action ->
                when (action) {
                    is SagaResultAction -> state.copy(
                        counter = state.counter + action.value,
                        sagaResults = state.sagaResults + "Result:${action.id}"
                    )
                    is SagaCompletedAction -> state.copy(
                        sagaResults = state.sagaResults + "Completed:${action.triggerId}"
                    )
                    is AddMessageAction -> state.copy(
                        sagaResults = state.sagaResults + action.message
                    )
                    else -> state
                }
            }
        }

        dispatchAndAdvance(store, TriggerAction("test1"))

        assertTrue(dispatchedActions.isNotEmpty(), "Should have processed actions")
        
        val counter = store.state.value.counter
        val sagaResults = store.state.value.sagaResults
        
        assertEquals(42, counter, "Saga should update counter state")
        assertTrue(sagaResults.isNotEmpty(), "Should have saga results")
        
        assertTrue(sagaResults.contains("Result:test1"), "Should have result action")
        assertTrue(sagaResults.contains("Completed:test1"), "Should have completed action")
    }
    
    @Test
    fun `should support conditional saga triggers`() = runTest(timeout = 10.seconds) {
        val initialState = SagaTestState()
        val triggeredSagas = mutableListOf<String>()

        val store = createStoreForTest(initialState) {
            middleware {
                sagas {
                    saga<CounterSagaState>(
                        name = "FilteredSaga",
                        initialState = { CounterSagaState("") }
                    ) {
                        startsOn<TriggerAction>(
                            condition = { it.id.startsWith("filter-") }
                        ) { action ->
                            triggeredSagas.add("filtered:${action.id}")
                            SagaTransition.Continue(
                                CounterSagaState(triggerId = action.id)
                            )
                        }
                        
                        startsWhen(
                            condition = { action -> 
                                action is TriggerAction && action.id.contains("special")
                            }
                        ) { action ->
                            val trigger = action as TriggerAction
                            triggeredSagas.add("special:${trigger.id}")
                            SagaTransition.Continue(
                                CounterSagaState(triggerId = trigger.id)
                            )
                        }
                    }
                }
            }
            
            reduceWith { state, _ -> state }
        }
        
        dispatchAndAdvance(store, TriggerAction("no-match"))
        dispatchAndAdvance(store, TriggerAction("filter-1"))
        dispatchAndAdvance(store, TriggerAction("filter-2"))
        dispatchAndAdvance(store, TriggerAction("special-event"))
        dispatchAndAdvance(store, TriggerAction("another"))
        
        assertEquals(3, triggeredSagas.size, "Should trigger matching sagas only")
        assertTrue(triggeredSagas.contains("filtered:filter-1"), "Should match filter-1")
        assertTrue(triggeredSagas.contains("filtered:filter-2"), "Should match filter-2")
        assertTrue(triggeredSagas.contains("special:special-event"), "Should match special event")
    }
    
    @Test
    fun `should manage saga state lifecycle`() = runTest(timeout = 10.seconds) {
        val initialState = SagaTestState()
        val sagaStateUpdates = mutableListOf<String>()
        
        val store = createStoreForTest(initialState) {
            middleware {
                sagas {
                    saga<CounterSagaState>(
                        name = "LifecycleSaga",
                        initialState = { CounterSagaState("") }
                    ) {
                        startsOn<TriggerAction> { action ->
                            sagaStateUpdates.add("start:${action.id}")
                            SagaTransition.Continue(
                                CounterSagaState(
                                    triggerId = action.id,
                                    currentValue = 0
                                )
                            )
                        }
                        
                        on<IncrementSagaCounter> { action, state ->
                            val newValue = state.currentValue + action.amount
                            sagaStateUpdates.add("increment:${state.triggerId}:$newValue")
                            SagaTransition.Continue(
                                state.copy(currentValue = newValue)
                            )
                        }
                        
                        on<CompleteSaga>(
                            condition = { action, state -> action.sagaId == state.triggerId }
                        ) { action, state ->
                            sagaStateUpdates.add("complete:${state.triggerId}:${state.currentValue}")
                            SagaTransition.Complete()
                        }
                    }
                }
            }
            
            reduceWith { state, _ -> state }
        }
        
        // Start saga
        dispatchAndAdvance(store, TriggerAction("lifecycle-1"))
        assertTrue(sagaStateUpdates.contains("start:lifecycle-1"))
        
        // Update saga state
        dispatchAndAdvance(store, IncrementSagaCounter("lifecycle-1", 10))
        assertTrue(sagaStateUpdates.contains("increment:lifecycle-1:10"))
        
        dispatchAndAdvance(store, IncrementSagaCounter("lifecycle-1", 5))
        assertTrue(sagaStateUpdates.contains("increment:lifecycle-1:15"))
        
        // Complete saga
        dispatchAndAdvance(store, CompleteSaga("lifecycle-1"))
        assertTrue(sagaStateUpdates.contains("complete:lifecycle-1:15"))
        
        // Verify saga no longer responds after completion
        val previousSize = sagaStateUpdates.size
        dispatchAndAdvance(store, IncrementSagaCounter("lifecycle-1", 100))
        assertEquals(previousSize, sagaStateUpdates.size, "Completed saga should not respond")
    }
    
    @Test
    fun `should support saga effects`() = runTest(timeout = 10.seconds) {
        val initialState = SagaTestState()
        val effectsExecuted = mutableListOf<String>()
        
        val store = createStoreForTest(initialState) {
            middleware {
                middleware { store, next, action ->
                    if (action is MultiStepAction) {
                        effectsExecuted.add("dispatch:step-${action.step}")
                    }
                    next(action)
                }
                
                sagas {
                    saga<MultiStepSagaState>(
                        name = "EffectsSaga",
                        initialState = { MultiStepSagaState("") }
                    ) {
                        startsOn<TriggerAction> { action ->
                            effectsExecuted.add("saga-start:${action.id}")
                            
                            val effects = when (action.id) {
                                "sequential" -> listOf(
                                    SagaEffect.Dispatch(MultiStepAction(1)),
                                    SagaEffect.Delay(50),
                                    SagaEffect.Dispatch(MultiStepAction(2)),
                                    SagaEffect.Delay(50),
                                    SagaEffect.Dispatch(MultiStepAction(3))
                                )
                                "parallel" -> listOf(
                                    SagaEffect.Dispatch(MultiStepAction(1)),
                                    SagaEffect.Dispatch(MultiStepAction(2)),
                                    SagaEffect.Dispatch(MultiStepAction(3))
                                )
                                else -> emptyList()
                            }
                            
                            SagaTransition.Continue(
                                MultiStepSagaState(
                                    startAction = action.id,
                                    isParallel = action.id == "parallel"
                                ),
                                effects = effects
                            )
                        }
                        
                        on<MultiStepAction> { action, state ->
                            SagaTransition.Continue(
                                state.copy(
                                    completedSteps = state.completedSteps + action.step
                                )
                            )
                        }
                    }
                }
            }
            
            reduceWith { state, action ->
                when (action) {
                    is MultiStepAction -> state.copy(
                        parallelResults = state.parallelResults.apply { add(action.step) }
                    )
                    else -> state
                }
            }
        }
        
        // Test sequential effects
        dispatchAndAdvance(store, TriggerAction("sequential"))
        
        assertTrue(effectsExecuted.contains("saga-start:sequential"))
        assertTrue(effectsExecuted.contains("dispatch:step-1"))
        assertTrue(effectsExecuted.contains("dispatch:step-2"))
        assertTrue(effectsExecuted.contains("dispatch:step-3"))
        
        // Test parallel effects
        effectsExecuted.clear()
        store.state.value.parallelResults.clear()
        
        dispatchAndAdvance(store, TriggerAction("parallel"))
        
        assertTrue(effectsExecuted.contains("saga-start:parallel"))
        assertEquals(3, effectsExecuted.count { it.startsWith("dispatch:step-") })
    }
    
    @Test
    fun `should support cross-saga communication`() = runTest(timeout = 10.seconds) {
        data class ParentSagaState(val childStarted: Boolean = false)
        data class ChildSagaState(val parentId: String)
        
        data class StartParent(val id: String) : Action
        data class StartChild(val parentId: String) : Action
        data class ChildCompleted(val parentId: String) : Action
        
        val sagaEvents = mutableListOf<String>()
        
        val store = createStoreForTest(SagaTestState()) {
            middleware {
                sagas {
                    saga<ParentSagaState>(
                        name = "ParentSaga",
                        initialState = { ParentSagaState() }
                    ) {
                        startsOn<StartParent> { action ->
                            sagaEvents.add("parent-started:${action.id}")
                            SagaTransition.Continue(
                                ParentSagaState(childStarted = false),
                                effects = listOf(
                                    SagaEffect.Dispatch(StartChild(action.id))
                                )
                            )
                        }
                        
                        on<ChildCompleted> { action, state ->
                            sagaEvents.add("parent-received-child-complete")
                            SagaTransition.Complete()
                        }
                    }
                    
                    saga<ChildSagaState>(
                        name = "ChildSaga", 
                        initialState = { ChildSagaState("") }
                    ) {
                        startsOn<StartChild> { action ->
                            sagaEvents.add("child-started:${action.parentId}")
                            SagaTransition.Continue(
                                ChildSagaState(parentId = action.parentId),
                                effects = listOf(
                                    SagaEffect.Delay(100),
                                    SagaEffect.Dispatch(ChildCompleted(action.parentId))
                                )
                            )
                        }
                    }
                }
            }
            
            reduceWith { state, _ -> state }
        }
        
        dispatchAndAdvance(store, StartParent("test-parent"))
        
        assertTrue(sagaEvents.contains("parent-started:test-parent"))
        assertTrue(sagaEvents.contains("child-started:test-parent"))
        assertTrue(sagaEvents.contains("parent-received-child-complete"))
    }
}

// Helper action for test
data class AddMessageAction(val message: String) : Action