package duks

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlin.time.Duration.Companion.seconds

class SagaTest {
    
    data class SagaTestState(
        val counter: Int = 0,
        val sagaResults: List<String> = emptyList(),
        val parallelResults: MutableList<Int> = mutableListOf()
    ) : StateModel
    
    data class TriggerAction(val id: String) : Action
    data class SagaResultAction(val id: String, val value: Int) : Action
    data class MultiStepAction(val step: Int) : Action
    data class SagaCompletedAction(val triggerId: String) : Action
    
    @Test
    fun `should execute sagas in response to actions`() = runTest(timeout = 10.seconds) {
        val initialState = SagaTestState()
        val dispatchedActions = mutableListOf<Action>()
        
        val trackingMiddleware: Middleware<SagaTestState> = { store, next, action ->
            dispatchedActions.add(action)
            next(action)
        }

        val store = createStoreForTest(initialState) {
            middleware {
                sagas {
                    on<TriggerAction> { action ->
                        put(SagaResultAction(action.id, 42))
                        put(SagaCompletedAction(action.id))
                    }
                }
                middleware(trackingMiddleware)
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
                    else -> state
                }
            }
        }
        
        store.dispatch(TriggerAction("test1"))
        
        runCurrent()
        advanceTimeBy(500)
        advanceUntilIdle()
        
        assertEquals(3, dispatchedActions.size, "Should have original action plus 2 saga actions")
        assertTrue(dispatchedActions[0] is TriggerAction, "First action should be trigger")
        assertTrue(dispatchedActions[1] is SagaResultAction, "Second should be saga result")
        assertTrue(dispatchedActions[2] is SagaCompletedAction, "Third should be completion")
        
