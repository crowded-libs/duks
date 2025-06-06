package duks.storage

import duks.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlinx.serialization.Serializable
import kotlin.test.*
import kotlin.test.Ignore

class PersistenceTest {
    
    @Serializable
    data class TestState(
        val counter: Int = 0,
        val message: String = "",
        val items: List<String> = emptyList()
    ) : StateModel
    
    // Actions
    data class IncrementAction(val amount: Int = 1) : Action
    data class UpdateMessageAction(val message: String) : Action
    data class AddItemAction(val item: String) : Action
    object SaveAction : Action // Trigger for OnAction strategy
    object ResetAction : Action
    
    private fun testReducer(state: TestState, action: Action): TestState = when (action) {
        is IncrementAction -> state.copy(counter = state.counter + action.amount)
        is UpdateMessageAction -> state.copy(message = action.message)
        is AddItemAction -> state.copy(items = state.items + action.item)
        is RestoreStateAction<*> -> action.state as TestState
        ResetAction -> TestState()
        else -> state
    }
    
    @BeforeTest
    fun setup() {
        TestFileSystem.instance.clear()
    }
    
    @Test
    fun testJsonFileStorage() = runTest {
        val storage = createJsonFileStorage<TestState>("test.json")
        
        val testState = TestState(
            counter = 42,
            message = "Hello Persistence",
            items = listOf("item1", "item2")
        )
        
        // Save state
        storage.save(testState)
        
        // Verify it exists
        assertTrue(storage.exists())
        
        // Load and verify
        val loaded = storage.load()
        assertNotNull(loaded)
        assertEquals(testState, loaded)
        
        // Clear and verify
        storage.clear()
        assertFalse(storage.exists())
        assertNull(storage.load())
    }
    
