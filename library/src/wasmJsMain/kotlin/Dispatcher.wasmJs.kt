package duks

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun backgroundDispatcher(): CoroutineDispatcher = Dispatchers.Default