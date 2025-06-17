package duks.logging

/**
 * Represents the logging level for the logger.
 */
enum class LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL
}

/**
 * Logger interface providing logging capabilities with different severity levels.
 *
 * The logger supports efficient logging through inline functions that check the
 * current log level before evaluating the message lambda, avoiding unnecessary
 * string creation when the log level is disabled.
 */
interface Logger {
    /**
     * The current logging level. Messages below this level will not be logged.
     */
    var logLevel: LogLevel

    /**
     * Logs a trace level message.
     */
    fun trace(message: String, vararg args: Any?)

    /**
     * Logs a debug level message.
     */
    fun debug(message: String, vararg args: Any?)

    /**
     * Logs an info level message.
     */
    fun info(message: String, vararg args: Any?)

    /**
     * Logs a warning level message.
     */
    fun warn(message: String, vararg args: Any?)

    /**
     * Logs an error level message.
     */
    fun error(message: String, vararg args: Any?)

    /**
     * Logs a fatal level message.
     */
    fun fatal(message: String, vararg args: Any?)

    /**
     * Logs a warning level message with an exception.
     */
    fun warn(message: String, throwable: Throwable, vararg args: Any?)

    /**
     * Logs an error level message with an exception.
     */
    fun error(message: String, throwable: Throwable, vararg args: Any?)

    /**
     * Logs a fatal level message with an exception.
     */
    fun fatal(message: String, throwable: Throwable, vararg args: Any?)

    /**
     * Formats a message by replacing named placeholders with provided arguments.
     * Placeholders are in the format {name} and are replaced based on position.
     * The first unique placeholder found is replaced with the first argument,
     * the second unique placeholder with the second argument, and so on.
     *
     * Example:
     * formatMessage("Action {action} at {time}, retrying {action}", myAction, currentTime)
     * will replace all occurrences of {action} with myAction and all occurrences of {time} with currentTime
     *
     * @param message The message template with placeholders
     * @param args The arguments to replace placeholders with
     * @return The formatted message
     */
    fun formatMessage(message: String, vararg args: Any?): String {
        if (args.isEmpty()) return message

        var result = message
        val placeholderPattern = "\\{([^}]+)\\}".toRegex()
        val placeholders = placeholderPattern.findAll(message).map { it.groups[1]!!.value }.distinct().toList()

        placeholders.forEachIndexed { index, placeholder ->
            if (index < args.size) {
                result = result.replace("{$placeholder}", args[index].toString())
            }
        }

        return result
    }

    companion object {
        /**
         * Returns a default console logger implementation.
         */
        fun default(): Logger = factory()

        var factory: () -> Logger = { ConsoleLogger() }
    }
}

/**
 * Logs a trace level message using a lambda that is only evaluated if trace level is enabled.
 */
inline fun Logger.trace(vararg args: Any?, crossinline message: () -> String) {
    if (logLevel <= LogLevel.TRACE) {
        trace(message(), *args)
    }
}

/**
 * Logs a debug level message using a lambda that is only evaluated if debug level is enabled.
 */
inline fun Logger.debug(vararg args: Any?, crossinline message: () -> String) {
    if (logLevel <= LogLevel.DEBUG) {
        debug(message(), *args)
    }
}

/**
 * Logs an info level message using a lambda that is only evaluated if info level is enabled.
 */
inline fun Logger.info(vararg args: Any?, crossinline message: () -> String) {
    if (logLevel <= LogLevel.INFO) {
        info(message(), *args)
    }
}

/**
 * Logs a warning level message using a lambda that is only evaluated if warn level is enabled.
 */
inline fun Logger.warn(vararg args: Any?, crossinline message: () -> String) {
    if (logLevel <= LogLevel.WARN) {
        warn(message(), *args)
    }
}

/**
 * Logs an error level message using a lambda that is only evaluated if error level is enabled.
 */
inline fun Logger.error(vararg args: Any?, crossinline message: () -> String) {
    if (logLevel <= LogLevel.ERROR) {
        error(message(), *args)
    }
}

/**
 * Logs a fatal level message using a lambda that is only evaluated if fatal level is enabled.
 */
inline fun Logger.fatal(vararg args: Any?, crossinline message: () -> String) {
    if (logLevel <= LogLevel.FATAL) {
        fatal(message(), *args)
    }
}

/**
 * Logs a warning level message with an exception using a lambda that is only evaluated if warn level is enabled.
 */
inline fun Logger.warn(throwable: Throwable, vararg args: Any?, crossinline message: () -> String) {
    if (logLevel <= LogLevel.WARN) {
        warn(message(), throwable, *args)
    }
}

/**
 * Logs an error level message with an exception using a lambda that is only evaluated if error level is enabled.
 */
inline fun Logger.error(throwable: Throwable, vararg args: Any?, crossinline message: () -> String) {
    if (logLevel <= LogLevel.ERROR) {
        error(message(), throwable, *args)
    }
}

/**
 * Logs a fatal level message with an exception using a lambda that is only evaluated if fatal level is enabled.
 */
inline fun Logger.fatal(throwable: Throwable, vararg args: Any?, crossinline message: () -> String) {
    if (logLevel <= LogLevel.FATAL) {
        fatal(message(), throwable, *args)
    }
}

/**
 * Default console logger implementation that outputs to println.
 *
 * This basic implementation formats log messages with a level prefix and
 * outputs them using println. It can be easily replaced with a more
 * sophisticated logging library implementation.
 */
class ConsoleLogger(
    override var logLevel: LogLevel = LogLevel.INFO
) : Logger {

    override fun trace(message: String, vararg args: Any?) {
        if (logLevel <= LogLevel.TRACE) {
            println("[TRACE] ${formatMessage(message, *args)}")
        }
    }

    override fun debug(message: String, vararg args: Any?) {
        if (logLevel <= LogLevel.DEBUG) {
            println("[DEBUG] ${formatMessage(message, *args)}")
        }
    }

    override fun info(message: String, vararg args: Any?) {
        if (logLevel <= LogLevel.INFO) {
            println("[INFO] ${formatMessage(message, *args)}")
        }
    }

    override fun warn(message: String, vararg args: Any?) {
        if (logLevel <= LogLevel.WARN) {
            println("[WARN] ${formatMessage(message, *args)}")
        }
    }

    override fun error(message: String, vararg args: Any?) {
        if (logLevel <= LogLevel.ERROR) {
            println("[ERROR] ${formatMessage(message, *args)}")
        }
    }

    override fun fatal(message: String, vararg args: Any?) {
        if (logLevel <= LogLevel.FATAL) {
            println("[FATAL] ${formatMessage(message, *args)}")
        }
    }

    override fun warn(message: String, throwable: Throwable, vararg args: Any?) {
        if (logLevel <= LogLevel.WARN) {
            println("[WARN] ${formatMessage(message, *args)}")
            throwable.printStackTrace()
        }
    }

    override fun error(message: String, throwable: Throwable, vararg args: Any?) {
        if (logLevel <= LogLevel.ERROR) {
            println("[ERROR] ${formatMessage(message, *args)}")
            throwable.printStackTrace()
        }
    }

    override fun fatal(message: String, throwable: Throwable, vararg args: Any?) {
        if (logLevel <= LogLevel.FATAL) {
            println("[FATAL] ${formatMessage(message, *args)}")
            throwable.printStackTrace()
        }
    }
}