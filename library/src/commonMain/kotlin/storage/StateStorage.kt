package duks.storage

/**
 * Storage abstraction for persisting state data in any format
 * @param TState The type of state being stored
 */
interface StateStorage<TState> {
    /**
     * Save data to storage
     * @param state The data to save in the specified format
     */
    suspend fun save(state: TState)
    
    /**
     * Load data from storage
     * @return The loaded data in the specified format, or null if no data exists
     */
    suspend fun load(): TState?
    
    /**
     * Clear all stored data
     */
    suspend fun clear()
    
    /**
     * Check if stored data exists
     * @return true if data exists, false otherwise
     */
    suspend fun exists(): Boolean
}