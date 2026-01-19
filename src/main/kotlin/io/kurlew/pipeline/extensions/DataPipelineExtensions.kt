package io.kurlew.pipeline.extensions

import io.kurlew.pipeline.DataEvent
import io.kurlew.pipeline.DataPipeline
import io.kurlew.pipeline.DataPipelinePhases
import io.ktor.util.pipeline.PipelineContext
import io.ktor.util.pipeline.PipelineInterceptor
import kotlin.time.Duration
import kotlin.time.measureTime

/**
 * Extension functions to simplify common DataPipeline patterns.
 */

/**
 * Adds a monitoring interceptor that wraps downstream phases in try-catch.
 * This is the recommended way to add error handling to the Monitoring phase.
 *
 * Example:
 * ```
 * pipeline.monitoringWrapper {
 *     // Optional: Add custom logging
 *     println("Processing started")
 * }
 * ```
 */
fun DataPipeline.monitoringWrapper(
    onError: (DataEvent, Throwable) -> Unit = { event, error ->
        event.markFailed(error.message)
    },
    onSuccess: (DataEvent) -> Unit = {},
    block: suspend PipelineContext<DataEvent, DataEvent>.() -> Unit = {}
) {
    intercept(DataPipelinePhases.Monitoring) {
        block()

        val duration = measureTime {
            try {
                proceed()
                onSuccess(subject)
            } catch (e: Exception) {
                onError(subject, e)
                proceed() // Let Fallback handle it
            }
        }

        subject.enrich("processingDuration", duration)
    }
}

/**
 * Adds a validation interceptor to the Features phase.
 * Marks event as failed but allows it to proceed to Fallback for proper handling.
 *
 * Note: We don't call finish() here because that would skip the Fallback phase,
 * preventing proper error handling and DLQ operations.
 *
 * Example:
 * ```
 * pipeline.validate { event ->
 *     event.incomingData is String && event.incomingData.isNotEmpty()
 * }
 * ```
 */
fun DataPipeline.validate(
    errorMessage: String = "Validation failed",
    predicate: (DataEvent) -> Boolean
) {
    intercept(DataPipelinePhases.Features) {
        if (!predicate(subject)) {
            subject.markFailed(errorMessage)
            // Don't call finish() - allow event to proceed to Fallback
            // This ensures proper error handling and DLQ operations
        } else {
            subject.enrich("validated", true)
        }
        proceed()
    }
}
/**
 * Adds an enrichment interceptor to the Features phase.
 *
 * Example:
 * ```
 * pipeline.enrich { event ->
 *     event.enrich("timestamp", Clock.System.now())
 * }
 * ```
 */
fun DataPipeline.enrich(
    enricher: (DataEvent) -> Unit
) {
    intercept(DataPipelinePhases.Features) {
        enricher(subject)
        proceed()
    }
}

/**
 * Adds a processing interceptor to the Process phase.
 * Only executes if the event is not already marked as failed.
 *
 * Example:
 * ```
 * pipeline.process { event ->
 *     database.save(event.incomingData)
 * }
 * ```
 */
fun DataPipeline.process(
    processor: suspend (DataEvent) -> Unit
) {
    intercept(DataPipelinePhases.Process) {
        // Only process if not already failed
        if (!subject.isFailed()) {
            processor(subject)
        }
        proceed()
    }
}

/**
 * Adds a fallback handler for failed events.
 * Only executes if the event is marked as failed.
 *
 * Example:
 * ```
 * pipeline.onFailure { event ->
 *     deadLetterQueue.send(event)
 * }
 * ```
 */
fun DataPipeline.onFailure(
    handler: suspend (DataEvent) -> Unit
) {
    intercept(DataPipelinePhases.Fallback) {
        if (subject.isFailed()) {
            handler(subject)
        }
        // No proceed() - this is terminal
    }
}

/**
 * Adds a success handler for successful events.
 * Only executes if the event is NOT marked as failed.
 *
 * Example:
 * ```
 * pipeline.onSuccess { event ->
 *     metrics.recordSuccess()
 * }
 * ```
 */
fun DataPipeline.onSuccess(
    handler: suspend (DataEvent) -> Unit
) {
    intercept(DataPipelinePhases.Fallback) {
        if (!subject.isFailed()) {
            handler(subject)
        }
        // No proceed() - this is terminal
    }
}

/**
 * Creates a retry interceptor for the Process phase.
 * Retries the downstream processing up to maxAttempts times.
 *
 * Example:
 * ```
 * pipeline.retry(maxAttempts = 3) {
 *     // Processing logic that might fail
 * }
 * ```
 */
fun DataPipeline.retry(
    maxAttempts: Int = 3,
    delay: Duration = Duration.ZERO,
    processor: PipelineInterceptor<DataEvent, DataEvent>
) {
    intercept(DataPipelinePhases.Process) {
        var lastError: Throwable? = null

        repeat(maxAttempts) { attempt ->
            try {
                processor(this, subject)
                return@intercept // Success
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxAttempts - 1 && delay.isPositive()) {
                    kotlinx.coroutines.delay(delay)
                }
            }
        }

        // All attempts failed
        throw lastError ?: IllegalStateException("Retry failed")
    }
}