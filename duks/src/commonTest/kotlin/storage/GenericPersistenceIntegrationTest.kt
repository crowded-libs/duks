package duks.storage

import duks.*
import kotlin.test.*
import kotlinx.coroutines.test.*
import kotlin.time.Duration.Companion.seconds

data class CounterState(val count: Int = 0) : StateModel

object IncrementAction : Action
data class SetCountAction(val count: Int) : Action

class GenericPersistenceIntegrationTest {
    
    private fun counterReducer(state: CounterState, action: Action): CounterState = when (action) {
        is IncrementAction -> state.copy(count = state.count + 1)
        is SetCountAction -> state.copy(count = action.count)
        is RestoreStateAction<*> -> action.state as CounterState
        else -> state
    }
    
    @Test
    fun `should persist state directly to storage`() = runTest(timeout = 5.seconds) {
        // Direct state storage
        val storage = InMemoryStorage<CounterState>()
        
        // Create store with persistence
        val store = createStoreForTest(initialState = CounterState(0)) {
            reduceWith(::counterReducer)
            middleware {
                persistence(
                    storage = storage,
                    strategy = PersistenceStrategy.OnEveryChange
                )
            }
        }
        
        // Let the persistence middleware initialize
        runCurrent()
        advanceUntilIdle()
        
        // Dispatch actions
        store.dispatch(IncrementAction)
        store.dispatch(IncrementAction)
        runCurrent()
        advanceUntilIdle()
        
        // Verify state changed
        assertEquals(2, store.state.value.count)
        
        // Verify state was persisted
        val savedData = storage.load()
        assertNotNull(savedData)
        assertEquals(CounterState(2), savedData)
        
        // Create new store to test restoration
        val store2 = createStoreForTest(initialState = CounterState(0)) {
            reduceWith(::counterReducer)
            middleware {
                persistence(
                    storage = storage,
                    strategy = PersistenceStrategy.OnEveryChange
                )
            }
        }
        
        runCurrent()
        advanceUntilIdle()
        
        // State should be restored
        assertEquals(2, store2.state.value.count)
    }
    
    @Test
    fun `should persist state when set action is dispatched`() = runTest(timeout = 5.seconds) {
        // Storage that saves state directly
        val storage = InMemoryStorage<CounterState>()
        
        val store = createStoreForTest(initialState = CounterState(0)) {
            reduceWith(::counterReducer)
            middleware {
                persistence(
                    storage = storage,
                    strategy = PersistenceStrategy.OnEveryChange
                )
            }
        }
        
        store.dispatch(SetCountAction(42))
        runCurrent()
        advanceUntilIdle()
        
        // State is stored directly
        val savedState = storage.load()
        assertNotNull(savedState)
        assertEquals(CounterState(42), savedState)
    }
    
    @Test
    fun `should debounce state persistence with configured delay`() = runTest(timeout = 5.seconds) {
        val storage = InMemoryStorage<CounterState>()
        
        val store = createStoreForTest(initialState = CounterState(0)) {
            reduceWith(::counterReducer)
            middleware {
                persistence(
                    storage = storage,
                    strategy = PersistenceStrategy.Debounced(500)
                )
            }
        }
        
        // Dispatch multiple actions quickly
        store.dispatch(IncrementAction)
        store.dispatch(IncrementAction)
        store.dispatch(IncrementAction)
        
        // Should not have persisted yet
        advanceTimeBy(200)
        assertNull(storage.load())
        
        // After debounce delay, should persist
        advanceTimeBy(400)
        advanceUntilIdle()
        
        val saved = storage.load()
        assertNotNull(saved)
        assertEquals(3, saved.count)
    }
    
    @Test
    fun `should persist state conditionally based on custom logic`() = runTest(timeout = 5.seconds) {
        val storage = InMemoryStorage<CounterState>()
        
        val store = createStoreForTest(initialState = CounterState(0)) {
            reduceWith(::counterReducer)
            middleware {
                persistence(
                    storage = storage,
                    strategy = PersistenceStrategy.Conditional { current, previous ->
                        // Only persist when count is even
                        (current as CounterState).count % 2 == 0
                    }
                )
            }
        }
        
        // Dispatch to odd number - should not persist
        store.dispatch(IncrementAction)
        runCurrent()
        advanceUntilIdle()
        assertNull(storage.load())
        
        // Dispatch to even number - should persist
        store.dispatch(IncrementAction)
        runCurrent()
        advanceUntilIdle()
        
        val saved = storage.load()
        assertNotNull(saved)
        assertEquals(2, saved.count)
    }
    
