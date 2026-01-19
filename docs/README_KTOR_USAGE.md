# Data Pipeline: Leveraging Ktor's Pipeline Architecture

## Table of Contents
- [Overview](#overview)
- [Ktor Classes Used](#ktor-classes-used)
- [How We Extend Ktor](#how-we-extend-ktor)
- [Deep Dive: Pipeline Mechanics](#deep-dive-pipeline-mechanics)
- [Migration from Ktor HTTP to Data Pipeline](#migration-from-ktor-http-to-data-pipeline)
- [Advanced Usage](#advanced-usage)

---

## Overview

The Data Pipeline implementation is built on top of Ktor's robust `Pipeline` infrastructure. Rather than reinventing the wheel, we directly extend Ktor's proven pipeline mechanism to create a specialized data processing framework.

**Key Insight**: Ktor's Pipeline was designed for HTTP request/response processing, but its core abstraction is generic enough to handle any sequential data processing workflow.

---

## Ktor Classes Used

### 1. `Pipeline<TSubject, TContext>`

**What it is**: The base class for all pipeline operations in Ktor.

**From**: `io.ktor.util.pipeline.Pipeline`

**Our Usage**:
```kotlin
class DataPipeline : Pipeline<DataEvent, DataEvent>(
    Acquire, Monitoring, Features, Process, Fallback
)
```

**What Ktor Provides**:
- Phase management and ordering
- Interceptor registration and execution
- Coroutine-based suspension mechanism
- `execute()` method to run the pipeline

**What We Add**:
- Specific five-phase structure for data processing
- DataEvent as unified subject/context
- Helper methods for common data pipeline patterns

**Ktor HTTP Example** (for comparison):
```kotlin
// How Ktor uses it for HTTP
class ApplicationCallPipeline : Pipeline<Unit, ApplicationCall>(
    Setup, Monitoring, Plugins, Call, Fallback
)
```

---

### 2. `PipelinePhase`

**What it is**: Represents a named stage in the pipeline.

**From**: `io.ktor.util.pipeline.PipelinePhase`

**Our Usage**:
```kotlin
object DataPipelinePhases {
    val Acquire = PipelinePhase("Acquire")
    val Monitoring = PipelinePhase("Monitoring")
    val Features = PipelinePhase("Features")
    val Process = PipelinePhase("Process")
    val Fallback = PipelinePhase("Fallback")
}
```

**What PipelinePhase Provides**:
- Named identifier for phase
- Ordering and relationship with other phases
- Used by Pipeline to determine execution order

**Ktor HTTP Example**:
```kotlin
// Ktor's HTTP phases
val Setup = PipelinePhase("Setup")
val Monitoring = PipelinePhase("Monitoring")
val Plugins = PipelinePhase("Plugins")
// etc.
```

**Phase Relationships**:
```kotlin
// In Ktor, you can define phase relationships
pipeline.insertPhaseAfter(Monitoring, CustomPhase)
pipeline.insertPhaseBefore(Process, ValidationPhase)

// We use fixed ordering, but relationships still work
val phases = listOf(Acquire, Monitoring, Features, Process, Fallback)
// Pipeline executes in this exact order
```

---

### 3. `PipelineContext<TSubject, TContext>`

**What it is**: The execution context provided to each interceptor.

**From**: `io.ktor.util.pipeline.PipelineContext`

**Our Usage**:
```kotlin
pipeline.intercept(DataPipelinePhases.Process) {
    // 'this' is PipelineContext<DataEvent, DataEvent>
    val event = subject  // Access to DataEvent
    
    proceed()  // Continue to next interceptor
    finish()   // Short-circuit pipeline
    proceedWith(newEvent)  // Transform subject
}
```

**What PipelineContext Provides**:

| Property/Method | Purpose | Our Usage |
|-----------------|---------|-----------|
| `subject` | The current subject (DataEvent) | Access incoming and outgoing data |
| `context` | The current context (also DataEvent) | Same as subject in our case |
| `proceed()` | Continue to next interceptor | Standard flow control |
| `finish()` | Stop pipeline execution | Validation failures |
| `proceedWith(value)` | Replace subject | Acquire phase creates DataEvent |

**Ktor HTTP Example**:
```kotlin
// In Ktor HTTP
intercept(ApplicationCallPipeline.Call) {
    val call: ApplicationCall = subject  // or context
    
    if (call.request.uri == "/health") {
        call.respondText("OK")
        finish()  // Don't process further
    }
    
    proceed()
}
```

---

### 4. `PipelineInterceptor<TSubject, TContext>`

**What it is**: Type alias for interceptor functions.

**From**: `io.ktor.util.pipeline.PipelineInterceptor`

**Definition**:
```kotlin
typealias PipelineInterceptor<TSubject, TContext> = 
    suspend PipelineContext<TSubject, TContext>.(TSubject) -> Unit
```

**Our Usage**:
```kotlin
val validationInterceptor: PipelineInterceptor<DataEvent, DataEvent> = {
    // 'this' is PipelineContext
    // 'it' is the subject (DataEvent)
    if (!isValid(it.incomingData)) {
        it.markFailed("Invalid")
        finish()
    } else {
        proceed()
    }
}

pipeline.intercept(Features, validationInterceptor)
```

**Why This Signature?**:
- **Suspend**: Allows async I/O without blocking
- **PipelineContext receiver**: Access to `proceed()`, `finish()`
- **TSubject parameter**: Direct access to the data being processed

---

## How We Extend Ktor

### Extension Point 1: Specialized Subject/Context

**Ktor's Approach** (HTTP):
```kotlin
data class ApplicationCall(
    val request: ApplicationRequest,
    val response: ApplicationResponse,
    val attributes: Attributes
)

// Subject and Context are the same object
Pipeline<Unit, ApplicationCall>
```

**Our Approach** (Data):
```kotlin
data class DataEvent(
    val incomingData: Any,  // Immutable source
    val outgoingData: MutableMap<String, Any>  // Mutable enrichment
)

// Subject and Context are the same object
Pipeline<DataEvent, DataEvent>
```

**Key Difference**: We emphasize the immutable/mutable split for data integrity.

---

### Extension Point 2: Specialized Phases

**Ktor's HTTP Phases**:
- Setup → Monitoring → Plugins → Call → Fallback

**Our Data Phases**:
- Acquire → Monitoring → Features → Process → Fallback

**Mapping**:

| Ktor Phase | Our Phase | Purpose Adaptation |
|------------|-----------|-------------------|
| N/A | Acquire | Data ingestion (HTTP has implicit request reception) |
| Monitoring | Monitoring | Same concept - error detection |
| Plugins | Features | Validation and enrichment (vs HTTP features like auth) |
| Call | Process | Business logic (vs request handling) |
| Fallback | Fallback | Same concept - error handling |

---

### Extension Point 3: Domain-Specific Extensions

**What Ktor Provides** (Base):
```kotlin
fun intercept(phase: PipelinePhase, block: PipelineInterceptor)
```

**What We Add** (Convenience):
```kotlin
// High-level, declarative API
fun DataPipeline.validate(predicate: (DataEvent) -> Boolean)
fun DataPipeline.process(processor: suspend (DataEvent) -> Unit)
fun DataPipeline.onFailure(handler: suspend (DataEvent) -> Unit)

// These are just wrappers around intercept()
```

**Example**:
```kotlin
// Using Ktor's base API (still works)
pipeline.intercept(Features) {
    if (!isValid(subject)) {
        finish()
    }
    proceed()
}

// Using our extension (preferred)
pipeline.validate { event -> isValid(event.incomingData) }
```

---

## Deep Dive: Pipeline Mechanics

### How `proceed()` Actually Works

Understanding Ktor's `proceed()` is key to understanding backpressure:

```kotlin
// Simplified internal implementation (conceptual)
suspend fun PipelineContext<T, C>.proceed() {
    val nextInterceptor = getNextInterceptor()
    if (nextInterceptor != null) {
        nextInterceptor.invoke(this, subject)  // Suspend here!
    } else {
        val nextPhase = getNextPhase()
        if (nextPhase != null) {
            executePhase(nextPhase)  // Move to next phase
        }
        // else: pipeline complete
    }
}
```

**Key Insight**: `proceed()` is a suspension point. The calling coroutine suspends until all downstream interceptors complete.

**Backpressure Mechanism**:
```kotlin
intercept(Acquire) {
    while (true) {
        val message = webSocket.receive()  // Fast producer
        val event = DataEvent(message)
        
        proceedWith(event)  
        // ^^^ SUSPENDS until event fully processed
        // This naturally throttles the producer!
        
        // Won't fetch next message until current one completes
    }
}
```

**Comparison to Other Approaches**:

| Approach | Backpressure | Complexity |
|----------|--------------|-----------|
| Ktor Pipeline (us) | Automatic via suspension | Low |
| Reactive Streams | Manual buffering/operators | High |
| Akka Actors | Mailbox back-pressure | Medium |
| Manual Channels | Explicit channel buffering | Medium |

---

### How `finish()` Works

```kotlin
// Simplified internal implementation (conceptual)
suspend fun PipelineContext<T, C>.finish() {
    markPipelineAsFinished()
    // Immediately return to top-level execute()
    // Skip all remaining interceptors and phases
}
```

**When to use `finish()`**:
- Validation failures
- Early termination conditions
- Short-circuiting expensive operations

**Example**:
```kotlin
intercept(Features) {
    if (subject.incomingData is InvalidData) {
        subject.markFailed("Validation failed")
        finish()  // Skip Process phase entirely
        return@intercept
    }
    proceed()
}
```

---

### How `proceedWith()` Works

```kotlin
// Simplified internal implementation (conceptual)
suspend fun PipelineContext<T, C>.proceedWith(newSubject: T) {
    subject = newSubject  // Replace current subject
    proceed()  // Continue with new subject
}
```

**Use Cases**:
1. **Acquire Phase**: Transform raw input to DataEvent
2. **Data Transformation**: Convert between formats
3. **Enrichment**: Create enhanced version of data

**Example**:
```kotlin
intercept(Acquire) {
    // Receive raw data
    val rawData = fetchFromAPI()
    
    // Package into DataEvent
    val event = DataEvent(
        incomingData = rawData,
        outgoingData = mutableMapOf("source" to "api")
    )
    
    // All downstream interceptors see this DataEvent
    proceedWith(event)
}
```

---

## Migration from Ktor HTTP to Data Pipeline

If you're familiar with Ktor HTTP, here's how concepts translate:

### HTTP Request Processing (Ktor)

```kotlin
install(MyPlugin) {
    intercept(ApplicationCallPipeline.Call) {
        val call: ApplicationCall = subject
        
        // Validate
        if (!isAuthorized(call.request)) {
            call.respond(HttpStatusCode.Unauthorized)
            finish()
            return@intercept
        }
        
        // Process
        val result = processRequest(call.request)
        call.respond(result)
        
        proceed()
    }
}
```

### Data Event Processing (Our Pipeline)

```kotlin
val pipeline = DataPipeline()

pipeline.validate { event ->
    isAuthorized(event.incomingData)
}

pipeline.process { event ->
    val result = processData(event.incomingData)
    event.enrich("result", result)
}

pipeline.execute(DataEvent(rawData))
```

### Side-by-Side Comparison

| Aspect | Ktor HTTP | Data Pipeline |
|--------|-----------|---------------|
| Subject | ApplicationCall | DataEvent |
| Input | ApplicationRequest | DataEvent.incomingData |
| Output | ApplicationResponse | DataEvent.outgoingData |
| Validation | Custom interceptor | Features phase |
| Processing | Call phase | Process phase |
| Error Handling | StatusPages plugin | Monitoring + Fallback |
| Failure Response | HTTP error code | markFailed() + DLQ |

---

## Advanced Usage

### 1. Combining Multiple Pipelines

```kotlin
// Create specialized pipelines
val validationPipeline = DataPipeline().apply {
    validate { /* complex validation */ }
}

val processingPipeline = DataPipeline().apply {
    process { /* heavy processing */ }
}

// Compose them
intercept(Features) {
    validationPipeline.execute(subject)
    if (subject.isFailed()) {
        finish()
        return@intercept
    }
    proceed()
}

intercept(Process) {
    processingPipeline.execute(subject)
    proceed()
}
```

### 2. Custom Phase Insertion

```kotlin
// Define custom phase
val EnrichmentPhase = PipelinePhase("Enrichment")

// Insert between Features and Process
pipeline.insertPhaseAfter(Features, EnrichmentPhase)

// Add interceptor
pipeline.intercept(EnrichmentPhase) {
    // Custom enrichment logic
    proceed()
}
```

### 3. Dynamic Interceptor Registration

```kotlin
// Register interceptors at runtime
fun registerDataSource(source: DataSource) {
    pipeline.intercept(Acquire) {
        val data = source.fetch()
        proceedWith(DataEvent(data))
    }
}

// Add multiple sources
registerDataSource(ApiSource())
registerDataSource(DatabaseSource())
registerDataSource(KafkaSource())
```

---

## Ktor Pipeline Benefits We Inherit

### 1. Battle-Tested Implementation
- Used in production by thousands of applications
- Proven correctness and performance
- Regular updates and bug fixes

### 2. Coroutine Integration
- First-class coroutine support
- Efficient suspension and resumption
- Structured concurrency

### 3. Extensibility
- Phase insertion
- Custom interceptors
- Composable pipelines

### 4. Performance
- Low overhead (~microseconds per phase)
- Efficient suspension mechanism
- Minimal allocations

---

## Conclusion

By extending Ktor's Pipeline, we get:
- **Proven Foundation**: Battle-tested implementation
- **Coroutine Power**: Natural backpressure via suspension
- **Extensibility**: Easy to customize and extend
- **Performance**: Low overhead, high throughput
- **Familiarity**: Known API for Ktor developers

We adapt Ktor's HTTP-focused pipeline to generic data processing while preserving all the core benefits of the original design.

---

## Further Reading

- [Ktor Pipeline Documentation](https://ktor.io/docs/pipelines.html)
- [Kotlin Coroutines Guide](https://kotlinlang.org/docs/coroutines-guide.html)
- [Our Design Specifications](./docs/specification.md)
- [Design Decisions](./docs/design.md)