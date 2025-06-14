package duks.storage

import duks.SagaInstance

/**
 * Storage interface specifically for saga instances.
 * Each saga instance is stored individually by its ID as the key.
 */
interface SagaStorage {
    /**
     * Save a saga instance to storage using its ID as the key.
     */
    suspend fun save(sagaId: String, instance: SagaInstance<*>)
    
    /**
     * Load a specific saga instance by ID.
     * Returns null if the saga doesn't exist or has been completed/removed.
     */
    suspend fun load(sagaId: String): SagaInstance<*>?
    
    /**
     * Remove a saga instance from storage (typically when completed).
     */
    suspend fun remove(sagaId: String)
    
    /**
     * Get all active saga IDs (for restoration on startup).
     */
    suspend fun getAllSagaIds(): Set<String>
}

/**
 * Metadata stored alongside saga instances for recovery and management.
 */
data class PersistedSagaInstance(
    val id: String,
    val sagaName: String,
    val state: String, // JSON or other serialized format
    val startedAt: Long,
    val lastUpdatedAt: Long,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * In-memory implementation of SagaStorage for testing and development.
 */
class InMemorySagaStorage : SagaStorage {
    private val sagas = mutableMapOf<String, SagaInstance<*>>()
    
    override suspend fun save(sagaId: String, instance: SagaInstance<*>) {
        sagas[sagaId] = instance
    }
    
    override suspend fun load(sagaId: String): SagaInstance<*>? {
        return sagas[sagaId]
    }
    
    override suspend fun remove(sagaId: String) {
        sagas.remove(sagaId)
    }
    
    override suspend fun getAllSagaIds(): Set<String> {
        return sagas.keys.toSet()
    }
}

/**
 * Storage implementation that uses individual StateStorage instances for each saga.
 * Each saga is stored with its ID as the key.
 */
class KeyBasedSagaStorage(
    private val createStorage: (key: String) -> StateStorage<PersistedSagaInstance>,
    private val serializer: SagaStateSerializer,
    private val sagaKeysStorage: StateStorage<Set<String>> // Track active saga keys
) : SagaStorage {
    
    override suspend fun save(sagaId: String, instance: SagaInstance<*>) {
        val storage = createStorage(sagaId)
        val persisted = PersistedSagaInstance(
            id = instance.id,
            sagaName = instance.sagaName,
            state = serializer.serialize(instance.state as Any),
            startedAt = instance.startedAt,
            lastUpdatedAt = instance.lastUpdatedAt
        )
        storage.save(persisted)
        
        // Update the set of active saga IDs
        val currentKeys = sagaKeysStorage.load() ?: emptySet()
        sagaKeysStorage.save(currentKeys + sagaId)
    }
    
    override suspend fun load(sagaId: String): SagaInstance<*>? {
        val storage = createStorage(sagaId)
        val persisted = storage.load() ?: return null
        return deserializeSagaInstance(persisted)
    }
    
    override suspend fun remove(sagaId: String) {
        val storage = createStorage(sagaId)
        storage.clear()
        
        // Remove from active saga IDs
        val currentKeys = sagaKeysStorage.load() ?: emptySet()
        sagaKeysStorage.save(currentKeys - sagaId)
    }
    
    override suspend fun getAllSagaIds(): Set<String> {
        return sagaKeysStorage.load() ?: emptySet()
    }
    
    private fun deserializeSagaInstance(persisted: PersistedSagaInstance): SagaInstance<*> {
        val state = serializer.deserialize(persisted.state, persisted.sagaName)
        return SagaInstance(
            id = persisted.id,
            sagaName = persisted.sagaName,
            state = state,
            startedAt = persisted.startedAt,
            lastUpdatedAt = persisted.lastUpdatedAt
        )
    }
}

/**
 * Interface for serializing and deserializing saga state.
 */
interface SagaStateSerializer {
    /**
     * Serialize saga state to a string representation.
     */
    fun serialize(state: Any): String
    
    /**
     * Deserialize saga state from string representation.
     * The sagaName helps identify the correct type for deserialization.
     */
    fun deserialize(data: String, sagaName: String): Any
}

/**
 * Persistence strategies specific to sagas.
 */
sealed class SagaPersistenceStrategy {
    /**
     * Persist on every saga lifecycle event (start, update, complete).
     */
    data object OnEveryChange : SagaPersistenceStrategy()
    
    /**
     * Persist with debouncing to avoid excessive writes.
     */
    data class Debounced(val delayMs: Long) : SagaPersistenceStrategy()
    
    /**
     * Persist only at specific checkpoints defined by the saga.
     */
    data object OnCheckpoint : SagaPersistenceStrategy()
    
    /**
     * Persist when saga completes or fails.
     */
    data object OnCompletion : SagaPersistenceStrategy()
    
    /**
     * Combine multiple strategies.
     */
    data class Combined(val strategies: List<SagaPersistenceStrategy>) : SagaPersistenceStrategy()
}