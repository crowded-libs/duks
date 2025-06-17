package duks

import kotlin.test.*
import kotlinx.coroutines.test.*

class MultipleReducersTest {

    data class TestState(
        val counter: Int = 0,
        val text: String = "",
        val items: List<String> = emptyList(),
        val flags: Set<String> = emptySet()
    ) : StateModel

    // Actions
    data class IncrementAction(val value: Int = 1) : Action
    data class SetTextAction(val text: String) : Action
    data class AddItemAction(val item: String) : Action
    data class SetFlagAction(val flag: String) : Action
    data class ClearFlagAction(val flag: String) : Action
    object ResetAction : Action

    @Test
    fun `should support single reducer for backwards compatibility`() = runTest {
        val store = createStoreForTest(TestState()) {
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    is SetTextAction -> state.copy(text = action.text)
                    else -> state
                }
            }
        }

        store.dispatch(IncrementAction(5))
        advanceUntilIdle()
        assertEquals(5, store.state.value.counter)

        store.dispatch(SetTextAction("Hello"))
        advanceUntilIdle()
        assertEquals("Hello", store.state.value.text)
    }

    @Test
    fun `should compose multiple reducers in order`() = runTest {
        val store = createStoreForTest(TestState()) {
            // First reducer handles counter
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    else -> state
                }
            }

            // Second reducer handles text
            reduceWith { state, action ->
                when (action) {
                    is SetTextAction -> state.copy(text = action.text)
                    else -> state
                }
            }

            // Third reducer handles items
            reduceWith { state, action ->
                when (action) {
                    is AddItemAction -> state.copy(items = state.items + action.item)
                    else -> state
                }
            }
        }

        store.dispatch(IncrementAction(3))
        advanceUntilIdle()
        assertEquals(3, store.state.value.counter)

        store.dispatch(SetTextAction("Test"))
        advanceUntilIdle()
        assertEquals("Test", store.state.value.text)

        store.dispatch(AddItemAction("Item1"))
        advanceUntilIdle()
        assertEquals(listOf("Item1"), store.state.value.items)
    }

    @Test
    fun `should allow reducers to see changes from previous reducers`() = runTest {
        val store = createStoreForTest(TestState()) {
            // First reducer sets counter
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    else -> state
                }
            }

            // Second reducer uses counter value to modify text
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(text = "Counter is now: ${state.counter}")
                    else -> state
                }
            }
        }

        store.dispatch(IncrementAction(5))
        advanceUntilIdle()
        assertEquals(5, store.state.value.counter)
        assertEquals("Counter is now: 5", store.state.value.text)
    }

    @Test
    fun `should handle reset action across multiple reducers`() = runTest {
        val store = createStoreForTest(TestState(counter = 10, text = "Hello", items = listOf("A", "B"))) {
            // Each reducer handles reset for its domain
            reduceWith { state, action ->
                when (action) {
                    is IncrementAction -> state.copy(counter = state.counter + action.value)
                    is ResetAction -> state.copy(counter = 0)
                    else -> state
                }
            }

            reduceWith { state, action ->
                when (action) {
                    is SetTextAction -> state.copy(text = action.text)
                    is ResetAction -> state.copy(text = "")
                    else -> state
                }
            }

            reduceWith { state, action ->
                when (action) {
                    is AddItemAction -> state.copy(items = state.items + action.item)
                    is ResetAction -> state.copy(items = emptyList())
                    else -> state
                }
            }
        }

        // Verify initial state
        assertEquals(10, store.state.value.counter)
        assertEquals("Hello", store.state.value.text)
        assertEquals(listOf("A", "B"), store.state.value.items)

        // Reset everything
        store.dispatch(ResetAction)
        advanceUntilIdle()
        assertEquals(0, store.state.value.counter)
        assertEquals("", store.state.value.text)
        assertEquals(emptyList(), store.state.value.items)
    }

    @Test
    fun `should work with no reducers`() = runTest {
        val initialState = TestState(counter = 42, text = "Initial")
        val store = createStoreForTest(initialState) {
            // No reducers added
        }

        // Dispatch actions - state should remain unchanged
        store.dispatch(IncrementAction(5))
        store.dispatch(SetTextAction("Changed"))
        advanceUntilIdle()

        assertEquals(initialState, store.state.value)
    }

    @Test
    fun `should allow modular reducer composition`() = runTest {
        // Define modular reducers
        val counterReducer: Reducer<TestState> = { state, action ->
            when (action) {
                is IncrementAction -> state.copy(counter = state.counter + action.value)
                else -> state
            }
        }

        val textReducer: Reducer<TestState> = { state, action ->
            when (action) {
                is SetTextAction -> state.copy(text = action.text)
                else -> state
            }
        }

        val flagsReducer: Reducer<TestState> = { state, action ->
            when (action) {
                is SetFlagAction -> state.copy(flags = state.flags + action.flag)
                is ClearFlagAction -> state.copy(flags = state.flags - action.flag)
                else -> state
            }
        }

        val store = createStoreForTest(TestState()) {
            reduceWith(counterReducer)
            reduceWith(textReducer)
            reduceWith(flagsReducer)
        }

        // Test all reducers work independently
        store.dispatch(IncrementAction(1))
        advanceUntilIdle()
        assertEquals(1, store.state.value.counter)

        store.dispatch(SetTextAction("Modular"))
        advanceUntilIdle()
        assertEquals("Modular", store.state.value.text)

        store.dispatch(SetFlagAction("feature1"))
        store.dispatch(SetFlagAction("feature2"))
        advanceUntilIdle()
        assertEquals(setOf("feature1", "feature2"), store.state.value.flags)

        store.dispatch(ClearFlagAction("feature1"))
        advanceUntilIdle()
        assertEquals(setOf("feature2"), store.state.value.flags)
    }

    @Test
    fun `should preserve order of execution for reducers`() = runTest {
        val executionOrder = mutableListOf<String>()

        val store = createStoreForTest(TestState()) {
            reduceWith { state, action ->
                if (action is IncrementAction) {
                    executionOrder.add("reducer1")
                }
                state
            }

            reduceWith { state, action ->
                if (action is IncrementAction) {
                    executionOrder.add("reducer2")
                }
                state
            }

            reduceWith { state, action ->
                if (action is IncrementAction) {
                    executionOrder.add("reducer3")
                }
                state
            }
        }

        store.dispatch(IncrementAction())
        advanceUntilIdle()

        assertEquals(listOf("reducer1", "reducer2", "reducer3"), executionOrder)
    }

    @Test
    fun `should allow middleware and plugins to add their own reducers`() = runTest {
        // Simulate a plugin that tracks action history
        data class HistoryState(
            val baseState: TestState = TestState(),
            val actionHistory: List<String> = emptyList()
        ) : StateModel

        val historyReducer: Reducer<HistoryState> = { state, action ->
            state.copy(actionHistory = state.actionHistory + action::class.simpleName!!)
        }

        val baseReducer: Reducer<HistoryState> = { state, action ->
            when (action) {
                is IncrementAction -> state.copy(
                    baseState = state.baseState.copy(counter = state.baseState.counter + action.value)
                )
                is SetTextAction -> state.copy(
                    baseState = state.baseState.copy(text = action.text)
                )
                else -> state
            }
        }

        val store = createStoreForTest(HistoryState()) {
            // Base application reducer
            reduceWith(baseReducer)
            
            // Plugin adds its own reducer
            reduceWith(historyReducer)
        }

        store.dispatch(IncrementAction(5))
        store.dispatch(SetTextAction("Hello"))
        store.dispatch(IncrementAction(3))
        advanceUntilIdle()

        val finalState = store.state.value
        assertEquals(8, finalState.baseState.counter)
        assertEquals("Hello", finalState.baseState.text)
        assertEquals(
            listOf("IncrementAction", "SetTextAction", "IncrementAction"),
            finalState.actionHistory
        )
    }
}