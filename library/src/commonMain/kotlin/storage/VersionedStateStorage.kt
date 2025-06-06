package duks.storage

/**
 * Extended storage interface that supports versioning for migration support
 * @param TState The type of state being stored
 */
interface VersionedStateStorage<TState> : StateStorage<TState> {
    /**
     * Save data with a version number
     * @param state The state to save
     * @param version The version number
     */
    suspend fun saveWithVersion(state: TState, version: Int)
    
    /**
     * Load data with its version number
     * @return A pair of state and version, or null if no data exists
     */
    suspend fun loadWithVersion(): Pair<TState, Int>?
}