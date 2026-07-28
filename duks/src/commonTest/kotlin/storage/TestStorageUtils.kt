package duks.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent

data class TestStorageState(val saveCount: Int = 0, val loadCount: Int = 0)

/**
 * Wrapper that adds testing hooks to any [StateStorage] implementation.
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

    fun resetCounters() {
        stateFlow.value = TestStorageState()
    }
}

/**
 * Creates a testable in-memory storage.
 */
fun <TState> createTestableStorage(): TestableStorageWrapper<TState> =
    TestableStorageWrapper(InMemoryStorage())

/**
 * Wraps any storage with testable functionality.
 */
fun <TState> StateStorage<TState>.testable(): TestableStorageWrapper<TState> =
    TestableStorageWrapper(this)

/**
 * Runs [block], then pumps the test scheduler until [TestableStorageWrapper] records
 * a new save (saveCount increases).
 *
 * Uses virtual time / scheduler pumps only — no wall-clock [kotlinx.coroutines.delay].
 */
suspend fun <TState> TestScope.waitForSave(
    storage: TestableStorageWrapper<TState>,
    maxPumps: Int = 1_000,
    block: suspend () -> Unit
) {
    val saveCountBefore = storage.state.value.saveCount
    block()

    if (storage.state.value.saveCount > saveCountBefore) {
        return
    }

    var done = false
    val collectJob = backgroundScope.launch {
        storage.state.first { it.saveCount > saveCountBefore }
        done = true
    }

    var pumps = 0
    while (collectJob.isActive && pumps < maxPumps) {
        runCurrent()
        advanceUntilIdle()
        pumps++
    }

    if (collectJob.isActive) {
        collectJob.cancel()
        throw AssertionError(
            "Storage save did not complete after $maxPumps pumps " +
                "(saveCount still ${storage.state.value.saveCount}, expected > $saveCountBefore)"
        )
    }
    if (!done && storage.state.value.saveCount <= saveCountBefore) {
        throw AssertionError(
            "Storage save did not complete (saveCount=${storage.state.value.saveCount})"
        )
    }
}
