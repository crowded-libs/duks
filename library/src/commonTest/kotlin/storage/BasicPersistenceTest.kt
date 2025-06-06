package org.ducks.storage

import duks.*
import duks.storage.InMemoryStorage
import duks.storage.PersistenceStrategy
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlin.test.*

class BasicPersistenceTest {
    
    data class TestState(val value: Int = 0) : StateModel
    data class IncrementAction(val amount: Int = 1) : Action
    
    private fun testReducer(state: TestState, action: Action): TestState {
        return when (action) {
            is IncrementAction -> state.copy(value = state.value + action.amount)
            is RestoreStateAction<*> -> action.state as TestState
            else -> state
        }
    }
    
    @Test
    fun testInMemoryStorage() = runTest {
        val storage = InMemoryStorage<TestState>()
        
        assertFalse(storage.exists())
        assertNull(storage.load())
        
        val data = TestState(1)
        storage.save(data)
        
        assertTrue(storage.exists())
        assertEquals(data, storage.load())
        
        storage.clear()
        assertFalse(storage.exists())
    }
    
    @Test
    fun testPersistenceMiddleware() = runTest {
        val storage = InMemoryStorage<TestState>()

        val store = createStoreForTest(initialState = TestState(0)) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = storage,
                    strategy = PersistenceStrategy.OnEveryChange
                )
            }
        }
        
        // Dispatch action and wait
        store.dispatch(IncrementAction(5))
        runCurrent()
        advanceUntilIdle()
        delay(200) // Allow persistence to complete
        
        // Check state was persisted
        assertTrue(storage.exists())
        val loaded = storage.load()!!
        assertEquals(5, loaded.value)
    }
    
    @Test
    fun testStateRestoration() = runTest {
        val storage = InMemoryStorage<TestState>()

        // Pre-save state
        storage.save(TestState(42))
        
        // Create store with persistence - should restore
        val store = createStoreForTest(initialState = TestState(0)) {
            reduceWith(::testReducer)
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
        delay(200)
        
        // State should be restored
        assertEquals(42, store.state.value.value)
    }
}