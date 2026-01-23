package io.kurlew.pipeline

import io.ktor.util.pipeline.Pipeline
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DataPipeline: runs per-event processing using ktor Pipeline semantics.
 *
 * Worker count default is 1 to preserve ordering per StartEvent.
 */
class DataPipeline(
    override val coroutineContext: CoroutineContext = Dispatchers.Default + SupervisorJob(),
    private val workerCount: Int = 1,
    private val bufferSize: Int = 1024
) : Pipeline<DataEvent, DataContext>(
    DataPipelinePhases.Acquire,
    DataPipelinePhases.Monitoring,
    DataPipelinePhases.Features,
    DataPipelinePhases.Process,
    DataPipelinePhases.Fallback
) {
    private val scope = CoroutineScope(coroutineContext)
    private val channel = Channel<Pair<DataContext, DataEvent>>(bufferSize)
    private val workersStarted = AtomicBoolean(false)

    private var shutdownTimeout: Duration = 10.seconds

    private fun startWorkersIfNeeded() {
        if (workersStarted.compareAndSet(false, true)) {
            repeat(workerCount) {
                scope.launch {
                    for ((ctx, event) in channel) {
                        try {
                            // Execute pipeline per event. Monitoring phase should wrap downstream exceptions.
                            execute(ctx, event)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            // Mark event failure if not handled and best-effort fallback.
                            event.failed = true
                            event.failureReason = t
                            // Try to let Fallback handle it by re-running pipeline (best-effort).
                            try {
                                execute(ctx, event)
                            } catch (_: Throwable) {
                                // ignore
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Enqueue an event for processing. Suspends when the buffer is full.
     */
    suspend fun accept(context: DataContext, event: DataEvent) {
        startWorkersIfNeeded()
        channel.send(context to event)
    }

    /**
     * Graceful stop: close channel and wait for workers to drain or until timeout.
     */
    suspend fun stopGraceful(timeout: Duration = shutdownTimeout) {
        channel.close()
        withTimeoutOrNull(timeout) {
            coroutineContext[Job]?.children?.forEach { it.join() }
        }
        scope.cancel()
    }

    /**
     * Immediate cancellation.
     */
    fun cancelNow() {
        scope.cancel()
        channel.cancel()
    }
}