package duks.storage

import duks.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.Serializable
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class StatePersistenceTest {
    
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
        ResetAction -> TestState()
        else -> state
    }
    
    @BeforeTest
    fun setup() {
        TestFileSystem.instance.clear()
    }
    
    @Test
    fun `should save and load state with JSON file storage`() = runTest(timeout = 5.seconds) {
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
    fun `should persist state on every change`() = runTest(timeout = 5.seconds) {
        // Use in-memory storage for better cross-platform compatibility
        val inMemoryStorage = InMemoryStorage<TestState>()
        
        // Wrap with testable functionality
        val testableStorage = inMemoryStorage.testable()
        
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
        assertFalse(inMemoryStorage.exists())
        
        // Dispatch action and wait for save
        waitForSave(testableStorage) {
            store.dispatch(IncrementAction(5))
        }

        // Verify persistence
        assertTrue(inMemoryStorage.exists())
        val persisted = inMemoryStorage.load()!!
        assertEquals(5, persisted.counter)
        
        // Another action
        waitForSave(testableStorage) {
            store.dispatch(UpdateMessageAction("Test"))
        }
        
        val persisted2 = inMemoryStorage.load()!!
        assertEquals(5, persisted2.counter)
        assertEquals("Test", persisted2.message)
    }
    
    @Test
    fun `should debounce state persistence`() = runTest(timeout = 5.seconds) {
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
        assertEquals(0, testableStorage.state.value.saveCount)
        
        // Wait less than debounce time
        advanceTimeBy(100)
        runCurrent()
        assertEquals(0, testableStorage.state.value.saveCount)
        
        // Wait for debounce to complete (total time should be > 200ms)
        advanceTimeBy(150)  // Now at 250ms total
        runCurrent()
        advanceUntilIdle()
        
        // Check if data was persisted
        assertEquals(1, testableStorage.state.value.saveCount, "Should have saved once after debounce")
        assertTrue(inMemoryStorage.exists())
        
        // Only final state should be persisted
        val persisted = inMemoryStorage.load()!!
        assertEquals(6, persisted.counter) // 1 + 2 + 3
    }
    
    
    @Test
    fun `should persist state only on specific actions`() = runTest(timeout = 5.seconds) {
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
        assertEquals(0, testableStorage.state.value.saveCount)
        
        // SaveAction should trigger persistence
        waitForSave(testableStorage) {
            store.dispatch(SaveAction)
        }
        assertTrue(inMemoryStorage.exists())
        assertEquals(1, testableStorage.state.value.saveCount)
        
        val persisted = inMemoryStorage.load()!!
        assertEquals(10, persisted.counter)
        assertEquals("No save yet", persisted.message)
    }
    
    @Test
    fun `should persist state conditionally`() = runTest(timeout = 5.seconds) {
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
        assertEquals(0, testableStorage.state.value.saveCount)
        
        // This should persist (counter = 5)
        waitForSave(testableStorage) {
            store.dispatch(IncrementAction(2))
        }

        assertTrue(inMemoryStorage.exists())
        assertEquals(1, testableStorage.state.value.saveCount)
        
        var persisted = inMemoryStorage.load()!!
        assertEquals(5, persisted.counter)
        
        // Clear for next test
        inMemoryStorage.clear()
        
        // Skip to 10
        waitForSave(testableStorage) {
            store.dispatch(IncrementAction(5)) // counter = 10
        }
        assertTrue(inMemoryStorage.exists())
        assertEquals(2, testableStorage.state.value.saveCount)
        
        persisted = inMemoryStorage.load()!!
        assertEquals(10, persisted.counter)
    }
    
    @Test
    fun `should support combined persistence strategies`() = runTest(timeout = 5.seconds) {
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
        assertEquals(0, testableStorage.state.value.saveCount)

        waitForSave(testableStorage) {
            store.dispatch(SaveAction)
        }
        assertEquals(1, testableStorage.state.value.saveCount)

        waitForSave(testableStorage) {
            store.dispatch(IncrementAction(5)) // counter = 10
        }
        assertEquals(2, testableStorage.state.value.saveCount)
    }
    
    @Test
    fun `should restore state from storage`() = runTest(timeout = 5.seconds) {
        val fileStorage = createJsonFileStorage<TestState>("restore.json")
        val testableStorage = fileStorage.testable()

        // Pre-save a state
        val savedState = TestState(
            counter = 99,
            message = "Restored from disk",
            items = listOf("a", "b", "c")
        )
        // Wrap with testable functionality to wait for restore
        waitForSave(testableStorage) {
            testableStorage.save(savedState)
        }

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

        testableStorage.state.first { it.loadCount == 1 }
        runCurrent()
        advanceUntilIdle()
        
        // Verify restoration happened
        assertNotNull(restoredState)
        assertEquals(savedState, restoredState)
        
        // Store state should be restored
        assertEquals(99, store.state.value.counter)
        assertEquals("Restored from disk", store.state.value.message)
        assertEquals(listOf("a", "b", "c"), store.state.value.items)
    }
    
    @Test
    fun `should handle persistence errors gracefully`() = runTest(timeout = 5.seconds) {
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
    fun `should save to multiple storages with composite storage`() = runTest(timeout = 5.seconds) {
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
    fun `should share storage between multiple stores`() = runTest(timeout = 5.seconds) {
        // Use in-memory storage instead of file storage for better cross-platform compatibility
        val storage = InMemoryStorage<TestState>()
        
        // Wrap storage with testable functionality
        val testableStorage = storage.testable()
        
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
        
        // Wait for initialization to complete
        runCurrent()
        advanceUntilIdle()
        
        // Dispatch a small action first to ensure middleware is initialized
        store1.dispatch(IncrementAction(0)) // This initializes the middleware
        runCurrent()
        advanceUntilIdle()

        waitForSave(testableStorage) {
            store1.dispatch(IncrementAction(9)) // counter = 10
        }

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
        
        // Use a more robust wait mechanism for restoration
        var attempts = 0
        while (testableStorage.state.value.loadCount == 0 && attempts < 50) {
            delay(10)
            runCurrent()
            attempts++
        }
        
        // Ensure restoration happened
        assertTrue(testableStorage.state.value.loadCount > 0, "State should have been loaded")
        
        runCurrent()
        advanceUntilIdle()

        // Store2 should have store1's persisted state
        assertEquals(10, store2.state.value.counter)
    }
    
    // === Tests from DebouncedPersistenceRaceConditionTest ===
    
    @Test
    fun `should not save initial state when restoring with debounced strategy`() = runTest(timeout = 5.seconds) {
        val storage = InMemoryStorage<TestState>()
        
        // Pre-save some state
        val savedState = TestState(counter = 10, message = "restored")
        storage.save(savedState)
        
        // Track all saves
        var saveCount = 0
        val savedStates = mutableListOf<TestState>()
        
        val trackingStorage = object : StateStorage<TestState> {
            override suspend fun save(state: TestState) {
                saveCount++
                savedStates.add(state)
                storage.save(state)
            }
            override suspend fun load(): TestState? = storage.load()
            override suspend fun clear() = storage.clear()
            override suspend fun exists(): Boolean = storage.exists()
        }
        
        // Create store with debounced persistence
        val store = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = trackingStorage,
                    strategy = PersistenceStrategy.Debounced(100)
                )
            }
        }
        
        // Wait for restoration to complete
        runCurrent()
        advanceUntilIdle()
        
        // Verify state was restored
        assertEquals(10, store.state.value.counter)
        assertEquals("restored", store.state.value.message)
        
        // Wait longer than debounce time to ensure no pending saves
        advanceTimeBy(200)
        runCurrent()
        advanceUntilIdle()
        
        // Initial state should NOT have been saved
        assertEquals(0, saveCount, "No saves should have occurred during restoration")
        assertTrue(savedStates.isEmpty(), "No states should have been saved")
        
        // Now dispatch an action - this should trigger a save
        store.dispatch(UpdateMessageAction("updated"))
        
        // Wait for debounce
        advanceTimeBy(150)
        runCurrent()
        advanceUntilIdle()
        
        // Now we should have exactly one save
        assertEquals(1, saveCount, "Exactly one save should have occurred after action")
        assertEquals("updated", savedStates[0].message)
    }
    
    @Test
    fun `should handle rapid actions after restoration correctly`() = runTest(timeout = 5.seconds) {
        val storage = InMemoryStorage<TestState>()
        
        // Pre-save some state
        storage.save(TestState(counter = 5, message = "restored"))
        
        val testableStorage = storage.testable()
        
        // Create store with debounced persistence
        val store = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = testableStorage,
                    strategy = PersistenceStrategy.Debounced(100)
                )
            }
        }
        
        // Wait for restoration
        testableStorage.state.first { it.loadCount == 1 }
        runCurrent()
        advanceUntilIdle()
        
        // Verify restored
        assertEquals("restored", store.state.value.message)
        
        // Fire rapid actions
        store.dispatch(UpdateMessageAction("update1"))
        store.dispatch(UpdateMessageAction("update2"))
        store.dispatch(UpdateMessageAction("update3"))
        
        // Should not save immediately
        runCurrent()
        assertEquals(0, testableStorage.state.value.saveCount)
        
        // Wait for debounce
        advanceTimeBy(150)
        runCurrent()
        advanceUntilIdle()
        
        // Should have saved once with final state
        assertEquals(1, testableStorage.state.value.saveCount)
        val finalState = storage.load()!!
        assertEquals("update3", finalState.message)
    }
    
    @Test
    fun `should not save empty state after successful restoration on app restart`() = runTest(timeout = 5.seconds) {
        val storage = InMemoryStorage<TestState>()
        
        // First app session - save some state
        val firstStore = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = storage,
                    strategy = PersistenceStrategy.Debounced(100)
                )
            }
        }
        
        // Wait for initialization
        runCurrent()
        advanceUntilIdle()
        
        // Update state in first session
        firstStore.dispatch(UpdateMessageAction("session1-data"))
        
        // Wait for debounce to save
        advanceTimeBy(150)
        runCurrent()
        advanceUntilIdle()
        
        // Verify state was saved
        assertEquals("session1-data", storage.load()?.message)
        
        // Second app session - should restore previous state
        val trackingStorage = object : StateStorage<TestState> {
            val operations = mutableListOf<String>()
            
            override suspend fun save(state: TestState) {
                operations.add("save: ${state.message}")
                storage.save(state)
            }
            override suspend fun load(): TestState? {
                val result = storage.load()
                operations.add("load: ${result?.message}")
                return result
            }
            override suspend fun clear() = storage.clear()
            override suspend fun exists(): Boolean = storage.exists()
        }
        
        val secondStore = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = trackingStorage,
                    strategy = PersistenceStrategy.Debounced(100)
                )
            }
        }
        
        // Wait for restoration
        runCurrent()
        advanceUntilIdle()
        
        // State should be restored
        assertEquals("session1-data", secondStore.state.value.message)
        
        // Wait to ensure no unwanted saves occur
        advanceTimeBy(200)
        runCurrent()
        advanceUntilIdle()
        
        // Check operations log
        val saveOps = trackingStorage.operations.filter { it.startsWith("save:") }
        assertTrue(saveOps.isEmpty() || saveOps.none { it.contains("") }, 
            "Initial state should not be saved after restoration. Operations: ${trackingStorage.operations}")
        
        // Third app session - should still have the data from session 1
        val thirdStore = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = storage,
                    strategy = PersistenceStrategy.Debounced(100)
                )
            }
        }
        
        // Wait for restoration
        runCurrent()
        advanceUntilIdle()
        
        // State should still be the data from session 1
        assertEquals("session1-data", thirdStore.state.value.message, 
            "Third session should restore session1-data, not empty/initial state")
    }
    
    // === Tests from FlowBasedPersistenceTest ===
    
    @Test
    fun `should use single collector job for debounced persistence`() = runTest(timeout = 5.seconds) {
        val storage = InMemoryStorage<TestState>()
        val testableStorage = storage.testable()
        
        val store = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = testableStorage,
                    strategy = PersistenceStrategy.Debounced(500)
                )
            }
        }
        
        // Wait for store initialization
        runCurrent()
        advanceUntilIdle()
        
        // Dispatch multiple actions rapidly
        store.dispatch(IncrementAction(1))
        runCurrent()
        
        store.dispatch(IncrementAction(2))
        runCurrent()
        
        store.dispatch(IncrementAction(3))
        runCurrent()
        
        // Verify no saves yet (debounced)
        assertEquals(0, testableStorage.state.value.saveCount)
        
        // Advance time past debounce period
        advanceTimeBy(600)
        runCurrent()
        advanceUntilIdle()
        
        // Should have saved once with final state
        assertEquals(1, testableStorage.state.value.saveCount)
        val saved = storage.load()!!
        assertEquals(6, saved.counter) // 1 + 2 + 3
    }
    
    @Test
    fun `should not create multiple collector jobs`() = runTest(timeout = 5.seconds) {
        val storage = InMemoryStorage<TestState>()
        val testableStorage = storage.testable()
        
        val store = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = testableStorage,
                    strategy = PersistenceStrategy.Debounced(100)
                )
            }
        }
        
        // Wait for initialization
        runCurrent()
        advanceUntilIdle()
        
        // Dispatch many actions rapidly
        repeat(10) { i ->
            store.dispatch(IncrementAction(1))
            store.dispatch(UpdateMessageAction("Message $i"))
            runCurrent()
        }
        
        // Advance time to trigger debounce
        advanceTimeBy(200)
        runCurrent()
        advanceUntilIdle()
        
        // Should have saved once with final state
        assertEquals(1, testableStorage.state.value.saveCount)
        val saved = storage.load()!!
        assertEquals(10, saved.counter)
        assertEquals("Message 9", saved.message)
    }
    
    @Test
    fun `should handle combined strategies with flow merge`() = runTest(timeout = 5.seconds) {
        val storage = InMemoryStorage<TestState>()
        val testableStorage = storage.testable()
        
        val store = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = testableStorage,
                    strategy = PersistenceStrategy.Combined(listOf(
                        PersistenceStrategy.Debounced(300),
                        PersistenceStrategy.Conditional { current, previous ->
                            // Save when counter changes by 5 or more
                            kotlin.math.abs((current as TestState).counter - ((previous as? TestState)?.counter ?: 0)) >= 5
                        }
                    ))
                )
            }
        }
        
        // Wait for initialization
        runCurrent()
        advanceUntilIdle()
        
        // Small increment - won't trigger conditional
        store.dispatch(IncrementAction(2))
        runCurrent()
        
        // Wait less than debounce
        advanceTimeBy(200)
        runCurrent()
        
        // No save yet
        assertEquals(0, testableStorage.state.value.saveCount)
        
        // Large increment - triggers conditional immediately
        store.dispatch(IncrementAction(10))
        runCurrent()
        advanceUntilIdle()
        
        // Should save immediately due to conditional
        assertEquals(1, testableStorage.state.value.saveCount)
        var saved = storage.load()!!
        assertEquals(12, saved.counter) // 2 + 10
        
        // Small increment again
        store.dispatch(IncrementAction(1))
        runCurrent()
        
        // Wait for debounce to trigger
        advanceTimeBy(400)
        runCurrent()
        advanceUntilIdle()
        
        // Should have saved again due to debounce
        assertEquals(2, testableStorage.state.value.saveCount)
        saved = storage.load()!!
        assertEquals(13, saved.counter) // 12 + 1
    }
    
    // === Tests from RestoreStateActionTest ===
    
    @Test
    fun `RestoreStateAction should update store state without reducer handling`() = runTest(timeout = 5.seconds) {
        // Create a reducer that doesn't handle RestoreStateAction
        val reducer: Reducer<TestState> = { state, action ->
            when (action) {
                is IncrementAction -> state.copy(counter = state.counter + action.amount)
                else -> state // Does NOT handle RestoreStateAction
            }
        }
        
        // Create store with initial state using test utilities
        val store = createStoreForTest(TestState(counter = 0)) {
            reduceWith(reducer)
        }
        
        // Verify initial state
        assertEquals(0, store.state.value.counter)
        
        // Dispatch RestoreStateAction with different state
        val restoredState = TestState(counter = 100, message = "restored")
        store.dispatch(RestoreStateAction(restoredState))
        
        // Wait for action to be processed
        runCurrent()
        advanceUntilIdle()
        
        // Verify state was restored even though reducer doesn't handle RestoreStateAction
        assertEquals(100, store.state.value.counter)
        assertEquals("restored", store.state.value.message)
        
        // Verify normal actions still work
        store.dispatch(IncrementAction(5))
        runCurrent()
        advanceUntilIdle()
        
        assertEquals(105, store.state.value.counter)
    }
    
    @Test
    fun `RestoreStateAction should work with multiple reducers none handling it`() = runTest(timeout = 5.seconds) {
        // Create multiple reducers that don't handle RestoreStateAction
        val reducer1: Reducer<TestState> = { state, action ->
            when (action) {
                is IncrementAction -> state.copy(counter = state.counter + action.amount)
                else -> state
            }
        }
        
        val reducer2: Reducer<TestState> = { state, action ->
            when (action) {
                is IncrementAction -> state.copy(counter = state.counter * 2) // Different behavior
                else -> state
            }
        }
        
        // Create store with composed reducers
        val store = createStoreForTest(TestState(counter = 1)) {
            reduceWith(reducer1)
            reduceWith(reducer2)
        }
        
        // Verify initial state
        assertEquals(1, store.state.value.counter)
        
        // Dispatch RestoreStateAction
        val restoredState = TestState(counter = 50, message = "multi-restore")
        store.dispatch(RestoreStateAction(restoredState))
        
        // Wait for action to be processed
        runCurrent()
        advanceUntilIdle()
        
        // Verify state was restored
        assertEquals(50, store.state.value.counter)
        
        // Verify composed reducers still work
        store.dispatch(IncrementAction(3))
        runCurrent()
        advanceUntilIdle()
        
        // First reducer adds 3 (50 + 3 = 53), then second reducer doubles (53 * 2 = 106)
        assertEquals(106, store.state.value.counter)
    }
    
    // === Tests from RestoreNoPersistTest ===
    
    @Test
    fun `should not persist state immediately after restoration`() = runTest(timeout = 5.seconds) {
        val storage = InMemoryStorage<TestState>()
        val testableStorage = storage.testable()
        
        // Pre-save a state
        val savedState = TestState(counter = 100, message = "Saved state")
        testableStorage.save(savedState)
        runCurrent()
        
        // Reset save counter
        testableStorage.resetCounters()
        
        // Create store with persistence - should restore
        val store = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = testableStorage,
                    strategy = PersistenceStrategy.Debounced(1000) // 1 second debounce
                )
            }
        }
        
        // Wait for restoration
        runCurrent()
        advanceUntilIdle()
        
        // Verify state was restored
        assertEquals(100, store.state.value.counter)
        assertEquals("Saved state", store.state.value.message)
        
        // Verify load was called
        assertEquals(1, testableStorage.state.value.loadCount)
        
        // Now advance time to after debounce period
        advanceTimeBy(1500) // 1.5 seconds
        runCurrent()
        advanceUntilIdle()
        
        // Verify no save occurred just from restoration
        assertEquals(0, testableStorage.state.value.saveCount, 
            "State should not be persisted after restoration")
    }
    
    @Test
    fun `should persist state only after actual changes post-restoration`() = runTest(timeout = 5.seconds) {
        val storage = InMemoryStorage<TestState>()
        val testableStorage = storage.testable()
        
        // Pre-save a state
        val savedState = TestState(counter = 50, message = "Initial saved")
        testableStorage.save(savedState)
        runCurrent()
        
        // Reset counters
        testableStorage.resetCounters()
        
        // Create store with persistence
        val store = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = testableStorage,
                    strategy = PersistenceStrategy.Debounced(500)
                )
            }
        }
        
        // Wait for restoration
        runCurrent()
        advanceUntilIdle()
        
        // Verify restoration
        assertEquals(50, store.state.value.counter)
        assertEquals(1, testableStorage.state.value.loadCount)
        assertEquals(0, testableStorage.state.value.saveCount)
        
        // Advance time past debounce without any actions
        advanceTimeBy(1000)
        runCurrent()
        advanceUntilIdle()
        
        // Still no save
        assertEquals(0, testableStorage.state.value.saveCount)
        
        // Now dispatch an action that changes state
        store.dispatch(IncrementAction(10))
        runCurrent()
        
        // Advance past debounce
        advanceTimeBy(600)
        runCurrent()
        advanceUntilIdle()
        
        // Now it should have saved
        assertEquals(1, testableStorage.state.value.saveCount, 
            "State should be persisted after actual change")
        
        // Verify the saved state
        val persisted = storage.load()!!
        assertEquals(60, persisted.counter) // 50 + 10
    }
    
    @Test
    fun `should handle multiple rapid restorations correctly`() = runTest(timeout = 5.seconds) {
        val storage = InMemoryStorage<TestState>()
        val testableStorage = storage.testable()
        
        // Pre-save a state
        val savedState = TestState(counter = 200, message = "Multi-restore test")
        testableStorage.save(savedState)
        runCurrent()
        
        // Create first store
        val store1 = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = testableStorage,
                    strategy = PersistenceStrategy.OnEveryChange
                )
            }
        }
        
        // Wait for first restoration
        runCurrent()
        advanceUntilIdle()
        
        val saveCountAfterFirstRestore = testableStorage.state.value.saveCount
        
        // Create second store immediately
        val store2 = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            middleware {
                persistence(
                    storage = testableStorage,
                    strategy = PersistenceStrategy.OnEveryChange
                )
            }
        }
        
        // Wait for second restoration
        runCurrent()
        advanceUntilIdle()
        
        // Both stores should have restored state
        assertEquals(200, store1.state.value.counter)
        assertEquals(200, store2.state.value.counter)
        
        // No additional saves should have occurred from restorations
        assertEquals(saveCountAfterFirstRestore, testableStorage.state.value.saveCount,
            "No saves should occur from restorations")
    }
    
    // === Tests from StorageLifecycleTest ===
    
    /**
     * Test middleware that tracks storage lifecycle events
     */
    class LifecycleTrackingMiddleware<TState : StateModel> : Middleware<TState>, StoreLifecycleAware<TState> {
        val events = mutableListOf<String>()
        var store: KStore<TState>? = null
        var storageStartedCalled = false
        var storageCompletedCalled = false
        var storageRestored = false
        
        override suspend fun invoke(store: KStore<TState>, next: suspend (Action) -> Action, action: Action): Action {
            events.add("action:${action::class.simpleName}")
            return next(action)
        }
        
        override suspend fun onStoreCreated(store: KStore<TState>) {
            events.add("onStoreCreated")
            this.store = store
        }
        
        override suspend fun onStorageRestorationStarted() {
            events.add("onStorageRestorationStarted")
            storageStartedCalled = true
        }
        
        override suspend fun onStorageRestorationCompleted(restored: Boolean) {
            events.add("onStorageRestorationCompleted:$restored")
            storageCompletedCalled = true
            storageRestored = restored
        }
        
        override suspend fun onStoreDestroyed() {
            events.add("onStoreDestroyed")
        }
    }

    @Test
    fun `should notify middleware of successful storage restoration`() = runTest(timeout = 5.seconds) {
        // Create storage with pre-existing state
        val storage = InMemoryStorage<TestState>()
        val savedState = TestState(counter = 42, message = "Restored")
        storage.save(savedState)
        
        val trackingMiddleware = LifecycleTrackingMiddleware<TestState>()
        
        val store = createStoreForTest(TestState()) {
            middleware {
                // Add tracking middleware first so it gets lifecycle events
                middleware(trackingMiddleware)
                lifecycleAware(trackingMiddleware)
                
                // Add persistence middleware
                persistence(
                    storage = storage,
                    strategy = PersistenceStrategy.OnEveryChange
                )
            }
            
            reduceWith(::testReducer)
        }
        
        // Wait for restoration to complete
        runCurrent()
        advanceUntilIdle()
        
        // Verify lifecycle callbacks were made
        assertTrue(trackingMiddleware.storageStartedCalled, "onStorageRestorationStarted should be called")
        assertTrue(trackingMiddleware.storageCompletedCalled, "onStorageRestorationCompleted should be called")
        assertTrue(trackingMiddleware.storageRestored, "Storage should be marked as restored")
        
        // Verify the order of events
        val lifecycleEvents = trackingMiddleware.events.filter { 
            it.startsWith("on") || it.contains("RestoreStateAction")
        }
        
        assertTrue(lifecycleEvents.contains("onStoreCreated"), "Should have onStoreCreated event")
        assertTrue(lifecycleEvents.contains("onStorageRestorationStarted"), "Should have onStorageRestorationStarted event")
        assertTrue(lifecycleEvents.contains("action:RestoreStateAction"), "Should have RestoreStateAction event")
        assertTrue(lifecycleEvents.contains("onStorageRestorationCompleted:true"), "Should have onStorageRestorationCompleted:true event")
        
        // Verify state was restored
        assertEquals(42, store.state.value.counter)
        assertEquals("Restored", store.state.value.message)
    }

    @Test
    fun `should notify middleware when no storage to restore`() = runTest(timeout = 5.seconds) {
        // Create empty storage
        val storage = InMemoryStorage<TestState>()
        
        val trackingMiddleware = LifecycleTrackingMiddleware<TestState>()
        
        val store = createStoreForTest(TestState()) {
            middleware {
                // Add tracking middleware first
                middleware(trackingMiddleware)
                lifecycleAware(trackingMiddleware)
                
                // Add persistence middleware
                persistence(
                    storage = storage,
                    strategy = PersistenceStrategy.OnEveryChange
                )
            }
            
            reduceWith(::testReducer)
        }
        
        // Wait for restoration to complete
        runCurrent()
        advanceUntilIdle()
        
        // Verify lifecycle callbacks were made
        assertTrue(trackingMiddleware.storageStartedCalled, "onStorageRestorationStarted should be called")
        assertTrue(trackingMiddleware.storageCompletedCalled, "onStorageRestorationCompleted should be called")
        assertFalse(trackingMiddleware.storageRestored, "Storage should NOT be marked as restored")
        
        // Verify the order of events (no RestoreStateAction when nothing to restore)
        val lifecycleEvents = trackingMiddleware.events.filter { it.startsWith("on") }
        assertEquals("onStoreCreated", lifecycleEvents[0])
        assertEquals("onStorageRestorationStarted", lifecycleEvents[1])
        assertEquals("onStorageRestorationCompleted:false", lifecycleEvents[2])
        
        // Verify state remains initial
        assertEquals(0, store.state.value.counter)
    }

    @Test
    fun `should notify middleware when storage restoration fails`() = runTest(timeout = 5.seconds) {
        // Create storage that throws on load
        val storage = object : StateStorage<TestState> {
            override suspend fun save(state: TestState) {
                // Not used in this test
            }
            
            override suspend fun load(): TestState? {
                throw RuntimeException("Storage failure")
            }
            
            override suspend fun clear() {
                // Not used in this test
            }
            
            override suspend fun exists(): Boolean = false
        }
        
        val trackingMiddleware = LifecycleTrackingMiddleware<TestState>()
        var errorHandled = false
        
        val store = createStoreForTest(TestState()) {
            middleware {
                // Add tracking middleware first
                middleware(trackingMiddleware)
                lifecycleAware(trackingMiddleware)
                
                // Add persistence middleware with error handler
                persistence(
                    storage = storage,
                    strategy = PersistenceStrategy.OnEveryChange,
                    errorHandler = { errorHandled = true }
                )
            }
            
            reduceWith(::testReducer)
        }
        
        // Wait for restoration to complete
        runCurrent()
        advanceUntilIdle()
        
        // Verify error was handled
        assertTrue(errorHandled, "Error handler should be called")
        
        // Verify lifecycle callbacks were made
        assertTrue(trackingMiddleware.storageStartedCalled, "onStorageRestorationStarted should be called")
        assertTrue(trackingMiddleware.storageCompletedCalled, "onStorageRestorationCompleted should be called")
        assertFalse(trackingMiddleware.storageRestored, "Storage should NOT be marked as restored on failure")
        
        // Verify the order of events
        val lifecycleEvents = trackingMiddleware.events.filter { it.startsWith("on") }
        assertEquals("onStoreCreated", lifecycleEvents[0])
        assertEquals("onStorageRestorationStarted", lifecycleEvents[1])
        assertEquals("onStorageRestorationCompleted:false", lifecycleEvents[2])
        
        // Verify state remains initial
        assertEquals(0, store.state.value.counter)
    }
}