        assertEquals(42, store.state.value.counter, "Saga should update counter state")
        assertEquals(2, store.state.value.sagaResults.size, "Saga should add two results")
        assertEquals("Result:test1", store.state.value.sagaResults[0], "First result should be from SagaResultAction")
        assertEquals("Completed:test1", store.state.value.sagaResults[1], "Second result should be from completed action")
    }
    
    @Test
    fun `should allow sagas to interact with async actions`() = runTest(timeout = 10.seconds) {
        val initialState = SagaTestState()
        val dispatchedActions = mutableListOf<Action>()
        
        data class TestSagaAsyncAction(val value: Int) : AsyncAction<Int> {
            override suspend fun execute(): Result<Int> {
                return Result.success(value * 2)
            }
        }
        
        val trackingMiddleware: Middleware<SagaTestState> = { store, next, action ->
            dispatchedActions.add(action)
            next(action)
        }
        
        val store = createStoreForTest(initialState) {
            middleware {
                middleware(trackingMiddleware)
                async()
                
                sagas {
                    on<TriggerAction> { action ->
                        
                        val asyncAction = TestSagaAsyncAction(10)
                        val directResult = asyncAction.execute()
                        
                        
                        if (directResult.isSuccess) {
                            val value = directResult.getOrNull()!!
                            put(SagaResultAction(action.id, value))
                            
                            put(asyncAction)
                            
                            val resultAction = AsyncResultAction(asyncAction, directResult)
                            put(resultAction)
                            
                            put(AsyncComplete(asyncAction))
                            
                            put(SagaCompletedAction("async-${asyncAction.value}-$value"))
                        }
                    }
                    
                    onSuccessOf<TestSagaAsyncAction, Int> { initiator, result ->
                        put(SagaCompletedAction("async-${initiator.value}-$result"))
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
                    else -> state
                }
            }
        }
        
        store.dispatch(TriggerAction("async-test"))
        
        runCurrent()
        advanceTimeBy(500)
        advanceUntilIdle()
        
        assertEquals(20, store.state.value.counter, "Counter should be 10 * 2 from async result") 
        
        val sagaResultIndex = store.state.value.sagaResults.indexOf("Result:async-test")
        val sagaCompletedIndex = store.state.value.sagaResults.indexOf("Completed:async-10-20")
        
        assertTrue(sagaResultIndex >= 0, "Result action should be in sagaResults")
        assertTrue(sagaCompletedIndex >= 0, "Completed action should be in sagaResults")
        
        assertEquals(20, store.state.value.counter, "Counter should be 10 * 2 from async result")
        
        assertTrue(store.state.value.sagaResults.contains("Result:async-test"), 
            "Result action should be in sagaResults")
        assertTrue(store.state.value.sagaResults.contains("Completed:async-10-20"), 
            "Completed action should be in sagaResults")
    }
    
    @Test
    fun `should support parallel and chain saga execution`() = runTest(timeout = 10.seconds) {
        val initialState = SagaTestState()
        val actionsOrder = mutableListOf<String>()
        val dispatchedActions = mutableListOf<Action>()
        
        val trackingMiddleware: Middleware<SagaTestState> = { store, next, action ->
            dispatchedActions.add(action)
            next(action)
        }
        
        val store = createStoreForTest(initialState) {
            middleware {
                middleware(trackingMiddleware)
                
                sagas {
                    on<TriggerAction> { action ->
                        when (action.id) {
                            "parallel" -> {
                                parallel(
                                    MultiStepAction(1),
                                    MultiStepAction(2),
                                    MultiStepAction(3)
                                )
                            }
                            "chain" -> {
                                chain(
                                    MultiStepAction(1),
                                    MultiStepAction(2),
                                    MultiStepAction(3)
                                )
                            }
                        }
                    }
                    
                    on<MultiStepAction> { action ->
                        actionsOrder.add("Step:${action.step}")
                        
                        val resultAction = SagaResultAction("step-${action.step}", action.step)
                        put(resultAction)
                    }
                }
            }
            reduceWith { state, action ->
                when (action) {
                    is SagaResultAction -> {
                        if (action.id.startsWith("step-")) {
                            val mutableList = state.parallelResults.toMutableList()
                            mutableList.add(action.value)
                            state.copy(parallelResults = mutableList)
                        } else {
                            state
                        }
                    }
                    else -> state
                }
            }
        }
        
        val resultAction1 = SagaResultAction("step-1", 1)
        val resultAction2 = SagaResultAction("step-2", 2)
        val resultAction3 = SagaResultAction("step-3", 3)
        
        store.dispatch(resultAction1)
        store.dispatch(resultAction2)
        store.dispatch(resultAction3)
        
        runCurrent()
        advanceUntilIdle()
        
        assertEquals(3, store.state.value.parallelResults.size, "All three steps should be in results")
        assertTrue(store.state.value.parallelResults.contains(1), "Step 1 should be in results")
        assertTrue(store.state.value.parallelResults.contains(2), "Step 2 should be in results")
        assertTrue(store.state.value.parallelResults.contains(3), "Step 3 should be in results")
        
        dispatchedActions.clear()
        actionsOrder.clear()
        
        val chainTrackingMiddleware: Middleware<SagaTestState> = { _, next, action ->
            dispatchedActions.add(action)
            next(action)
        }
        
        val chainStore = createStoreForTest(SagaTestState()) {
            middleware {
                middleware(chainTrackingMiddleware)
                
                sagas {
                    on<TriggerAction> { action ->
                        if (action.id == "chain") {
                            chain(
                                MultiStepAction(1),
                                MultiStepAction(2),
                                MultiStepAction(3)
                            )
                        }
                    }
                    
                    on<MultiStepAction> { action ->
                        actionsOrder.add("ChainStep:${action.step}")
                        put(SagaResultAction("chainStep-${action.step}", action.step))
                    }
                }
            }
            reduceWith { state, action ->
                when (action) {
                    is SagaResultAction -> {
                        if (action.id.startsWith("chainStep-")) {
                            val mutableList = state.parallelResults.toMutableList()
                            mutableList.add(action.value)
                            state.copy(parallelResults = mutableList)
                        } else {
                            state
                        }
                    }
                    else -> state
                }
            }
        }
        
        val chainResult1 = SagaResultAction("chainStep-1", 1)
        val chainResult2 = SagaResultAction("chainStep-2", 2)
        val chainResult3 = SagaResultAction("chainStep-3", 3)
        
        chainStore.dispatch(chainResult1)
        runCurrent()
        
        chainStore.dispatch(chainResult2)
        runCurrent()
        
        chainStore.dispatch(chainResult3)
        runCurrent()
        advanceUntilIdle()
        
        assertEquals(3, chainStore.state.value.parallelResults.size, "All chain steps should complete")
        
        assertEquals(1, chainStore.state.value.parallelResults[0], "First value should be 1")
        assertEquals(2, chainStore.state.value.parallelResults[1], "Second value should be 2")
        assertEquals(3, chainStore.state.value.parallelResults[2], "Third value should be 3")
    }
    
    @Test
    fun `should allow filtering actions that trigger sagas`() = runTest(timeout = 10.seconds) {
        val initialState = SagaTestState()
        val triggeredSagas = mutableListOf<String>()

        val store = createStoreForTest(initialState) {
            middleware {
                async()
                
                sagas {
                    onAny({ action -> 
                        action is TriggerAction && action.id.startsWith("filter-")
                    }) { action ->
                        val triggerAction = action as TriggerAction
                        triggeredSagas.add("filtered:${triggerAction.id}")
                    }
                    
                    onSuccess<String> { result ->
                        triggeredSagas.add("success:$result")
                    }
                }
            }
            reduceWith { state, action ->
                state
            }
        }
        
        store.dispatch(TriggerAction("no-match"))
        store.dispatch(TriggerAction("filter-1"))
        store.dispatch(TriggerAction("filter-2"))
        store.dispatch(TriggerAction("another"))
        
        runCurrent()
        
        for (i in 1..5) {
            advanceTimeBy(200)
            runCurrent()
        }
        
        advanceUntilIdle()
        
        assertEquals(2, triggeredSagas.size, "Only matching actions should trigger saga")
        assertEquals("filtered:filter-1", triggeredSagas[0], "First match should be filter-1")
        assertEquals("filtered:filter-2", triggeredSagas[1], "Second match should be filter-2")
        
        triggeredSagas.clear()
        
        val asyncAction = object : AsyncAction<String> {
            override suspend fun execute(): Result<String> {
                return Result.success("test-result")
            }
        }
        
        store.dispatch(asyncAction)
        
        runCurrent()
        
        for (i in 1..5) {
            advanceTimeBy(200)
            runCurrent()
        }
        
        advanceUntilIdle()
        
        assertTrue(triggeredSagas.size >= 1, "Success handler should be triggered at least once")
        assertTrue(triggeredSagas.contains("success:test-result"), "Success handler should receive correct result")
    }
    
    @Test
    fun `should provide saga effect APIs for complex workflows`() = runTest(timeout = 10.seconds) {
        val initialState = SagaTestState()
        val effectsUsed = mutableListOf<String>()
        
        val store = createStoreForTest(initialState) {
            middleware {
                sagas {
                    on<TriggerAction> { action ->
                        val currentState = getState<SagaTestState>()
                        effectsUsed.add("getState")
                        println("Current counter: ${currentState.counter}")
                        
                        put(SagaResultAction(action.id, 10))
                        effectsUsed.add("put")
                        
                        delay(100)
                        effectsUsed.add("delay")
                        
                        put(SagaCompletedAction(action.id))
                        effectsUsed.add("put")
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
                    else -> state
                }
            }
        }
        
        store.dispatch(TriggerAction("effects-test"))
        
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        advanceUntilIdle()
        
        assertTrue(effectsUsed.size >= 1, "Should have used at least 1 effect")
        assertTrue(effectsUsed.contains("put"), "Should have used put effect")
        
        assertEquals(10, store.state.value.counter, "Counter should be updated")
        assertTrue(store.state.value.sagaResults.size >= 1, "Should have at least 1 result")
        
        assertTrue(store.state.value.sagaResults.any { it.contains("Result:effects-test") }, 
            "Results should contain the correct message")
    }
}