    @Test
    fun testPersistenceOnEveryChange() = runTest {
        val fileStorage = createJsonFileStorage<TestState>("state.json")
        
        // Create a composite storage with testing hooks
        val compositeStorage = object : StateStorage<TestState> {
            val inMemory = InMemoryStorage<TestState>()
            
            override suspend fun save(state: TestState) {
                inMemory.save(state)
                fileStorage.save(state)
            }
            override suspend fun load(): TestState? = fileStorage.load() ?: inMemory.load()
            override suspend fun clear() {
                inMemory.clear()
                fileStorage.clear()
            }
            override suspend fun exists(): Boolean = fileStorage.exists() || inMemory.exists()
        }
        
        // Wrap with testable functionality
        val testableStorage = compositeStorage.testable()
        
        val store = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = testableStorage,
                    strategy = PersistenceStrategy.OnEveryChange
                )
            }
        }
        
        // Wait for store initialization
        runCurrent()
        
        // Initial state should not be persisted immediately
        assertFalse(fileStorage.exists())
        
        // Dispatch action and wait for save
        waitForSave(testableStorage) {
            store.dispatch(IncrementAction(5))
        }
        
        // Verify persistence
        assertTrue(fileStorage.exists())
        val persisted = fileStorage.load()!!
        assertEquals(5, persisted.counter)
        
        // Another action
        waitForSave(testableStorage) {
            store.dispatch(UpdateMessageAction("Test"))
        }
        
        val persisted2 = fileStorage.load()!!
        assertEquals(5, persisted2.counter)
        assertEquals("Test", persisted2.message)
    }
    
    @Test
    fun testPersistenceDebounced() = runTest {
        val inMemoryStorage = InMemoryStorage<TestState>()
        val testableStorage = inMemoryStorage.testable()
        
        val store = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = testableStorage,
                    strategy = PersistenceStrategy.Debounced(200)
                )
            }
        }
        
        // Wait for initial store setup
        runCurrent()
        advanceUntilIdle()
        
        // Rapid fire actions
        store.dispatch(IncrementAction(1))
        store.dispatch(IncrementAction(2))
        store.dispatch(IncrementAction(3))
        
        // Should not persist immediately
        runCurrent()
        assertEquals(0, testableStorage.saveCount)
        
        // Wait less than debounce time
        advanceTimeBy(100)
        runCurrent()
        assertEquals(0, testableStorage.saveCount)
        
        // Wait for debounce to complete (total time should be > 200ms)
        advanceTimeBy(150)  // Now at 250ms total
        runCurrent()
        advanceUntilIdle()
        
        // Check if data was persisted
        assertEquals(1, testableStorage.saveCount, "Should have saved once after debounce")
        assertTrue(inMemoryStorage.exists())
        
        // Only final state should be persisted
        val persisted = inMemoryStorage.load()!!
        assertEquals(6, persisted.counter) // 1 + 2 + 3
    }
    
    @Test
    @Ignore  // Throttled persistence uses real time, not test time
    fun testPersistenceThrottled() = runTest {
        val inMemoryStorage = InMemoryStorage<TestState>()
        val testableStorage = inMemoryStorage.testable()
        
        val store = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = testableStorage,
                    strategy = PersistenceStrategy.Throttled(300)
                )
            }
        }
        
        // Wait for store initialization
        runCurrent()
        advanceUntilIdle()
        
        // First action should save immediately (throttle allows first save)
        store.dispatch(IncrementAction(1))
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, testableStorage.saveCount)
        
        // Dispatch multiple actions quickly
        repeat(4) { i ->
            store.dispatch(IncrementAction(1))
            advanceTimeBy(50) // Less than throttle interval
            runCurrent()
        }
        
        // Should still be 1 save (throttled)
        assertEquals(1, testableStorage.saveCount)
        
        // Wait for throttle interval to pass from first save
        advanceTimeBy(150) // Total time now > 300ms from first save
        runCurrent()
        
        // One more action should trigger another save
        store.dispatch(IncrementAction(1))
        runCurrent()
        advanceUntilIdle()
        assertEquals(2, testableStorage.saveCount)
        
        // Verify final state
        val persisted = inMemoryStorage.load()!!
        assertEquals(6, persisted.counter) // 1 + 1 + 1 + 1 + 1 + 1
    }
    
    @Test
    fun testPersistenceOnAction() = runTest {
        val inMemoryStorage = InMemoryStorage<TestState>()
        val testableStorage = inMemoryStorage.testable()
        
        val store = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = testableStorage,
                    strategy = PersistenceStrategy.OnAction(setOf(SaveAction::class))
                )
            }
        }
        
        // Regular actions should not trigger persistence
        store.dispatch(IncrementAction(10))
        store.dispatch(UpdateMessageAction("No save yet"))
        runCurrent()
        assertFalse(inMemoryStorage.exists())
        assertEquals(0, testableStorage.saveCount)
        
        // SaveAction should trigger persistence
        waitForSave(testableStorage) {
            store.dispatch(SaveAction)
        }
        assertTrue(inMemoryStorage.exists())
        assertEquals(1, testableStorage.saveCount)
        
        val persisted = inMemoryStorage.load()!!
        assertEquals(10, persisted.counter)
        assertEquals("No save yet", persisted.message)
    }
    
    @Test
    fun testPersistenceConditional() = runTest {
        val inMemoryStorage = InMemoryStorage<TestState>()
        val testableStorage = inMemoryStorage.testable()
        
        val store = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = testableStorage,
                    strategy = PersistenceStrategy.Conditional { current, previous ->
                        // Only persist when counter is multiple of 5
                        val currentState = current as TestState
                        val previousState = previous as? TestState
                        currentState.counter % 5 == 0 && currentState.counter != previousState?.counter
                    }
                )
            }
        }
        
        // These should not persist
        store.dispatch(IncrementAction(1)) // counter = 1
        store.dispatch(IncrementAction(2)) // counter = 3
        runCurrent()
        assertFalse(inMemoryStorage.exists())
        assertEquals(0, testableStorage.saveCount)
        
        // This should persist (counter = 5)
        waitForSave(testableStorage) {
            store.dispatch(IncrementAction(2))
        }
        assertTrue(inMemoryStorage.exists())
        assertEquals(1, testableStorage.saveCount)
        
        var persisted = inMemoryStorage.load()!!
        assertEquals(5, persisted.counter)
        
        // Clear for next test
        inMemoryStorage.clear()
        
        // Skip to 10
        waitForSave(testableStorage) {
            store.dispatch(IncrementAction(5)) // counter = 10
        }
        assertTrue(inMemoryStorage.exists())
        assertEquals(2, testableStorage.saveCount)
        
        persisted = inMemoryStorage.load()!!
        assertEquals(10, persisted.counter)
    }
    
    @Test
    fun testPersistenceCombined() = runTest {
        val inMemoryStorage = InMemoryStorage<TestState>()
        val testableStorage = inMemoryStorage.testable()
        
        val store = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = testableStorage,
                    strategy = PersistenceStrategy.Combined(listOf(
                        PersistenceStrategy.OnAction(setOf(SaveAction::class)),
                        PersistenceStrategy.Conditional { current, _ ->
                            (current as TestState).counter >= 10
                        }
                    ))
                )
            }
        }
        
        // Neither condition met - no save
        store.dispatch(IncrementAction(5))
        runCurrent()
        assertEquals(0, testableStorage.saveCount)
        
        // SaveAction triggers save
        waitForSave(testableStorage) {
            store.dispatch(SaveAction)
        }
        assertEquals(1, testableStorage.saveCount)
        
        // Conditional triggers save (counter >= 10)
        waitForSave(testableStorage) {
            store.dispatch(IncrementAction(5)) // counter = 10
        }
        assertEquals(2, testableStorage.saveCount)
    }
    
    @Test
    fun testStateRestoration() = runTest {
        val fileStorage = createJsonFileStorage<TestState>("restore.json")
        
        // Pre-save a state
        val savedState = TestState(
            counter = 99,
            message = "Restored from disk",
            items = listOf("a", "b", "c")
        )
        fileStorage.save(savedState)
        
        // Wrap with testable functionality to wait for restore
        val testableStorage = fileStorage.testable()
        
        // Create store with persistence - should restore
        var restoredState: TestState? = null
        val store = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            middleware {
                // Add middleware to capture restored state
                middleware { _, next, action ->
                    if (action is RestoreStateAction<*>) {
                        restoredState = action.state as TestState
                    }
                    next(action)
                }
                persistence(
                    storage = testableStorage,
                    strategy = PersistenceStrategy.OnEveryChange
                )
            }
        }
        
        // Wait for restoration to complete
        while (restoredState == null && testableStorage.loadCount < 1) {
            runCurrent()
            advanceTimeBy(10)
        }
        
        // Verify restoration happened
        assertNotNull(restoredState)
        assertEquals(savedState, restoredState)
        
        // Store state should be restored
        assertEquals(99, store.state.value.counter)
        assertEquals("Restored from disk", store.state.value.message)
        assertEquals(listOf("a", "b", "c"), store.state.value.items)
    }
    
    @Test
    fun testPersistenceErrorHandling() = runTest {
        val errors = mutableListOf<Exception>()
        val failingStorage = object : StateStorage<TestState> {
            override suspend fun save(state: TestState) {
                throw RuntimeException("Simulated save failure")
            }
            override suspend fun load(): TestState? = null
            override suspend fun clear() {}
            override suspend fun exists(): Boolean = false
        }
        
        val store = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = failingStorage,
                    strategy = PersistenceStrategy.OnEveryChange,
                    errorHandler = { errors.add(it) }
                )
            }
        }
        
        // Dispatch action that triggers persistence
        store.dispatch(IncrementAction(1))
        runCurrent()
        advanceUntilIdle()
        
        // Error should be handled
        assertEquals(1, errors.size)
        assertEquals("Simulated save failure", errors[0].message)
        
        // Store should continue functioning
        assertEquals(1, store.state.value.counter)
    }
    
    @Test
    fun testCompositeStorage() = runTest {
        val storage1 = createJsonFileStorage<TestState>("composite1.json")
        val storage2 = createJsonFileStorage<TestState>("composite2.json")
        val composite = CompositeStorage(listOf(storage1, storage2))
        
        val testState = TestState(counter = 42, message = "Composite")
        
        // Save through composite
        composite.save(testState)
        
        // Both storages should have the data
        assertTrue(storage1.exists())
        assertTrue(storage2.exists())
        
        val loaded1 = storage1.load()!!
        val loaded2 = storage2.load()!!
        assertEquals(testState, loaded1)
        assertEquals(testState, loaded2)
        
        // Clear first storage
        storage1.clear()
        
        // Composite should still load from second storage
        val loadedComposite = composite.load()
        assertNotNull(loadedComposite)
        assertEquals(testState, loadedComposite)
        
        // Clear composite clears all
        composite.clear()
        assertFalse(storage1.exists())
        assertFalse(storage2.exists())
    }
    
    @Test
    fun testMultipleStoresWithSameStorage() = runTest {
        val fileStorage = createJsonFileStorage<TestState>("shared.json")
        
        // Wrap file storage with testable functionality
        val testableStorage = fileStorage.testable()
        
        // First store saves state
        val store1 = createStoreForTest(TestState(counter = 1)) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = testableStorage,
                    strategy = PersistenceStrategy.OnEveryChange
                )
            }
        }
        
        waitForSave(testableStorage) {
            store1.dispatch(IncrementAction(9)) // counter = 10
        }
        
        // Reset counters for tracking the second store's operations
        testableStorage.resetCounters()
        
        // Second store should restore from first store's state
        val store2 = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = testableStorage,
                    strategy = PersistenceStrategy.OnEveryChange
                )
            }
        }
        
        // Wait for the load operation to complete
        while (testableStorage.loadCount < 1) {
            runCurrent()
            advanceTimeBy(10)
        }
        
        // Store2 should have store1's persisted state
        assertEquals(10, store2.state.value.counter)
    }
}