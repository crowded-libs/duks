package duks

import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StoreBuilderTest {
    
    data class TestState(val value: Int = 0) : StateModel
    
    @Test
    fun `should provide access to StoreBuilder scope property`() = runTest {
        val builder = StoreBuilder<TestState>()
        
        // Test that scope property is accessible
        val scope = builder.scope
        assertNotNull(scope, "StoreBuilder scope should not be null")
        
        // Test that scope is active
        assertTrue(scope.isActive, "StoreBuilder scope should be active")
        
        // Test that we can launch coroutines in the scope
        var executed = false
        val job = scope.launch {
            executed = true
        }
        
        job.join()
        assertTrue(executed, "Should be able to launch coroutines in StoreBuilder scope")
    }
    
    @Test
    fun `should access StoreBuilder scope during store creation`() = runTest {
        // Test that StoreBuilder has a scope property that can be accessed
        var scopeCaptured: kotlinx.coroutines.CoroutineScope? = null
        
        val store = createStore(TestState()) {
            // Capture the scope during store building
            scopeCaptured = this.scope
            
            middleware {
                // Add a simple middleware
                middleware { store, next, action ->
                    next(action)
                }
            }
        }
        
        assertNotNull(scopeCaptured, "StoreBuilder scope should be accessible during building")
        assertTrue(scopeCaptured.isActive, "Captured scope should be active")
    }
}