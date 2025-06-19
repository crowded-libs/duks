package duks.storage

import duks.*
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class SagaPersistenceTest {
    
    @Serializable
    data class TestSagaState(
        val id: String,
        val counter: Int = 0,
        val status: String = "active"
    )
    
    data class TestAppState(
        val activeSagas: Int = 0
    ) : StateModel
    
    // Test actions
    data class StartTestSaga(val sagaId: String) : Action
    data class UpdateTestSaga(val sagaId: String, val increment: Int) : Action
    data class CompleteTestSaga(val sagaId: String) : Action
    
    // Simple JSON serializer for testing
    class TestSagaSerializer : SagaStateSerializer {
        private val json = Json { 
            ignoreUnknownKeys = true
            isLenient = true
        }
        
        override fun serialize(state: Any): String {
            return when (state) {
                is TestSagaState -> json.encodeToString(state)
                else -> throw IllegalArgumentException("Unknown saga state type")
            }
        }
        
        override fun deserialize(data: String, sagaName: String): Any {
            return when (sagaName) {
                "TestSaga" -> json.decodeFromString<TestSagaState>(data)
                else -> throw IllegalArgumentException("Unknown saga name: $sagaName")
            }
        }
    }
    
    @Test
    fun `should persist and restore saga instances`() = runTest(timeout = 5.seconds) {
        // Create in-memory storage
        val sagaStorage = InMemorySagaStorage()
        
        val store = createStoreForTest(TestAppState()) {
            middleware {
                // Add sagas with integrated persistence
                sagas(
                    storage = sagaStorage,
                    persistenceStrategy = SagaPersistenceStrategy.OnEveryChange
                ) {
                    saga<TestSagaState>(
                        name = "TestSaga",
                        initialState = { TestSagaState("") }
                    ) {
                        startsOn<StartTestSaga> { action ->
                            SagaTransition.Continue(
                                TestSagaState(
                                    id = action.sagaId,
                                    counter = 0,
                                    status = "active"
                                )
                            )
                        }
                        
                        on<UpdateTestSaga>(
                            condition = { action, state -> action.sagaId == state.id }
                        ) { action, state ->
                            SagaTransition.Continue(
                                state.copy(counter = state.counter + action.increment)
                            )
                        }
                        
                        on<CompleteTestSaga>(
                            condition = { action, state -> action.sagaId == state.id }
                        ) { action, state ->
                            SagaTransition.Complete()
                        }
                    }
                }
            }
            
            reduceWith { state, _ -> state }
        }
        
        // Start a saga
        dispatchAndAdvance(store, StartTestSaga("saga-1"))
        advanceTimeBy(100)
        runCurrent()
        
        // Wait a bit for async operations
        advanceTimeBy(100)
        runCurrent()
        
        // Verify saga was persisted by checking for its ID
        val allSagaIds = sagaStorage.getAllSagaIds()
        assertTrue(allSagaIds.isNotEmpty(), "Should have active sagas after persistence")
        
        // Get the actual saga ID (it includes timestamp)
        val actualSagaId = allSagaIds.first()
        val saga = sagaStorage.load(actualSagaId)
        assertNotNull(saga, "Should be able to load the saga")
        val sagaState = saga.state as TestSagaState
        assertEquals("saga-1", sagaState.id, "Saga state ID should match")
        assertEquals(0, sagaState.counter, "Initial counter should be 0")
        
        // Update the saga
        dispatchAndAdvance(store, UpdateTestSaga("saga-1", 5))
        advanceTimeBy(100)
        runCurrent()
        
        // Verify update was persisted
        val updatedSaga = sagaStorage.load(actualSagaId)
        assertNotNull(updatedSaga)
        val updatedState = updatedSaga.state as TestSagaState
        assertEquals(5, updatedState.counter, "Counter should be updated to 5")
        
        // Complete the saga
        dispatchAndAdvance(store, CompleteTestSaga("saga-1"))
        advanceTimeBy(100)
        runCurrent()
        
        // Verify saga was removed
        val finalSagaIds = sagaStorage.getAllSagaIds()
        assertTrue(finalSagaIds.isEmpty(), "Completed saga should be removed")
        assertNull(sagaStorage.load(actualSagaId), "Should not be able to load completed saga")
    }
    
    @Test
    fun `should restore sagas on store initialization`() = runTest(timeout = 5.seconds) {
        // Create storage with serializer
        val storageMap = mutableMapOf<String, InMemoryStorage<PersistedSagaInstance>>()
        val keysStorage = InMemoryStorage<Set<String>>()
        
        val sagaStorage = KeyBasedSagaStorage(
            createStorage = { key -> 
                storageMap.getOrPut(key) { InMemoryStorage() }
            },
            serializer = TestSagaSerializer(),
            sagaKeysStorage = keysStorage
        )
        
        // First store - start some sagas
        val store1 = createStoreForTest(TestAppState()) {
            middleware {
                sagas(
                    storage = sagaStorage,
                    persistenceStrategy = SagaPersistenceStrategy.OnEveryChange
                ) {
                    saga<TestSagaState>(
                        name = "TestSaga",
                        initialState = { TestSagaState("") }
                    ) {
                        startsOn<StartTestSaga> { action ->
                            SagaTransition.Continue(
                                TestSagaState(
                                    id = action.sagaId,
                                    counter = 0,
                                    status = "active"
                                )
                            )
                        }
                        
                        on<UpdateTestSaga>(
                            condition = { action, state -> action.sagaId == state.id }
                        ) { action, state ->
                            SagaTransition.Continue(
                                state.copy(counter = state.counter + action.increment)
                            )
                        }
                    }
                }
            }
            
            reduceWith { state, _ -> state }
        }
        
        // Start and update sagas
        dispatchAndAdvance(store1, StartTestSaga("saga-1"))
        dispatchAndAdvance(store1, UpdateTestSaga("saga-1", 10))
        dispatchAndAdvance(store1, StartTestSaga("saga-2"))
        dispatchAndAdvance(store1, UpdateTestSaga("saga-2", 20))
        
        advanceTimeBy(500)
        runCurrent()
        
        // Verify sagas were persisted
        val allSagaIds = sagaStorage.getAllSagaIds()
        assertEquals(2, allSagaIds.size, "Should have 2 active sagas")
        
        // Create second store - should restore sagas
        val store2 = createStoreForTest(TestAppState()) {
            middleware {
                sagas(
                    storage = sagaStorage,
                    persistenceStrategy = SagaPersistenceStrategy.OnEveryChange
                ) {
                    saga<TestSagaState>(
                        name = "TestSaga",
                        initialState = { TestSagaState("") }
                    ) {
                        startsOn<StartTestSaga> { action ->
                            SagaTransition.Continue(
                                TestSagaState(
                                    id = action.sagaId,
                                    counter = 0,
                                    status = "active"
                                )
                            )
                        }
                        
                        on<UpdateTestSaga>(
                            condition = { action, state -> action.sagaId == state.id }
                        ) { action, state ->
                            SagaTransition.Continue(
                                state.copy(counter = state.counter + action.increment)
                            )
                        }
                    }
                }
            }
            
            reduceWith { state, _ -> state }
        }
        
        // Wait for restoration to complete
        advanceTimeBy(100)
        runCurrent()
        
        // Verify sagas were restored
        val restoredSagaIds = sagaStorage.getAllSagaIds()
        assertEquals(2, restoredSagaIds.size, "Should restore 2 sagas")
        
        // Test that restored sagas can still handle actions
        dispatchAndAdvance(store2, UpdateTestSaga("saga-1", 5))
        advanceTimeBy(100)
        runCurrent()
        
        // Find the saga with state id "saga-1" and verify it was updated
        var foundSaga1 = false
        for (sagaId in restoredSagaIds) {
            val saga = sagaStorage.load(sagaId)
            if (saga != null) {
                val state = saga.state as? TestSagaState
                if (state?.id == "saga-1") {
                    assertEquals(15, state.counter, "Saga should have counter=15 (10+5)")
                    foundSaga1 = true
                    break
                }
            }
        }
        assertTrue(foundSaga1, "Should find and update saga-1")
    }
    
    @Test
    fun `should support different persistence strategies`() = runTest(timeout = 5.seconds) {
        // Test that different persistence strategies can be configured
        val sagaStorage = InMemorySagaStorage()
        
        // Test OnEveryChange strategy
        val store1 = createStoreForTest(TestAppState()) {
            middleware {
                sagas(
                    storage = sagaStorage,
                    persistenceStrategy = SagaPersistenceStrategy.OnEveryChange
                ) {
                    saga<TestSagaState>(
                        name = "TestSaga",
                        initialState = { TestSagaState("") }
                    ) {
                        startsOn<StartTestSaga> { action ->
                            SagaTransition.Continue(TestSagaState(id = action.sagaId, counter = 0))
                        }
                        
                        on<UpdateTestSaga>(
                            condition = { action, state -> action.sagaId == state.id }
                        ) { action, state ->
                            SagaTransition.Continue(
                                state.copy(counter = state.counter + action.increment)
                            )
                        }
                    }
                }
            }
            
            reduceWith { state, _ -> state }
        }
        
        // Start saga and make updates with immediate persistence
        dispatchAndAdvance(store1, StartTestSaga("saga-1"))
        dispatchAndAdvance(store1, UpdateTestSaga("saga-1", 3))
        
        // Wait for async operations
        advanceTimeBy(100)
        runCurrent()
        
        // Verify saga was persisted
        val allSagaIds = sagaStorage.getAllSagaIds()
        assertEquals(1, allSagaIds.size, "Should have 1 active saga")
        
        val saga = sagaStorage.load(allSagaIds.first())
        assertNotNull(saga)
        val state = saga.state as TestSagaState
        assertEquals("saga-1", state.id, "Saga state ID should be saga-1")
        assertEquals(3, state.counter, "Saga state should have counter = 3")
        
        // Test that other strategies can be configured
        val strategies = listOf(
            SagaPersistenceStrategy.Debounced(200),
            SagaPersistenceStrategy.OnCheckpoint,
            SagaPersistenceStrategy.OnCompletion,
            SagaPersistenceStrategy.Combined(listOf(
                SagaPersistenceStrategy.OnEveryChange,
                SagaPersistenceStrategy.OnCheckpoint
            ))
        )
        
        // Verify all strategies can be configured without errors
        for (strategy in strategies) {
            val testStore = createStoreForTest(TestAppState()) {
                middleware {
                    sagas(
                        storage = InMemorySagaStorage(),
                        persistenceStrategy = strategy
                    ) {
                        saga<TestSagaState>(
                            name = "TestSaga",
                            initialState = { TestSagaState("") }
                        ) {
                            startsOn<StartTestSaga> { action ->
                                SagaTransition.Continue(TestSagaState(id = action.sagaId))
                            }
                        }
                    }
                }
                reduceWith { state, _ -> state }
            }
            assertNotNull(testStore, "Store with strategy $strategy should be created successfully")
        }
    }
}