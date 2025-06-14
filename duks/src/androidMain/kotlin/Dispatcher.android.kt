package duks

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun backgroundDispatcher(): CoroutineDispatcher = Dispatchers.IO

/**
 * Get current time in milliseconds for Android platform.
 */
internal actual fun currentTimeMillis(): Long = System.currentTimeMillis()