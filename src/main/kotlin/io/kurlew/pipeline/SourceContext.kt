package io.kurlew.pipeline
/**
 * Represents the source of a data event.
 *
 * This could be:
 * - HTTP request details
 * - WebSocket connection info
 * - Message queue metadata
 * - File system path
 * - Database trigger
 */
sealed class SourceContext {
    /**
     * HTTP request source.
     */
    data class Http(
        val method: String,
        val uri: String,
        val headers: Map<String, String> = emptyMap(),
        val remoteAddress: String? = null
    ) : SourceContext()

    /**
     * WebSocket connection source.
     */
    data class WebSocket(
        val connectionId: String,
        val uri: String,
        val headers: Map<String, String> = emptyMap()
    ) : SourceContext()

    /**
     * Message queue source (Kafka, RabbitMQ, etc.).
     */
    data class MessageQueue(
        val topic: String,
        val partition: Int? = null,
        val offset: Long? = null,
        val key: String? = null,
        val headers: Map<String, String> = emptyMap()
    ) : SourceContext()

    /**
     * File system source.
     */
    data class FileSystem(
        val path: String,
        val fileName: String,
        val mimeType: String? = null
    ) : SourceContext()

    /**
     * Database trigger source.
     */
    data class Database(
        val tableName: String,
        val operation: String, // INSERT, UPDATE, DELETE
        val rowId: String? = null
    ) : SourceContext()

    /**
     * Custom source type for extensibility.
     */
    data class Custom(
        val type: String,
        val metadata: Map<String, Any> = emptyMap()
    ) : SourceContext()
}