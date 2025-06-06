package duks.storage

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Composite storage that delegates to multiple storage implementations.
 * Useful for scenarios where you want to persist to multiple targets (e.g., local and remote).
 * 
 * @param storages List of storage implementations to delegate to
 */
class CompositeStorage<TState>(
    private val storages: List<StateStorage<TState>>
) : StateStorage<TState> {
    
    override suspend fun save(state: TState) {
        coroutineScope {
            storages.map { storage ->
                async { storage.save(state) }
            }.awaitAll()
        }
    }
    
    override suspend fun load(): TState? {
        // Return the first successfully loaded data
        return storages.firstNotNullOfOrNull { 
            try {
                it.load()
            } catch (e: Exception) {
                null
            }
        }
    }
    
    override suspend fun clear() {
        coroutineScope {
            storages.map { storage ->
                async { storage.clear() }
            }.awaitAll()
        }
    }
    
    override suspend fun exists(): Boolean {
        // Check if any storage has data
        return storages.any { 
            try {
                it.exists()
            } catch (e: Exception) {
                false
            }
        }
    }
}