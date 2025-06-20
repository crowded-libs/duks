package duks.storage

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

data class TestStorageState(val saveCount: Int = 0, val loadCount: Int = 0)
/**
 * Wrapper that adds testing hooks to any StateStorage implementation.
 * This allows tests to observe storage operations without affecting production code.
 */
class TestableStorageWrapper<TState>(
    private val wrapped: StateStorage<TState>
) : StateStorage<TState> {

    private val stateFlow = MutableStateFlow(TestStorageState())
    val state: StateFlow<TestStorageState> = stateFlow

    override suspend fun save(state: TState) {
        wrapped.save(state)
        stateFlow.value = stateFlow.value.copy(saveCount = stateFlow.value.saveCount + 1)
    }
    
    override suspend fun load(): TState? {
        val result = wrapped.load()
        stateFlow.value = stateFlow.value.copy(loadCount = stateFlow.value.loadCount + 1)
        return result
    }
    
    override suspend fun clear() {
        stateFlow.value = TestStorageState()
        wrapped.clear()
    }
    
    override suspend fun exists(): Boolean = wrapped.exists()
    
    /**
     * Resets the test counters
     */
    fun resetCounters() {
        stateFlow.value = TestStorageState()
    }
}

/**
 * Creates a testable in-memory storage
 */
fun <TState> createTestableStorage(): TestableStorageWrapper<TState> = 
    TestableStorageWrapper(InMemoryStorage())

/**
 * Wraps any storage with testable functionality
 */
fun <TState> StateStorage<TState>.testable(): TestableStorageWrapper<TState> =
    TestableStorageWrapper(this)

/**
 * Waits for a storage save operation to complete
 */
suspend fun <TState> TestScope.waitForSave(
    storage: TestableStorageWrapper<TState>,
    block: suspend () -> Unit
) {
    val saveCountBefore = storage.state.value.saveCount
    block()
    var attempts = 0
    while (storage.state.value.saveCount == saveCountBefore && attempts < 500) {
        delay(10)
        runCurrent()
        attempts++
    }
    
    if (storage.state.value.saveCount == saveCountBefore) {
        throw AssertionError("Storage save did not complete within timeout")
    }
}