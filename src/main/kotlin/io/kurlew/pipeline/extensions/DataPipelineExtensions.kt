package io.kurlew.pipeline.extensions

import io.ktor.util.pipeline.*
import io.kurlew.pipeline.DataContext
import io.kurlew.pipeline.DataEvent
import io.kurlew.pipeline.DataPipeline
import io.kurlew.pipeline.DataPipelinePhases
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
 * The context parameter provides access to correlation ID, cache, services, etc.
 */
fun DataPipeline.monitoringWrapper(
    onError: (DataEvent, DataContext, Throwable) -> Unit = { event, _, error ->
        event.markFailed(error.message)
    },
    onSuccess: (DataEvent, DataContext) -> Unit = { _, _ -> },
    block: suspend PipelineContext<DataEvent, DataContext>.() -> Unit = {}
) {
    intercept(DataPipelinePhases.Monitoring) {
        block()

        val duration = measureTime {
            try {
                proceed()
                onSuccess(subject, context)
            } catch (e: Exception) {
                // Record error in both event and context
                onError(subject, context, e)
                context.setAttribute("lastError", e)
                proceed() // Let Fallback handle it
            }
        }

        subject.enrich("processingDuration", duration)
        context.setAttribute("processingDuration", duration)
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
    predicate: (DataEvent, DataContext) -> Boolean
) {
    intercept(DataPipelinePhases.Features) {
        if (!predicate(subject, context)) {
            subject.markFailed(errorMessage)
            context.setAttribute("validationFailed", true)
            // Don't call finish() - allow event to proceed to Fallback
        } else {
            subject.enrich("validated", true)
            context.setAttribute("validated", true)
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
    enricher: suspend (DataEvent, DataContext) -> Unit
) {
    intercept(DataPipelinePhases.Features) {
        enricher(subject, context)
        proceed()
    }
}

/**
 * Adds a processing interceptor to the Process phase.
 * Only executes if the event is not already marked as failed.
 *
 * Example:
 * ```
 * pipeline.process { event, context ->
 *     val db = context.services.getByType<Database>()
 *     db?.save(event.incomingData)
 *
 *     // Use correlation ID for tracing
 *     logger.info("Processed ${context.correlationId}")
 * }
 * ```
 */
fun DataPipeline.process(
    processor: suspend (DataEvent, DataContext) -> Unit
) {
    intercept(DataPipelinePhases.Process) {
        // Only process if not already failed
        if (!subject.isFailed()) {
            processor(subject, context)
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
    handler: suspend (DataEvent, DataContext) -> Unit
) {
    intercept(DataPipelinePhases.Fallback) {
        if (subject.isFailed()) {
            handler(subject, context)
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
    handler: suspend (DataEvent, DataContext) -> Unit
) {
    intercept(DataPipelinePhases.Fallback) {
        if (!subject.isFailed()) {
            handler(subject, context)
        }
        // No proceed() - this is terminal
    }
}

/**
 * Adds caching interceptor to Features phase.
 * Checks cache before processing, stores result after.
 *
 * Example:
 * ```
 * pipeline.withCache(
 *     keyProvider = { event -> "user:${event.incomingData}" },
 *     ttlMs = 60_000 // 1 minute
 * )
 * ```
 */
fun DataPipeline.withCache(
    keyProvider: (DataEvent) -> String,
    ttlMs: Long? = null
) {
    intercept(DataPipelinePhases.Features) {
        val cacheKey = keyProvider(subject)

        // Try to get from cache
        val cached = context.cache.get(cacheKey)
        if (cached != null) {
            subject.enrich("fromCache", true)
            subject.enrich("cachedData", cached)
            context.setAttribute("cacheHit", true)
            // Continue processing with cached data available
        } else {
            context.setAttribute("cacheHit", false)
        }

        proceed()

        // After processing, cache the result if successful
        if (!subject.isFailed() && cached == null) {
            subject.outgoingData["result"]?.let { result ->
                context.cache.put(cacheKey, result, ttlMs)
            }
        }
    }
}

/**
 * Adds session tracking to the pipeline.
 *
 * Example:
 * ```
 * pipeline.withSession { event ->
 *     // Extract session ID from event
 *     (event.incomingData as? UserRequest)?.sessionId
 * }
 * ```
 */
fun DataPipeline.withSession(
    sessionIdProvider: (DataEvent) -> String?
) {
    intercept(DataPipelinePhases.Features) {
        val sessionId = sessionIdProvider(subject)
        if (sessionId != null) {
            // Session is already in context or create new one
            context.session?.let { session ->
                session.touch()
                subject.enrich("sessionId", session.sessionId)
            }
        }
        proceed()
    }
}

/**
 * Adds distributed tracing support.
 *
 * Automatically propagates correlation ID and adds tracing metadata.
 */
fun DataPipeline.withTracing(
    tracingService: TracingService? = null
) {
    intercept(DataPipelinePhases.Monitoring) {
        val traceId = context.correlationId

        subject.enrich("traceId", traceId)
        subject.enrich("timestamp", System.currentTimeMillis())

        // Add source information if available
        context.source?.let { source ->
            subject.enrich("source", source.toString())
        }

        tracingService?.startSpan(traceId, "pipeline.execute")

        try {
            proceed()
            tracingService?.endSpan(traceId, success = !subject.isFailed())
        } catch (e: Exception) {
            tracingService?.recordError(traceId, e)
            throw e
        }
    }
}

/**
 * Adds retry logic with exponential backoff.
 *
 * Uses context to track retry attempts and share state.
 */
fun DataPipeline.withRetry(
    maxAttempts: Int = 3,
    initialDelayMs: Long = 100,
    maxDelayMs: Long = 5000,
    retryOn: (Throwable) -> Boolean = { true }
) {
    intercept(DataPipelinePhases.Process) {
        var attempt = 0
        var delay = initialDelayMs
        var lastError: Throwable? = null

        while (attempt < maxAttempts) {
            attempt++
            context.setAttribute("retryAttempt", attempt)

            try {
                proceed()
                return@intercept // Success
            } catch (e: Exception) {
                lastError = e

                if (!retryOn(e) || attempt >= maxAttempts) {
                    throw e
                }

                // Exponential backoff
                kotlinx.coroutines.delay(delay)
                delay = minOf(delay * 2, maxDelayMs)
            }
        }

        throw lastError ?: IllegalStateException("Retry failed")
    }
}

/**
 * Simple tracing service interface.
 */
interface TracingService {
    fun startSpan(traceId: String, operationName: String)
    fun endSpan(traceId: String, success: Boolean)
    fun recordError(traceId: String, error: Throwable)
}

/**
 * Extension to access the current event in interceptor.
 */
val PipelineContext<DataEvent, DataContext>.event: DataEvent
    get() = subject

/**
 * Extension to access the current context in interceptor.
 */
val PipelineContext<DataEvent, DataContext>.ctx: DataContext
    get() = context