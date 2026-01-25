package io.kurlew.pipeline

/**
 * Represents the context/call object that flows through the DataPipeline.
 *
 * This class holds mutable state and metadata that accumulates as the
 * DataEvent is processed through different pipeline phases.
 */
class DataPipelineCall(
    /**
     * A mutable map for storing state, metadata, and processing results.
     * Modifications are cumulative - data added by early phases is available
     * to all subsequent phases.
     */
    val attributes: MutableMap<String, Any> = mutableMapOf()
) {
    /**
     * Checks if this call has been marked as failed by any phase.
     */
    fun isFailed(): Boolean = attributes["failed"] as? Boolean ?: false

    /**
     * Marks this call as failed with an optional error message.
     */
    fun markFailed(error: String? = null) {
        attributes["failed"] = true
        error?.let { attributes["error"] = it }
    }

    /**
     * Retrieves the error message if this call failed.
     */
    fun getError(): String? = attributes["error"] as? String

    /**
     * Adds enrichment data to the call attributes.
     */
    fun enrich(key: String, value: Any) {
        attributes[key] = value
    }

    /**
     * Retrieves enrichment data from the call attributes.
     */
    inline fun <reified T> get(key: String): T? = attributes[key] as? T
}
