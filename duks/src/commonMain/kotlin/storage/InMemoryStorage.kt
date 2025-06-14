package duks.storage

/**
 * In-memory implementation of StateStorage for testing and simple use cases.
 * Data is stored in memory and will be lost when the application restarts.
 * 
 * @param TState The type of state being stored
 */
class InMemoryStorage<TState> : StateStorage<TState> {
    private var data: TState? = null
    
    override suspend fun save(state: TState) {
        this.data = state
    }
    
    override suspend fun load(): TState? {
        return data
    }
    
    override suspend fun clear() {
        data = null
    }
    
    override suspend fun exists(): Boolean = data != null
}