    @Test
    fun `should persist state only on specific actions`() = runTest(timeout = 5.seconds) {
        val storage = InMemoryStorage<CounterState>()
        
        val store = createStoreForTest(initialState = CounterState(0)) {
            reduceWith(::counterReducer)
            middleware {
                persistence(
                    storage = storage,
                    strategy = PersistenceStrategy.OnAction(setOf(SetCountAction::class))
                )
            }
        }
        
        // Increment should not trigger persistence
        store.dispatch(IncrementAction)
        runCurrent()
        advanceUntilIdle()
        assertNull(storage.load())
        
        // SetCountAction should trigger persistence
        store.dispatch(SetCountAction(10))
        runCurrent()
        advanceUntilIdle()
        
        val saved = storage.load()
        assertNotNull(saved)
        assertEquals(10, saved.count)
    }
    
    @Test
    fun `should restore state from storage on store creation`() = runTest(timeout = 5.seconds) {
        val storage = InMemoryStorage<CounterState>()
        
        // Pre-save state
        storage.save(CounterState(42))
        
        // Create store with persistence - should restore
        val store = createStoreForTest(initialState = CounterState(0)) {
            reduceWith(::counterReducer)
            middleware {
                persistence(
                    storage = storage,
                    strategy = PersistenceStrategy.OnEveryChange
                )
            }
        }
        
        // Wait for restoration
        runCurrent()
        advanceUntilIdle()
        
        // State should be restored
        assertEquals(42, store.state.value.count)
    }
    
    @Test
    fun `should handle persistence errors gracefully`() = runTest(timeout = 5.seconds) {
        // Storage that throws errors
        val storage = object : StateStorage<CounterState> {
            private var shouldFail = true
            
            override suspend fun save(state: CounterState) {
                if (shouldFail) {
                    shouldFail = false
                    throw RuntimeException("Save failed")
                }
            }
            
            override suspend fun load(): CounterState? = null
            override suspend fun clear() {}
            override suspend fun exists(): Boolean = false
        }
        
        var errorCount = 0
        
        val store = createStoreForTest(initialState = CounterState(0)) {
            reduceWith(::counterReducer)
            middleware {
                persistence(
                    storage = storage,
                    strategy = PersistenceStrategy.OnEveryChange,
                    errorHandler = { errorCount++ }
                )
            }
        }
        
        // First save should fail
        store.dispatch(IncrementAction)
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, errorCount)
        
        // Second save should succeed
        store.dispatch(IncrementAction)
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, errorCount) // No new errors
    }
    
    @Test
    fun `should support combined persistence strategies`() = runTest(timeout = 5.seconds) {
        val storage = InMemoryStorage<CounterState>()
        
        val store = createStoreForTest(initialState = CounterState(0)) {
            reduceWith(::counterReducer)
            middleware {
                persistence(
                    storage = storage,
                    strategy = PersistenceStrategy.Combined(listOf(
                        PersistenceStrategy.OnAction(setOf(SetCountAction::class)),
                        PersistenceStrategy.Conditional { current, _ ->
                            (current as CounterState).count >= 10
                        }
                    ))
                )
            }
        }
        
        // Increment to 5 - should not persist (condition not met)
        repeat(5) { store.dispatch(IncrementAction) }
        runCurrent()
        advanceUntilIdle()
        assertNull(storage.load())
        
        // SetCountAction to 8 - should persist (action matches)
        store.dispatch(SetCountAction(8))
        runCurrent()
        advanceUntilIdle()
        assertNotNull(storage.load())
        assertEquals(8, storage.load()?.count)
        
        // Clear and increment to 10 - should persist (condition met)
        storage.clear()
        store.dispatch(SetCountAction(9))
        store.dispatch(IncrementAction)
        runCurrent()
        advanceUntilIdle()
        assertNotNull(storage.load())
        assertEquals(10, storage.load()?.count)
    }
}