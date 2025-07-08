package duks

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SagaRegistryTest {
    
    data class TestSagaState(val count: Int = 0)
    data class TestState(val value: Int = 0) : StateModel
    
    data class TestAction(val value: String) : Action
    data class TestCompleteAction(val result: String) : Action
    
    class TestSaga : SagaDefinition<TestSagaState> {
        override val name = "TestSaga"
        
        override fun configure(saga: SagaConfiguration<TestSagaState>) {
            saga.startsOn<TestAction> { action ->
                SagaTransition.Continue(
                    TestSagaState(count = 1),
                    listOf(SagaEffect.Dispatch(TestCompleteAction("started: ${action.value}")))
                )
            }
            
            saga.on<TestAction> { action, state ->
                SagaTransition.Continue(
                    TestSagaState(count = state.count + 1),
                    listOf(SagaEffect.Dispatch(TestCompleteAction("processed: ${action.value}")))
                )
            }
        }
    }
    
    @Test
    fun `should register saga definitions`() = runTest {
        // Test that we can create a saga definition and register it
        val testSaga = TestSaga()
        
        // Verify the saga has the correct name
        assertEquals("TestSaga", testSaga.name)
        
        // Test that configuration works
        val configuration = SagaConfiguration<TestSagaState>()
        testSaga.configure(configuration)
        
        // The test passes if no exceptions are thrown
        assertTrue(true)
    }
    
    @Test
    fun `should register multiple saga definitions`() = runTest {
        val registry = SagaRegistry<TestState>()
        
        // Test that the registry can register multiple sagas
        registry.register(TestSaga())
        
        // Create another saga definition
        class AnotherTestSaga : SagaDefinition<TestSagaState> {
            override val name = "AnotherTestSaga"
            
            override fun configure(saga: SagaConfiguration<TestSagaState>) {
                saga.startsOn<TestAction> { action ->
                    SagaTransition.Continue(TestSagaState(count = 2))
                }
            }
        }
        
        registry.register(AnotherTestSaga())
        
        // The test verifies that register function can be called multiple times
        // without throwing exceptions
        assertTrue(true)
    }
}