package io.kurlew.pipeline.extensions

import io.kurlew.pipeline.DataEvent
import io.kurlew.pipeline.DataPipeline
import io.kurlew.pipeline.DataPipelineCall
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
fun <T> DataPipeline<T>.monitoringWrapper(
    onError: (DataEvent<T>, DataPipelineCall, Throwable) -> Unit = { _, call, error ->
        call.markFailed(error.message)
    },
    onSuccess: (DataEvent<T>, DataPipelineCall) -> Unit = { _, _ -> },
    block: suspend PipelineContext<DataEvent<T>, DataPipelineCall>.() -> Unit = {}
) {
    intercept(DataPipelinePhases.Monitoring) {
        block()

        val duration = measureTime {
            try {
                proceed()
                onSuccess(subject, context)
            } catch (e: Exception) {
                onError(subject, context, e)
                proceed() // Let Fallback handle it
            }
        }

        context.enrich("processingDuration", duration)
    }
}

/**
 * Adds a validation interceptor to the Features phase.
 * Marks call as failed but allows it to proceed to Fallback for proper handling.
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
fun <T> DataPipeline<T>.validate(
    errorMessage: String = "Validation failed",
    predicate: (DataEvent<T>) -> Boolean
) {
    intercept(DataPipelinePhases.Features) {
        if (!predicate(subject)) {
            context.markFailed(errorMessage)
            // Don't call finish() - allow event to proceed to Fallback
            // This ensures proper error handling and DLQ operations
        } else {
            context.enrich("validated", true)
        }
        proceed()
    }
}
/**
 * Adds an enrichment interceptor to the Features phase.
 *
 * Example:
 * ```
 * pipeline.enrich { event, call ->
 *     call.enrich("timestamp", Clock.System.now())
 * }
 * ```
 */
fun <T> DataPipeline<T>.enrich(
    enricher: (DataEvent<T>, DataPipelineCall) -> Unit
) {
    intercept(DataPipelinePhases.Features) {
        enricher(subject, context)
        proceed()
    }
}

/**
 * Adds a processing interceptor to the Process phase.
 * Only executes if the call is not already marked as failed.
 *
 * Example:
 * ```
 * pipeline.process { event, call ->
 *     database.save(event.incomingData)
 * }
 * ```
 */
fun <T> DataPipeline<T>.process(
    processor: suspend (DataEvent<T>, DataPipelineCall) -> Unit
) {
    intercept(DataPipelinePhases.Process) {
        // Only process if not already failed
        if (!context.isFailed()) {
            processor(subject, context)
        }
        proceed()
    }
}

/**
 * Adds a fallback handler for failed calls.
 * Only executes if the call is marked as failed.
 *
 * Example:
 * ```
 * pipeline.onFailure { event, call ->
 *     deadLetterQueue.send(event to call)
 * }
 * ```
 */
fun <T> DataPipeline<T>.onFailure(
    handler: suspend (DataEvent<T>, DataPipelineCall) -> Unit
) {
    intercept(DataPipelinePhases.Fallback) {
        if (context.isFailed()) {
            handler(subject, context)
        }
        // No proceed() - this is terminal
    }
}

/**
 * Adds a success handler for successful calls.
 * Only executes if the call is NOT marked as failed.
 *
 * Example:
 * ```
 * pipeline.onSuccess { event, call ->
 *     metrics.recordSuccess()
 * }
 * ```
 */
fun <T> DataPipeline<T>.onSuccess(
    handler: suspend (DataEvent<T>, DataPipelineCall) -> Unit
) {
    intercept(DataPipelinePhases.Fallback) {
        if (!context.isFailed()) {
            handler(subject, context)
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
fun <T> DataPipeline<T>.retry(
    maxAttempts: Int = 3,
    delay: Duration = Duration.ZERO,
    processor: PipelineInterceptor<DataEvent<T>, DataPipelineCall>
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