package duks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for the non-Compose selection contract used by [mapToProps] /
 * [mapToPropsAsState]. Equality short-circuit is verified via [mapToProps]
 * (map + distinctUntilChanged) without introducing Compose UI test infrastructure.
 */
class MapToPropsTest {

    data class TestState(
        val counter: Int = 0,
        val message: String = "",
        val flag: Boolean = false
    ) : StateModel

    data class CounterProps(val counter: Int)

    @Test
    fun `mapToProps does not emit when only unselected fields change`() = runTest(timeout = 5.seconds) {
        val stateFlow = MutableStateFlow(TestState(counter = 1, message = "a"))
        val emissions = mutableListOf<CounterProps>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            stateFlow.mapToProps { CounterProps(counter) }.collect { emissions.add(it) }
        }

        // Initial emission
        assertEquals(listOf(CounterProps(1)), emissions)

        // Unrelated field changes should not emit
        stateFlow.value = TestState(counter = 1, message = "b")
        stateFlow.value = TestState(counter = 1, message = "c", flag = true)
        assertEquals(listOf(CounterProps(1)), emissions)

        // Selected field change should emit
        stateFlow.value = TestState(counter = 2, message = "c", flag = true)
        assertEquals(listOf(CounterProps(1), CounterProps(2)), emissions)
    }

    @Test
    fun `mapToProps emits when selected primitive props change`() = runTest(timeout = 5.seconds) {
        val stateFlow = MutableStateFlow(TestState(message = "hello"))
        val emissions = mutableListOf<String>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            stateFlow.mapToProps { message }.collect { emissions.add(it) }
        }

        assertEquals(listOf("hello"), emissions)

        stateFlow.value = TestState(counter = 99, message = "hello")
        assertEquals(listOf("hello"), emissions)

        stateFlow.value = TestState(counter = 99, message = "world")
        assertEquals(listOf("hello", "world"), emissions)
    }

    @Test
    fun `mapToProps does not re-emit equal data class props after intermediate noise`() =
        runTest(timeout = 5.seconds) {
            val stateFlow = MutableStateFlow(TestState(counter = 5, message = "x"))
            val emissions = mutableListOf<CounterProps>()

            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                stateFlow.mapToProps { CounterProps(counter) }.collect { emissions.add(it) }
            }

            stateFlow.value = TestState(counter = 5, message = "y")
            stateFlow.value = TestState(counter = 7, message = "y")
            stateFlow.value = TestState(counter = 7, message = "z")
            stateFlow.value = TestState(counter = 5, message = "z")

            assertEquals(
                listOf(CounterProps(5), CounterProps(7), CounterProps(5)),
                emissions
            )
        }
}
