package io.kurlew.pipeline
/**
 * Session context for stateful processing.
 *
 * Maintains state across multiple events from the same source.
 * Examples: user session, connection state, transaction context.
 */
data class SessionContext(
    /**
     * Unique session identifier.
     */
    val sessionId: String,

    /**
     * Session data storage.
     */
    val data: MutableMap<String, Any> = mutableMapOf(),

    /**
     * Session creation timestamp.
     */
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Last activity timestamp.
     */
    var lastActivityAt: Long = System.currentTimeMillis()
) {
    /**
     * Updates last activity timestamp.
     */
    fun touch() {
        lastActivityAt = System.currentTimeMillis()
    }

    /**
     * Sets session data.
     */
    fun set(key: String, value: Any) {
        data[key] = value
        touch()
    }

    /**
     * Gets session data with type safety.
     */
    inline fun <reified T> get(key: String): T? {
        touch()
        return data[key] as? T
    }

    /**
     * Checks if session has expired.
     */
    fun isExpired(timeoutMs: Long = 3600_000): Boolean { // 1 hour default
        return System.currentTimeMillis() - lastActivityAt > timeoutMs
    }
}