package io.kurlew.pipeline

import io.ktor.util.pipeline.Pipeline
import io.kurlew.pipeline.DataPipelinePhases.Acquire
import io.kurlew.pipeline.DataPipelinePhases.Fallback
import io.kurlew.pipeline.DataPipelinePhases.Features
import io.kurlew.pipeline.DataPipelinePhases.Monitoring
import io.kurlew.pipeline.DataPipelinePhases.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * A Ktor-inspired, multi-phase data processing pipeline for resilient data handling.
 *
 * The DataPipeline extends Ktor's Pipeline class to provide a structured, sequential,
 * and extensible framework for processing DataEvent objects. It enforces a strict
 * five-phase lifecycle: Acquire → Monitoring → Features → Process → Fallback.
 *
 * Key architectural principles:
 * - Phase-Based Sequential Execution: Predictable, ordered processing
 * - Contextual Isolation: Each DataEvent is a unique instance (thread-safe)
 * - Immutable Core, Mutable State: incomingData is immutable, outgoingData is mutable
 * - Built-in Resilience: Monitoring and Fallback phases handle all failures
 *
 * @see DataEvent The context object that flows through the pipeline
 * @see DataPipelinePhases The five mandatory phases
 */
class DataPipeline (
    override val coroutineContext: CoroutineContext = Dispatchers.Default + SupervisorJob()
) : Pipeline<DataEvent, DataEvent>(Acquire, Monitoring, Features, Process, Fallback),
    CoroutineScope {

    /**
     * Executes the pipeline for a given DataEvent.
     *
     * This is the primary entry point for processing. The event will flow through
     * all configured interceptors in phase order unless short-circuited by finish().
     *
     * @param event The DataEvent to process
     * @return The processed DataEvent (with enriched outgoingData)
     */
    suspend fun execute(event: DataEvent): DataEvent {
        execute(event, event)
        return event
    }

    /**
     * Convenience method to execute the pipeline with raw data.
     * Creates a DataEvent wrapper automatically.
     *
     * @param rawData The raw data to process
     * @return The processed DataEvent
     */
    suspend fun executeRaw(rawData: Any): DataEvent {
        return execute(DataEvent(rawData))
    }
}