package duks

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DispatcherTest {
    
    @Test
    fun `should provide a background dispatcher`() = runTest {
        val dispatcher = backgroundDispatcher()
        assertNotNull(dispatcher, "Background dispatcher should not be null")
    }
    
    @Test
    fun `should execute work on background dispatcher`() = runTest {
        val dispatcher = backgroundDispatcher()
        var executed = false
        
        withContext(dispatcher) {
            executed = true
        }
        
        assertTrue(executed, "Work should be executed on background dispatcher")
    }
}