package duks.storage

import duks.storage.StateStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/**
 * A test storage implementation that simulates file storage using an in-memory map.
 * This allows us to test persistence behavior without dealing with actual file I/O.
 * 
 * This implementation uses Kotlin serialization to store states as JSON.
 */
class JsonFileStorage<TState : Any>(
    private val fileName: String,
    private val stateClass: KClass<TState>,
    private val fileSystem: TestFileSystem = TestFileSystem.instance
) : StateStorage<TState> {
    
    private val json = Json { 
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    
    @OptIn(kotlinx.serialization.InternalSerializationApi::class)
    override suspend fun save(state: TState) = withContext(Dispatchers.Default) {
        val serializer = stateClass.serializer()
        val jsonString = json.encodeToString(serializer, state)
        fileSystem.writeFile(fileName, jsonString)
    }
    
    @OptIn(kotlinx.serialization.InternalSerializationApi::class)
    override suspend fun load(): TState? = withContext(Dispatchers.Default) {
        fileSystem.readFile(fileName)?.let { jsonString ->
            val serializer = stateClass.serializer()
            json.decodeFromString(serializer, jsonString)
        }
    }
    
    override suspend fun clear() = withContext(Dispatchers.Default) {
        fileSystem.deleteFile(fileName)
    }
    
    override suspend fun exists(): Boolean = withContext(Dispatchers.Default) {
        fileSystem.fileExists(fileName)
    }
}

/**
 * Reified helper function to create JsonFileStorage without passing KClass
 */
inline fun <reified TState : Any> createJsonFileStorage(
    fileName: String,
    fileSystem: TestFileSystem = TestFileSystem.instance
): JsonFileStorage<TState> = JsonFileStorage(fileName, TState::class, fileSystem)

/**
 * Simple in-memory file system simulation for testing
 */
class TestFileSystem {
    private val files = mutableMapOf<String, String>()
    
    fun writeFile(path: String, content: String) {
        files[path] = content
    }
    
    fun readFile(path: String): String? = files[path]
    
    fun deleteFile(path: String) {
        files.remove(path)
    }
    
    fun fileExists(path: String): Boolean = files.containsKey(path)
    
    fun clear() {
        files.clear()
    }
    
    fun listFiles(): List<String> = files.keys.toList()
    
    companion object {
        val instance = TestFileSystem()
    }
}