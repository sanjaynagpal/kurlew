package io.kurlew.pipeline

/**
 * StartEvent describes how to start acquiring data. Extensible sealed class.
 */
sealed class StartEvent {
    data class ByMic(val mic: String) : StartEvent()
    data class ByTopicPartition(val topic: String, val partition: Int, val offset: Long? = null) : StartEvent()
    data class Once(val requestId: String? = null) : StartEvent()
    data class Resume(val checkpointId: String) : StartEvent()
    data class Custom(val name: String, val params: Map<String, String> = emptyMap()) : StartEvent()
}