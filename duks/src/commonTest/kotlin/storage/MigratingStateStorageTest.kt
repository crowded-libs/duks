package duks.storage

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class MigratingStateStorageTest {

    data class VState(val value: Int, val label: String = "")

    @Test
    fun `migrates sequential versions on load`() = runTest(timeout = 5.seconds) {
        val backend = InMemoryVersionedStorage<VState>()
        backend.saveWithVersion(VState(1), version = 0)

        val storage = MigratingStateStorage(
            storage = backend,
            currentVersion = 2,
            migrations = mapOf(
                0 to { s -> s.copy(value = s.value + 10) },
                1 to { s -> s.copy(label = "v2") }
            )
        )

        val loaded = storage.load()
        assertEquals(VState(value = 11, label = "v2"), loaded)

        storage.save(VState(99, "fresh"))
        val reloaded = backend.loadWithVersion()
        assertEquals(VState(99, "fresh") to 2, reloaded)
    }

    @Test
    fun `returns null when empty`() = runTest(timeout = 5.seconds) {
        val storage = MigratingStateStorage(
            storage = InMemoryVersionedStorage<VState>(),
            currentVersion = 1,
            migrations = emptyMap()
        )
        assertNull(storage.load())
    }

    @Test
    fun `fails when migration missing`() = runTest(timeout = 5.seconds) {
        val backend = InMemoryVersionedStorage<VState>()
        backend.saveWithVersion(VState(1), version = 0)

        val storage = MigratingStateStorage(
            storage = backend,
            currentVersion = 2,
            migrations = emptyMap()
        )

        assertFailsWith<IllegalStateException> {
            storage.load()
        }
    }
}
