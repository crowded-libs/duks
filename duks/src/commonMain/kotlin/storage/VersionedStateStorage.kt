package duks.storage

/**
 * Extended storage interface that supports versioning for migration support.
 * @param TState The type of state being stored
 */
interface VersionedStateStorage<TState> : StateStorage<TState> {
    /**
     * Save data with a version number.
     * @param state The state to save
     * @param version The version number
     */
    suspend fun saveWithVersion(state: TState, version: Int)

    /**
     * Load data with its version number.
     * @return A pair of state and version, or null if no data exists
     */
    suspend fun loadWithVersion(): Pair<TState, Int>?
}

/**
 * Wraps a [VersionedStateStorage] and applies sequential migrations until
 * [currentVersion] is reached on load. Saves always write [currentVersion].
 *
 * @param storage Versioned backend
 * @param currentVersion Schema version written on save
 * @param migrations Map from version `v` to a function that migrates state from `v` to `v + 1`
 */
class MigratingStateStorage<TState>(
    private val storage: VersionedStateStorage<TState>,
    private val currentVersion: Int,
    private val migrations: Map<Int, suspend (TState) -> TState>
) : StateStorage<TState> {

    init {
        require(currentVersion >= 0) { "currentVersion must be non-negative" }
    }

    override suspend fun save(state: TState) {
        storage.saveWithVersion(state, currentVersion)
    }

    override suspend fun load(): TState? {
        val loaded = storage.loadWithVersion() ?: return null
        var (state, version) = loaded
        if (version > currentVersion) {
            throw IllegalStateException(
                "Stored state version $version is newer than current version $currentVersion"
            )
        }
        while (version < currentVersion) {
            val migrate = migrations[version]
                ?: throw IllegalStateException("Missing migration from version $version to ${version + 1}")
            state = migrate(state)
            version++
        }
        return state
    }

    override suspend fun clear() = storage.clear()

    override suspend fun exists(): Boolean = storage.exists()
}

/**
 * In-memory [VersionedStateStorage] for tests and simple use cases.
 */
class InMemoryVersionedStorage<TState> : VersionedStateStorage<TState> {
    private var data: TState? = null
    private var version: Int = 0

    override suspend fun save(state: TState) {
        // Unversioned save keeps the previous version tag (or 0).
        this.data = state
    }

    override suspend fun load(): TState? = data

    override suspend fun clear() {
        data = null
        version = 0
    }

    override suspend fun exists(): Boolean = data != null

    override suspend fun saveWithVersion(state: TState, version: Int) {
        this.data = state
        this.version = version
    }

    override suspend fun loadWithVersion(): Pair<TState, Int>? {
        val value = data ?: return null
        return value to version
    }
}
