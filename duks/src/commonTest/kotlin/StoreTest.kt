package duks

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class StoreTest {

    data class TestState(
        val counter: Int = 0, 
        val value: Int = 0,
        val messages: List<String> = emptyList()
    ) : StateModel

    data class IncrementAction(val value: Int = 1) : Action
    data class DecrementAction(val value: Int = 1) : Action
    data class AddMessageAction(val message: String) : Action
    class ErrorAction(val message: String = "Test error") : Action

    @Test
    fun `should create store with initial state`() = runTest {
        val store = createStoreForTest(TestState()) {
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    is DecrementAction -> state.copy(counter = state.counter - action.value)
                    else -> state
                }
            }
        }

        assertEquals(0, store.state.value.counter)
    }

    @Test
    fun `should apply reducer functions directly to state`() {
        val reducer: Reducer<TestState> = { state, action ->
            when (action) {
                is IncrementAction -> state.copy(counter = state.counter + action.value)
                is DecrementAction -> state.copy(counter = state.counter - action.value)
                else -> state
            }
        }

        val initialState = TestState()
        val state1 = reducer(initialState, IncrementAction(5))
        assertEquals(5, state1.counter)

        val state2 = reducer(state1, DecrementAction(3))
        assertEquals(2, state2.counter)
    }

    @Test
    fun `should update state when dispatching actions`() = runTest {
        val store = createStoreForTest(TestState()) {
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    is DecrementAction -> state.copy(counter = state.counter - action.value)
                    else -> state
                }
            }
        }

        dispatchAndAdvance(store, IncrementAction(5))

        assertEquals(5, store.state.value.counter)
    }

    @Test
    fun `should track actions processed by the store`() = runTest {
        val initialState = TestState()

        val (store, actionsProcessed) = createTrackedStoreForTest(initialState) {
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    is DecrementAction -> state.copy(counter = state.counter - action.value)
                    else -> state
                }
            }
        }

        dispatchAndAdvance(store, IncrementAction(5))

        assertEquals(1, actionsProcessed.size)
        assertTrue(actionsProcessed[0] is IncrementAction)
        assertEquals(5, store.state.value.counter)

        dispatchAndAdvance(store, DecrementAction(2))

        assertEquals(2, actionsProcessed.size)
        assertTrue(actionsProcessed[1] is DecrementAction)
        assertEquals(3, store.state.value.counter)
    }

    @Test
    fun `should execute middleware before and after actions`() = runTest {
        val initialState = TestState()
        val actionsProcessed = mutableListOf<String>()

        val store = createStoreForTest(initialState) {
            middleware {
                middleware { store, next, action ->
                    actionsProcessed.add("Before: ${action::class.simpleName}")
                    val result = next(action)
                    actionsProcessed.add("After: ${action::class.simpleName}")
                    result
                }
            }
            reduceWith { state, _ -> state }
        }

        dispatchAndAdvance(store, IncrementAction(5))

        assertEquals(2, actionsProcessed.size)
        assertEquals("Before: IncrementAction", actionsProcessed[0])
        assertEquals("After: IncrementAction", actionsProcessed[1])
    }

    @Test
    fun `should update multiple state properties correctly`() = runTest {
        val initialState = TestState()
        val store = createStoreForTest(initialState) {
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    is DecrementAction -> state.copy(counter = state.counter - action.value)
                    is AddMessageAction -> state.copy(messages = state.messages + action.message)
                    else -> state
                }
            }
        }

        store.dispatch(IncrementAction(5))
        dispatchAndAdvance(store, AddMessageAction("Hello"))

        assertEquals(5, store.state.value.counter)
        assertEquals(1, store.state.value.messages.size)
        assertEquals("Hello", store.state.value.messages[0])
    }

    @Test
    fun `should log actions with logger middleware`() = runTest {
        val testLogger = TestLogger()

        val store = createStoreForTest(TestState()) {
            middleware {
                middleware(loggerMiddleware<TestState>(testLogger))
            }
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    else -> state
                }
            }
        }

        dispatchAndAdvance(store, IncrementAction(3))

        assertEquals(2, testLogger.messages.size)
        assertTrue(testLogger.messages[0].contains("Action:"))
        assertTrue(testLogger.messages[1].contains("After Action:"))
    }

    @Test
    fun `should handle exceptions with exception middleware`() = runTest {
        val testLogger = TestLogger()

        val store = createStoreForTest(TestState()) {
            middleware {
                middleware(exceptionMiddleware<TestState>(testLogger))
            }
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    is ErrorAction -> throw RuntimeException(action.message)
                    else -> state
                }
            }
        }

        dispatchAndAdvance(store, IncrementAction(1))

        assertEquals(1, store.state.value.counter)

        dispatchAndAdvance(store, ErrorAction("Test error"))

        assertEquals(1, testLogger.messages.size)
        assertTrue(testLogger.messages[0].contains("Test error"))
    }

    @Test
    fun `should trigger saga-like side effects after actions`() = runTest {
        val (store, processedActions) = createTrackedStoreForTest(TestState()) {
            middleware {
                middleware({ store, next, action ->
                    val result = next(action)

                    if (action is IncrementAction) {
                        val message = AddMessageAction("Increment: ${action.value}")
                        store.dispatch(message)
                    }

                    result
                })
            }

            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    is AddMessageAction -> state.copy(messages = state.messages + action.message)
                    else -> state
                }
            }
        }

        dispatchAndAdvance(store, IncrementAction(5))
        val actions = processedActions.toList()

        assertTrue(actions.size >= 2, "Should have processed at least 2 actions")
        assertTrue(actions.any { it is IncrementAction })
        assertTrue(actions.any { it is AddMessageAction })

        assertEquals(5, store.state.value.counter)
        assertEquals(1, store.state.value.messages.size)
        assertEquals("Increment: 5", store.state.value.messages[0])
    }
}
