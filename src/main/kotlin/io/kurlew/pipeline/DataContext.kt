package io.kurlew.pipeline

import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext

/**
 * The execution context for the data pipeline.
 *
 * DataContext provides access to:
 * - Source context (where the event came from)
 * - Session management
 * - Shared services (cache, database, APIs)
 * - Correlation/tracing information
 * - Pipeline-wide configuration
 *
 * This separates "what data" (DataEvent) from "how to process it" (DataContext).
 */
data class DataContext(
    /**
     * Correlation ID for distributed tracing.
     * Used to track events across multiple systems.
     */
    val correlationId: String = generateCorrelationId(),

    /**
     * Source context - information about where the event originated.
     * Could be HTTP request, WebSocket connection, Kafka topic, etc.
     */
    val source: SourceContext? = null,

    /**
     * Session information for stateful processing.
     * Useful for user sessions, connection state, etc.
     */
    val session: SessionContext? = null,

    /**
     * Cache for storing/retrieving temporary data.
     * Shared across all events in the same pipeline execution.
     */
    val cache: CacheContext = CacheContext(),

    /**
     * Shared services available to all interceptors.
     * Database connections, API clients, etc.
     */
    val services: ServiceRegistry = ServiceRegistry(),

    /**
     * Additional attributes that can be set during processing.
     * Similar to Ktor's Attributes.
     */
    val attributes: MutableMap<String, Any> = mutableMapOf(),

    /**
     * Coroutine context for the current execution.
     */
    override val coroutineContext: CoroutineContext
) : CoroutineScope {

    /**
     * Sets an attribute value.
     */
    fun setAttribute(key: String, value: Any) {
        attributes[key] = value
    }

    /**
     * Gets an attribute value with type safety.
     */
    inline fun <reified T> getAttribute(key: String): T? = attributes[key] as? T

    companion object {
        private var counter = 0L
        private fun generateCorrelationId(): String {
            return "corr-${System.currentTimeMillis()}-${counter++}"
        }
    }
}