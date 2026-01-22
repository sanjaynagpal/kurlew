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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * A Ktor-inspired, multi-phase data processing pipeline with proper context separation.
 *
 * Key architectural change from previous version:
 * - DataEvent is the subject (TSubject) - what is being processed
 * - DataContext is the context (TContext) - how to process it
 *
 * This separation allows:
 * - Session management across events
 * - Shared cache and services
 * - Source context tracking
 * - Distributed tracing via correlation IDs
 * - Better testability and separation of concerns
 *
 * Implements CoroutineScope to provide proper lifecycle management and
 * structured concurrency for pipeline execution.
 *
 * @param parentContext Optional parent coroutine context. If not provided,
 *        creates a new context with Dispatchers.Default and SupervisorJob.
 *
 * @see DataEvent The data being processed
 * @see DataContext The execution environment
 */
class DataPipeline(
    parentContext: CoroutineContext? = null
) : Pipeline<DataEvent, DataContext>(Acquire, Monitoring, Features, Process, Fallback),
    CoroutineScope {

    /**
     * The coroutine context for this pipeline.
     * Uses provided parent context or creates default with Dispatchers.Default + SupervisorJob.
     */
    override val coroutineContext: CoroutineContext =
        parentContext ?: (Dispatchers.Default + SupervisorJob())

    /**
     * Executes the pipeline for a given event with a context.
     *
     * If the context doesn't have a coroutine context set, uses the pipeline's context.
     *
     * @param event The DataEvent to process
     * @param context The DataContext providing execution environment
     * @return The processed DataEvent
     */
    suspend fun execute(event: DataEvent, context: DataContext): DataEvent {
        // Use context's coroutineContext if set, otherwise use pipeline's
        val executionContext = if (context.coroutineContext == EmptyCoroutineContext) {
            context.copy(coroutineContext = coroutineContext)
        } else {
            context
        }

        execute(executionContext, event)
        return event
    }

    /**
     * Executes the pipeline with a new default context.
     * Creates a DataContext using the pipeline's coroutine context.
     *
     * @param event The DataEvent to process
     * @return The processed DataEvent
     */
    suspend fun execute(event: DataEvent): DataEvent {
        val context = DataContext(coroutineContext = coroutineContext)
        return execute(event, context)
    }

    /**
     * Executes the pipeline with raw data, creating a DataEvent wrapper.
     *
     * @param rawData The raw data to process
     * @param context Optional DataContext
     * @return The processed DataEvent
     */
    suspend fun executeRaw(rawData: Any, context: DataContext? = null): DataEvent {
        val event = DataEvent(rawData)
        val ctx = context ?: DataContext(coroutineContext = coroutineContext)
        return execute(event, ctx)
    }

    /**
     * Creates a new context builder for fluent API.
     * The builder will use the pipeline's coroutine context by default.
     */
    fun contextBuilder(): DataContextBuilder {
        return DataContextBuilder(coroutineContext)
    }

    /**
     * Cancels the pipeline and all its operations.
     * Calls cancel on the underlying coroutine scope.
     */
    fun cancel(cause: CancellationException? = null) {
        coroutineContext.cancel(cause)
    }

    /**
     * Checks if the pipeline is active (not cancelled).
     */
    val isActive: Boolean
        get() = coroutineContext.isActive
}

/**
 * Builder for DataContext to provide fluent API.
 */
class DataContextBuilder(
    private var coroutineContext: CoroutineContext
) {
    private var correlationId: String? = null
    private var source: SourceContext? = null
    private var session: SessionContext? = null
    private val services = ServiceRegistry()
    private val attributes = mutableMapOf<String, Any>()

    fun correlationId(id: String) = apply { this.correlationId = id }
    fun source(sourceContext: SourceContext) = apply { this.source = sourceContext }
    fun session(sessionContext: SessionContext) = apply { this.session = sessionContext }
    fun service(name: String, service: Any) = apply { this.services.register(name, service) }
    fun attribute(key: String, value: Any) = apply { this.attributes[key] = value }

    fun build(): DataContext {
        return DataContext(
            correlationId = correlationId ?: "corr-${System.currentTimeMillis()}",
            source = source,
            session = session,
            services = services,
            attributes = attributes,
            coroutineContext = coroutineContext
        )
    }
}