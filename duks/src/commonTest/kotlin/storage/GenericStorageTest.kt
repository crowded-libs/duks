package duks.storage

import duks.*
import kotlin.test.*
import kotlinx.coroutines.test.*

data class TestState(val value: String) : StateModel

class GenericStorageTest {
    @Test
    fun `should save and load state with in-memory storage`() = runTest {
        // Create storage that works directly with state
        val storage = InMemoryStorage<TestState>()
        
        // Test save and load
        val state = TestState("Hello, World!")
        storage.save(state)
        
        assertTrue(storage.exists())
        val loaded = storage.load()
        assertNotNull(loaded)
        assertEquals(state, loaded)
        
        // Test clear
        storage.clear()
        assertFalse(storage.exists())
        assertNull(storage.load())
    }
    
    @Test
    fun `should work with different state types`() = runTest {
        // Test with different state types
        data class UserState(val name: String, val age: Int) : StateModel
        
        val storage = InMemoryStorage<UserState>()
        val state = UserState("John", 25)
        
        storage.save(state)
        
        val loaded = storage.load()
        assertNotNull(loaded)
        assertEquals("John", loaded.name)
        assertEquals(25, loaded.age)
    }
    
    @Test
    fun `should save to multiple storages with composite storage`() = runTest {
        // Test composite storage that saves to multiple storages
        val storage1 = InMemoryStorage<TestState>()
        val storage2 = InMemoryStorage<TestState>()
        val storage3 = InMemoryStorage<TestState>()
        
        val composite = CompositeStorage(listOf(storage1, storage2, storage3))
        
        val state = TestState("Composite Test")
        composite.save(state)
        
        // All storages should have the state
        assertEquals(state, storage1.load())
        assertEquals(state, storage2.load())
        assertEquals(state, storage3.load())
        
        // Loading from composite should return first available
        assertEquals(state, composite.load())
        
        // Clear all
        composite.clear()
        assertFalse(storage1.exists())
        assertFalse(storage2.exists())
        assertFalse(storage3.exists())
    }
    
    @Test
    fun `should handle failures gracefully in composite storage`() = runTest {
        // Test composite storage when one storage fails
        val workingStorage = InMemoryStorage<TestState>()
        val failingStorage = object : StateStorage<TestState> {
            override suspend fun save(state: TestState) {
                throw RuntimeException("Save failed")
            }
            override suspend fun load(): TestState? {
                throw RuntimeException("Load failed")
            }
            override suspend fun clear() {
                throw RuntimeException("Clear failed")
            }
            override suspend fun exists(): Boolean = false
        }
        
        val composite = CompositeStorage(listOf(failingStorage, workingStorage))
        
        val state = TestState("Should work despite failure")
        
        // Save will throw because one storage fails (awaitAll behavior)
        assertFailsWith<RuntimeException> {
            composite.save(state)
        }
        
        // However, load should still work by returning from any successful storage
        // First save to working storage directly
        workingStorage.save(state)
        
        // Load should return from working storage despite failing storage
        val loaded = composite.load()
        assertEquals(state, loaded)
        
        // Exists should return true if any storage has data
        assertTrue(composite.exists())
        
        // Clear will also throw due to awaitAll
        assertFailsWith<RuntimeException> {
            composite.clear()
        }
    }
    
    @Test
    fun `should maintain state isolation between saves`() = runTest {
        // Ensure storage maintains state isolation
        val storage = InMemoryStorage<TestState>()
        
        val state1 = TestState("First")
        val state2 = TestState("Second")
        
        storage.save(state1)
        assertEquals(state1, storage.load())
        
        storage.save(state2)
        assertEquals(state2, storage.load())
        assertNotEquals(state1, storage.load())
    }
}