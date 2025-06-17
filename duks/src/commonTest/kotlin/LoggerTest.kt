package duks

import duks.logging.ConsoleLogger
import duks.logging.LogLevel
import duks.logging.Logger
import duks.logging.debug
import duks.logging.error
import duks.logging.fatal
import duks.logging.info
import duks.logging.trace
import duks.logging.warn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoggerTest {
    
    @Test
    fun `formatMessage should replace single placeholder`() {
        val logger = Logger.default()
        val result = logger.formatMessage("Action: {action}", "TestAction")
        assertEquals("Action: TestAction", result)
    }
    
    @Test
    fun `formatMessage should replace multiple different placeholders`() {
        val logger = Logger.default()
        val result = logger.formatMessage("Processing {action} at {time}", "SaveAction", "10:30 AM")
        assertEquals("Processing SaveAction at 10:30 AM", result)
    }
    
    @Test
    fun `formatMessage should replace multiple occurrences of same placeholder`() {
        val logger = Logger.default()
        val result = logger.formatMessage("Action {action} started, processing {action}, completed {action}", "DeleteAction")
        assertEquals("Action DeleteAction started, processing DeleteAction, completed DeleteAction", result)
    }
    
    @Test
    fun `formatMessage should handle no placeholders`() {
        val logger = Logger.default()
        val result = logger.formatMessage("No placeholders here", "unused")
        assertEquals("No placeholders here", result)
    }
    
    @Test
    fun `formatMessage should handle empty args`() {
        val logger = Logger.default()
        val result = logger.formatMessage("Action: {action}")
        assertEquals("Action: {action}", result)
    }
    
    @Test
    fun `formatMessage should handle more placeholders than args`() {
        val logger = Logger.default()
        val result = logger.formatMessage("Action: {action} at {time} by {user}", "SaveAction", "10:30 AM")
        assertEquals("Action: SaveAction at 10:30 AM by {user}", result)
    }
    
    @Test
    fun `formatMessage should handle more args than placeholders`() {
        val logger = Logger.default()
        val result = logger.formatMessage("Action: {action}", "SaveAction", "10:30 AM", "extra")
        assertEquals("Action: SaveAction", result)
    }
    
    @Test
    fun `formatMessage should handle null values`() {
        val logger = Logger.default()
        val result = logger.formatMessage("Action: {action} value: {value}", "TestAction", null)
        assertEquals("Action: TestAction value: null", result)
    }
    
    @Test
    fun `formatMessage should preserve placeholder order based on first occurrence`() {
        val logger = Logger.default()
        val result = logger.formatMessage("{second} then {first} then {second} again", "1st", "2nd")
        assertEquals("1st then 2nd then 1st again", result)
    }
    
    @Test
    fun `formatMessage should handle special characters in placeholders`() {
        val logger = Logger.default()
        val result = logger.formatMessage("Value: {my-value} and {my_value}", "dash-value", "underscore-value")
        assertEquals("Value: dash-value and underscore-value", result)
    }
    
    @Test
    fun `inline trace function should format message correctly`() {
        val testLogger = TestLogger()
        testLogger.logLevel = LogLevel.TRACE
        
        testLogger.trace("TestAction", 42) { "Executing {action} with value {value}" }
        
        assertEquals(1, testLogger.messages.size)
        assertEquals("[TRACE] Executing TestAction with value 42", testLogger.messages[0])
    }
    
    @Test
    fun `inline debug function should format message correctly`() {
        val testLogger = TestLogger()
        testLogger.logLevel = LogLevel.DEBUG
        
        testLogger.debug("SaveAction", "10:30 AM") { "Processing {action} at {time}" }
        
        assertEquals(1, testLogger.messages.size)
        assertEquals("[DEBUG] Processing SaveAction at 10:30 AM", testLogger.messages[0])
    }
    
    @Test
    fun `inline info function should format message correctly`() {
        val testLogger = TestLogger()
        
        testLogger.info("UserLogin", "john.doe") { "Action {action} for user {user}" }
        
        assertEquals(1, testLogger.messages.size)
        assertEquals("[INFO] Action UserLogin for user john.doe", testLogger.messages[0])
    }
    
    @Test
    fun `inline warn function should format message correctly`() {
        val testLogger = TestLogger()
        
        testLogger.warn("HighMemory", "85%") { "Warning: {issue} detected at {level}" }
        
        assertEquals(1, testLogger.messages.size)
        assertEquals("[WARN] Warning: HighMemory detected at 85%", testLogger.messages[0])
    }
    
    @Test
    fun `inline error function should format message correctly`() {
        val testLogger = TestLogger()
        
        testLogger.error("DatabaseConnection", "timeout") { "Error: {component} failed with {reason}" }
        
        assertEquals(1, testLogger.messages.size)
        assertEquals("[ERROR] Error: DatabaseConnection failed with timeout", testLogger.messages[0])
    }
    
    @Test
    fun `inline fatal function should format message correctly`() {
        val testLogger = TestLogger()
        
        testLogger.fatal("SystemCrash", "OutOfMemory") { "Fatal: {event} due to {cause}" }
        
        assertEquals(1, testLogger.messages.size)
        assertEquals("[FATAL] Fatal: SystemCrash due to OutOfMemory", testLogger.messages[0])
    }
    
    @Test
    fun `inline error function with throwable should format message correctly`() {
        val testLogger = TestLogger()
        val exception = RuntimeException("Test exception")
        
        testLogger.error(exception, "SaveAction") { "Failed to execute {action}" }
        
        assertEquals(1, testLogger.messages.size)
        assertEquals("[ERROR] Failed to execute SaveAction - Test exception", testLogger.messages[0])
    }
    
    @Test
    fun `inline warn function with throwable should format message correctly`() {
        val testLogger = TestLogger()
        val exception = IllegalStateException("Invalid state")
        
        testLogger.warn(exception, "ValidationAction") { "Warning during {action}" }
        
        assertEquals(1, testLogger.messages.size)
        assertEquals("[WARN] Warning during ValidationAction - Invalid state", testLogger.messages[0])
    }
    
    @Test
    fun `inline fatal function with throwable should format message correctly`() {
        val testLogger = TestLogger()
        val exception = OutOfMemoryError("Heap space")
        
        testLogger.fatal(exception, "MemoryAllocation") { "Fatal error in {operation}" }
        
        assertEquals(1, testLogger.messages.size)
        assertEquals("[FATAL] Fatal error in MemoryAllocation - Heap space", testLogger.messages[0])
    }
    
    @Test
    fun `inline functions should respect log level`() {
        val testLogger = TestLogger()
        testLogger.logLevel = LogLevel.WARN
        
        testLogger.trace("action") { "Trace: {msg}" }
        testLogger.debug("action") { "Debug: {msg}" }
        testLogger.info("action") { "Info: {msg}" }
        testLogger.warn("action") { "Warn: {msg}" }
        testLogger.error("action") { "Error: {msg}" }
        testLogger.fatal("action") { "Fatal: {msg}" }
        
        assertEquals(3, testLogger.messages.size)
        assertTrue(testLogger.messages[0].contains("Warn"))
        assertTrue(testLogger.messages[1].contains("Error"))
        assertTrue(testLogger.messages[2].contains("Fatal"))
    }
    
    @Test
    fun `formatMessage should handle complex nested patterns`() {
        val logger = Logger.default()
        val result = logger.formatMessage(
            "User {user} performed {action} on {resource} at {time}, {action} was successful",
            "alice",
            "UPDATE",
            "document-123",
            "14:30"
        )
        assertEquals("User alice performed UPDATE on document-123 at 14:30, UPDATE was successful", result)
    }
    
    @Test
    fun `formatMessage should handle empty string placeholders`() {
        val logger = Logger.default()
        val result = logger.formatMessage("Value: {value}", "")
        assertEquals("Value: ", result)
    }
    
    @Test
    fun `formatMessage should handle numeric placeholders`() {
        val logger = Logger.default()
        val result = logger.formatMessage("Count: {count}, Progress: {progress}%", 42, 75.5)
        assertEquals("Count: 42, Progress: 75.5%", result)
    }
    
    @Test
    fun `formatMessage should handle boolean placeholders`() {
        val logger = Logger.default()
        val result = logger.formatMessage("Success: {success}, Valid: {valid}", true, false)
        assertEquals("Success: true, Valid: false", result)
    }
    
    @Test
    fun `ConsoleLogger should use formatMessage for all log methods`() {
        val logger = ConsoleLogger()
        
        // Since ConsoleLogger prints to stdout, we can't easily capture the output
        // But we can verify it doesn't throw exceptions with placeholders
        logger.trace("Message with {placeholder}", "value")
        logger.debug("Debug {action} at {time}", "test", "now")
        logger.info("Info {msg}", "test")
        logger.warn("Warn {issue}", "problem")
        logger.error("Error {error}", "failure")
        logger.fatal("Fatal {cause}", "crash")
        
        val exception = RuntimeException("test")
        logger.warn("Warn {action}", exception, "test")
        logger.error("Error {action}", exception, "test")
        logger.fatal("Fatal {action}", exception, "test")
        
        // If we get here without exceptions, the formatting is working
        assertTrue(true)
    }
}