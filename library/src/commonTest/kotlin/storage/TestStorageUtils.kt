package duks.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.withTimeout

/**
 * Wrapper that adds testing hooks to any StateStorage implementation.
 * This allows tests to observe storage operations without affecting production code.
 */
class TestableStorageWrapper<TState>(
    private val wrapped: StateStorage<TState>
) : StateStorage<TState> {
    private var _saveCount = 0
    private var _loadCount = 0
    
    /**
     * Called after a save operation completes successfully
     */
    var onSaveComplete: (() -> Unit)? = null
    
    /**
     * Called after a load operation completes
     */
    var onLoadComplete: (() -> Unit)? = null
    
    /**
     * Returns the number of times save has been called
     */
    val saveCount: Int get() = _saveCount
    
    /**
     * Returns the number of times load has been called
     */
    val loadCount: Int get() = _loadCount
    
    override suspend fun save(state: TState) {
        wrapped.save(state)
        _saveCount++
        onSaveComplete?.invoke()
    }
    
    override suspend fun load(): TState? {
        val result = wrapped.load()
        _loadCount++
        onLoadComplete?.invoke()
        return result
    }
    
    override suspend fun clear() {
        wrapped.clear()
    }
    
    override suspend fun exists(): Boolean = wrapped.exists()
    
    /**
     * Resets the test counters
     */
    fun resetCounters() {
        _saveCount = 0
        _loadCount = 0
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
    val saveCompleted = CompletableDeferred<Unit>()
    val previousCallback = storage.onSaveComplete
    
    storage.onSaveComplete = {
        saveCompleted.complete(Unit)
        previousCallback?.invoke()
    }
    
    try {
        block()
        // Run all pending coroutines immediately
        runCurrent()
        // Advance time slightly to allow any delayed tasks to start
        advanceTimeBy(10)
        runCurrent()
        // Wait for the save to complete with a longer timeout
        withTimeout(5000) {
            while (!saveCompleted.isCompleted) {
                runCurrent()
                if (!saveCompleted.isCompleted) {
                    advanceTimeBy(10)
                }
            }
        }
    } finally {
        storage.onSaveComplete = previousCallback
    }
}

/**
 * Waits for a specific number of saves to complete
 */
suspend fun <TState> TestScope.waitForSaves(
    storage: TestableStorageWrapper<TState>,
    count: Int,
    block: suspend () -> Unit
) {
    val initialCount = storage.saveCount
    block()
    
    // Keep processing coroutines until we reach the expected save count
    while (storage.saveCount < initialCount + count) {
        runCurrent()
    }
}

/**
 * Extension to check if storage has saved a specific number of times
 */
fun <TState> TestableStorageWrapper<TState>.hasSavedTimes(expected: Int): Boolean =
    saveCount == expected

/**
 * Runs a block and waits for all pending coroutines to complete
 */
suspend fun TestScope.runAndWait(block: suspend () -> Unit) {
    block()
    runCurrent()
}