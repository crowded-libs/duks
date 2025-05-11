package duks

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

actual fun backgroundDispatcher(): CoroutineDispatcher = Dispatchers.IO