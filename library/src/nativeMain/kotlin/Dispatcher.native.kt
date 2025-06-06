package duks

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlin.time.TimeSource

actual fun backgroundDispatcher(): CoroutineDispatcher = Dispatchers.IO

/**
 * Get current time in milliseconds for Native platforms.
 */
internal actual fun currentTimeMillis(): Long = TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds