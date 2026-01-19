package io.kurlew.pipeline

import io.ktor.util.pipeline.PipelinePhase

object DataPipelinePhases {
    /**
     * Phase 1: Acquire
     *
     * Entry point of the pipeline. Responsible for:
     * - Receiving initial triggers/requests
     * - Fetching raw data from external sources
     * - Creating DataEvent instances
     */
    val Acquire = PipelinePhase("Acquire")

    /**
     * Phase 2: Monitoring
     *
     * Guardian phase that wraps downstream processing. Responsible for:
     * - Logging pipeline entry
     * - Catching exceptions from downstream phases
     * - Recording error details in DataEvent.outgoingData
     * - Timing and observability
     */
    val Monitoring = PipelinePhase("Monitoring")

    /**
     * Phase 3: Features
     *
     * Validation and enrichment gateway. Responsible for:
     * - Validating incomingData
     * - Short-circuiting invalid events (finish())
     * - Enriching outgoingData for downstream use
     */
    val Features = PipelinePhase("Features")

    /**
     * Phase 4: Process
     *
     * Core business logic phase. Responsible for:
     * - Executing primary data processing
     * - Persistence operations
     * - External service calls
     * - Only receives validated data from Features
     */
    val Process = PipelinePhase("Process")

    /**
     * Phase 5: Fallback
     *
     * Final safety net for failed events. Responsible for:
     * - Inspecting events for failure flags
     * - Dead-letter queue operations
     * - Alerting on critical failures
     * - Ensuring no data loss
     */
    val Fallback = PipelinePhase("Fallback")
}