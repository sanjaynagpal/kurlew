package io.kurlew.pipeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Provides a continuous stream of event. This can be consumed and processed in
 * a pipeline.
 */
interface DataSource<T> {
    val dataStream: SharedFlow<T>
}

/**
 * Continuous stream with a provider function.
 */
class EventStream<T>(
    val scope: CoroutineScope,
    val provider: suspend (MutableSharedFlow<T>) -> Unit
) : DataSource<T> {
    private val _events = MutableSharedFlow<T>(replay = 1, extraBufferCapacity = 10)
    override val dataStream: SharedFlow<T> = _events.asSharedFlow()

    private var job: Job? = null
    /**
     * Wires the provider in the background to start stream emit
     * Starts only if not started
     */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                provider(_events)
            } catch (e: CancellationException) {
                // clean up
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}