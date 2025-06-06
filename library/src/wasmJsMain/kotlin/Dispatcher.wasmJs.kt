package duks

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun backgroundDispatcher(): CoroutineDispatcher = Dispatchers.Default

/**
 * Get current time in milliseconds for WASM JS platform.
 */
internal actual fun currentTimeMillis(): Long {
    // For WASM, we'll use a simple timestamp based on monotonic time
    return kotlin.time.TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds
}