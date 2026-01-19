package io.kurlew.pipeline

data class DataEvent(
    /**
     * The immutable raw input acquired at the beginning of the pipeline.
     * This serves as the reliable, unaltered source of truth.
     */
    val incomingData: Any,

    /**
     * A mutable map for storing state, metadata, and processing results.
     * Modifications are cumulative - data added by early phases is available
     * to all subsequent phases.
     */
    val outgoingData: MutableMap<String, Any> = mutableMapOf()
) {
    /**
     * Checks if this event has been marked as failed by any phase.
     */
    fun isFailed(): Boolean = outgoingData["failed"] as? Boolean ?: false

    /**
     * Marks this event as failed with an optional error message.
     */
    fun markFailed(error: String? = null) {
        outgoingData["failed"] = true
        error?.let { outgoingData["error"] = it }
    }

    /**
     * Retrieves the error message if this event failed.
     */
    fun getError(): String? = outgoingData["error"] as? String

    /**
     * Adds enrichment data to the outgoing context.
     */
    fun enrich(key: String, value: Any) {
        outgoingData[key] = value
    }

    /**
     * Retrieves enrichment data from the outgoing context.
     */
    inline fun <reified T> get(key: String): T? = outgoingData[key] as? T
}
