package duks.storage

import duks.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlinx.serialization.Serializable
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class CombinedPersistenceStrategyTest {
    
    @Serializable
    data class TestState(
        val counter: Int = 0,
        val lastAction: String = ""
    ) : StateModel
    
    data class UpdateAction(val value: Int) : Action
    object SaveTriggerAction : Action
    
    private fun testReducer(state: TestState, action: Action): TestState = when (action) {
        is UpdateAction -> state.copy(counter = action.value, lastAction = "update")
        SaveTriggerAction -> state.copy(lastAction = "save")
        else -> state
    }
    
    @BeforeTest
    fun setup() {
        TestFileSystem.instance.clear()
    }
    
    @Test
    fun `should persist with combined debounce and onAction strategies`() = runTest(timeout = 5.seconds) {
        var saveCount = 0
        val storage = InMemoryStorage<TestState>()
        
        // Wrap to count saves
        val countingStorage = object : StateStorage<TestState> {
            override suspend fun save(state: TestState) {
                saveCount++
                storage.save(state)
            }
            override suspend fun load(): TestState? = storage.load()
            override suspend fun clear() = storage.clear()
            override suspend fun exists(): Boolean = storage.exists()
        }
        
        val store = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            
            middleware {
                persistence(
                    storage = countingStorage,
                    strategy = PersistenceStrategy.Combined(listOf(
                        PersistenceStrategy.Debounced(delayMs = 100),
                        PersistenceStrategy.OnAction(setOf(SaveTriggerAction::class))
                    ))
                )
            }
        }
        
        // Test 1: OnAction strategy triggers save
        store.dispatch(SaveTriggerAction)
        runCurrent()
        delay(10) // Small delay to ensure save happens
        assertEquals(1, saveCount, "Should save immediately on SaveTriggerAction")
        
        // Test 2: Multiple updates within debounce window
        store.dispatch(UpdateAction(1))
        store.dispatch(UpdateAction(2))
        store.dispatch(UpdateAction(3))
        runCurrent()
        
        // Should not save yet (within debounce window)
        assertEquals(1, saveCount, "Should not save during debounce window")
        
        // Wait for debounce
        delay(150)
        assertEquals(2, saveCount, "Should save after debounce period")
        
        // Test 3: OnAction during debounce cancels debounce and saves immediately
        store.dispatch(UpdateAction(4))
        delay(50) // Halfway through debounce
        store.dispatch(SaveTriggerAction)
        runCurrent()
        delay(10)
        
        assertEquals(3, saveCount, "OnAction should trigger save during debounce")
        
        // Verify final state was saved
        val loadedState = countingStorage.load()
        assertNotNull(loadedState)
        assertEquals("save", loadedState.lastAction)
    }
    
    @Test
    fun `should persist on every change when combined with onEveryChange strategy`() = runTest {
        var saveCount = 0
        val storage = InMemoryStorage<TestState>()
        
        val countingStorage = object : StateStorage<TestState> {
            override suspend fun save(state: TestState) {
                saveCount++
                storage.save(state)
            }
            override suspend fun load(): TestState? = storage.load()
            override suspend fun clear() = storage.clear()
            override suspend fun exists(): Boolean = storage.exists()
        }
        
        val store = createStoreForTest(TestState()) {
            reduceWith(::testReducer)
            
            middleware {
                persistence(
                    storage = countingStorage,
                    strategy = PersistenceStrategy.Combined(listOf(
                        PersistenceStrategy.Debounced(delayMs = 1000), // Long debounce
                        PersistenceStrategy.OnEveryChange
                    ))
                )
            }
        }
        
        // Each dispatch should save immediately due to OnEveryChange
        store.dispatch(UpdateAction(1))
        runCurrent()
        delay(10)
        assertEquals(1, saveCount)
        
        store.dispatch(UpdateAction(2))
        runCurrent()
        delay(10)
        assertEquals(2, saveCount)
        
        store.dispatch(UpdateAction(3))
        runCurrent()
        delay(10)
        assertEquals(3, saveCount, "OnEveryChange in combined strategy should save every change")
    }
}