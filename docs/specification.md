# Data Pipeline Implementation Specification

## 1. Executive Summary

This document specifies the Kotlin implementation of a Ktor-inspired, multi-phase data processing pipeline designed for resilient, high-throughput data handling. The implementation directly extends Ktor's `Pipeline` class to provide a structured, sequential, and extensible framework for processing `DataEvent` objects through five mandatory phases.

**Implementation Repository**: https://github.com/sanjaynagpal/kurlew  
**Branch**: feature/first_pass

## 2. Architecture Overview

### 2.1 Core Components

The implementation consists of four primary components that work together to provide a complete data processing solution:

| Component | Type | Purpose |
|-----------|------|---------|
| `DataEvent` | Data Class | The subject and context that flows through the pipeline |
| `DataPipeline` | Class extending Ktor's Pipeline | Main pipeline orchestrator |
| `DataPipelinePhases` | Object | Defines the five mandatory phases |
| Extension Functions | Kotlin Extensions | Convenience methods for common patterns |

### 2.2 Dependency on Ktor

The implementation directly uses the following Ktor classes from `io.ktor.util.pipeline`:

- **Pipeline<TSubject, TContext>**: Base class providing phase management and interceptor execution
- **PipelinePhase**: Represents individual pipeline stages
- **PipelineContext<TSubject, TContext>**: Execution context for interceptors
- **PipelineInterceptor<TSubject, TContext>**: Type alias for interceptor functions

```kotlin
class DataPipeline : Pipeline<DataEvent, DataEvent>(
    Acquire, Monitoring, Features, Process, Fallback
)
```

## 3. DataEvent Specification

### 3.1 Structure

```kotlin
data class DataEvent(
    val incomingData: Any,
    val outgoingData: MutableMap<String, Any> = mutableMapOf()
)
```

### 3.2 Design Rationale

**Immutable `incomingData`**:
- Thread-safe: Can be read concurrently without synchronization
- Auditability: Original data preserved for logging/debugging
- Integrity: Guarantees source data never corrupted

**Mutable `outgoingData`**:
- Progressive enrichment: Phases add metadata sequentially
- Error tracking: Failure details accumulated during execution
- Flexibility: Arbitrary key-value enrichment without schema changes

### 3.3 Helper Methods

| Method | Purpose | Phase Usage |
|--------|---------|-------------|
| `isFailed()` | Check if event marked as failed | All phases |
| `markFailed(error: String?)` | Mark event as failed | Monitoring, Features |
| `getError()` | Retrieve error message | Monitoring, Fallback |
| `enrich(key, value)` | Add enrichment data | Features, Process |
| `get<T>(key)` | Retrieve enriched data | All phases |

## 4. Phase Specification

### 4.1 Phase Ordering

The pipeline enforces strict sequential execution:

```
Acquire → Monitoring → Features → Process → Fallback
```

Each phase is represented by a `PipelinePhase` object:

```kotlin
object DataPipelinePhases {
    val Acquire = PipelinePhase("Acquire")
    val Monitoring = PipelinePhase("Monitoring")
    val Features = PipelinePhase("Features")
    val Process = PipelinePhase("Process")
    val Fallback = PipelinePhase("Fallback")
}
```

### 4.2 Phase Responsibilities

#### Phase 1: Acquire
**Purpose**: Data origination and packaging

**Responsibilities**:
- Receive triggers (HTTP requests, WebSocket messages, queue events)
- Fetch raw data from external sources
- Create `DataEvent` instances
- Use `proceedWith(event)` to pass to downstream phases

**Example**:
```kotlin
pipeline.intercept(Acquire) {
    val rawData = fetchFromAPI()
    proceedWith(DataEvent(rawData))
}
```

#### Phase 2: Monitoring
**Purpose**: Exception handling and observability

**Responsibilities**:
- Wrap downstream phases in try-catch
- Catch exceptions from Features/Process/Fallback
- Record errors in `outgoingData`
- Track processing duration
- Log pipeline execution

**Pattern**:
```kotlin
pipeline.intercept(Monitoring) {
    try {
        proceed()
    } catch (e: Exception) {
        subject.markFailed(e.message)
        proceed() // Let Fallback handle it
    }
}
```

#### Phase 3: Features
**Purpose**: Validation and enrichment

**Responsibilities**:
- Validate `incomingData` schema and business rules
- Short-circuit invalid events with `finish()`
- Enrich `outgoingData` with metadata
- Act as gatekeeper for Process phase

**Patterns**:
```kotlin
// Validation
pipeline.intercept(Features) {
    if (!isValid(subject.incomingData)) {
        subject.markFailed("Validation failed")
        finish() // Short-circuit
        return@intercept
    }
    proceed()
}

// Enrichment
pipeline.intercept(Features) {
    subject.enrich("timestamp", Clock.now())
    proceed()
}
```

#### Phase 4: Process
**Purpose**: Core business logic execution

**Responsibilities**:
- Execute primary data processing
- Perform I/O operations (database writes, API calls)
- Only receive validated data from Features
- Throw exceptions on failure (caught by Monitoring)

**Pattern**:
```kotlin
pipeline.intercept(Process) {
    val data = subject.incomingData
    database.save(data) // May throw exception
    subject.enrich("saved", true)
    proceed()
}
```

#### Phase 5: Fallback
**Purpose**: Error recovery and finalization

**Responsibilities**:
- Inspect events for failure flags
- Handle failed events (DLQ, logging, alerts)
- Handle successful events (metrics, notifications)
- Terminal phase - no `proceed()` call

**Pattern**:
```kotlin
pipeline.intercept(Fallback) {
    if (subject.isFailed()) {
        deadLetterQueue.send(subject)
        logger.error("Failed: ${subject.getError()}")
    }
    // No proceed() - this is terminal
}
```

## 5. Control Flow Mechanisms

### 5.1 proceed()
**Purpose**: Continue to next interceptor/phase

**Behavior**:
- Suspends current coroutine
- Executes next interceptor in chain
- Resumes after downstream completion
- Enables natural backpressure for streaming

**Example**:
```kotlin
intercept(Phase) {
    // Pre-processing
    proceed() // Suspends here
    // Post-processing (after downstream completes)
}
```

### 5.2 finish()
**Purpose**: Short-circuit pipeline execution

**Behavior**:
- Immediately halts pipeline
- Skips all remaining phases **INCLUDING Fallback**
- Used for early termination when no error handling needed
- **Important**: Does NOT allow Fallback phase to execute

**When to Use**:
- Successful early termination (e.g., cached response)
- Short-circuiting when no further processing needed
- **NOT for validation failures** (use markFailed + proceed instead)

**Example**:
```kotlin
intercept(Features) {
    // Check cache
    val cached = cache.get(subject.incomingData)
    if (cached != null) {
        subject.enrich("fromCache", true)
        finish() // No need for Process or Fallback
        return@intercept
    }
    proceed()
}
```

**For Validation Failures, DON'T use finish()**:
```kotlin
// ❌ WRONG - Fallback won't execute
intercept(Features) {
    if (!isValid(subject)) {
        subject.markFailed("Invalid")
        finish() // Fallback phase skipped!
        return@intercept
    }
    proceed()
}

// ✅ CORRECT - Fallback will execute
intercept(Features) {
    if (!isValid(subject)) {
        subject.markFailed("Invalid")
        // Don't call finish() - let it proceed to Fallback
    } else {
        // Only proceed to Process if valid
        proceed()
    }
}
```

### 5.3 proceedWith(value)
**Purpose**: Transform pipeline subject

**Behavior**:
- Replaces current subject with new value
- All downstream interceptors see new subject
- Used in Acquire to create DataEvent from raw data

**Example**:
```kotlin
intercept(Acquire) {
    val rawData = fetchData()
    val event = DataEvent(rawData)
    proceedWith(event) // Change subject for downstream
}
```

## 6. Extension Functions Specification

### 6.1 monitoringWrapper()
Adds monitoring with error handling to Monitoring phase.

```kotlin
fun DataPipeline.monitoringWrapper(
    onError: (DataEvent, Throwable) -> Unit = { event, error ->
        event.markFailed(error.message)
    },
    onSuccess: (DataEvent) -> Unit = {},
    block: suspend PipelineContext<DataEvent, DataEvent>.() -> Unit = {}
)
```

### 6.2 validate()
Adds validation to Features phase with automatic short-circuiting.

```kotlin
fun DataPipeline.validate(
    errorMessage: String = "Validation failed",
    predicate: (DataEvent) -> Boolean
)
```

### 6.3 enrich()
Adds enrichment to Features phase.

```kotlin
fun DataPipeline.enrich(
    enricher: (DataEvent) -> Unit
)
```

### 6.4 process()
Adds processing logic to Process phase.

```kotlin
fun DataPipeline.process(
    processor: suspend (DataEvent) -> Unit
)
```

### 6.5 onFailure() / onSuccess()
Adds handlers to Fallback phase.

```kotlin
fun DataPipeline.onFailure(handler: suspend (DataEvent) -> Unit)
fun DataPipeline.onSuccess(handler: suspend (DataEvent) -> Unit)
```

## 7. Threading and Concurrency Model

### 7.1 Coroutine-Based Execution

The pipeline uses Kotlin coroutines for asynchronous execution:

```kotlin
class DataPipeline(
    override val coroutineContext: CoroutineContext =
        Dispatchers.Default + SupervisorJob()
) : Pipeline<DataEvent, DataEvent>(...), CoroutineScope
```

### 7.2 Thread Safety Guarantees

**Per-Event Isolation**:
- Each `DataEvent` is a unique instance
- No shared mutable state between concurrent executions
- `outgoingData` is private to single execution

**Immutability**:
- `incomingData` is immutable (thread-safe reads)
- Can be safely accessed by multiple coroutines

**Sequential Access**:
- Within single event, interceptors execute sequentially
- No concurrent modifications to `outgoingData`

### 7.3 Concurrent Event Processing

Multiple events can be processed concurrently:

```kotlin
val events = listOf(event1, event2, event3)
events.forEach { event ->
    launch {
        pipeline.execute(event) // Concurrent execution
    }
}
```

Each execution is isolated - no interference between events.

## 8. Error Handling Strategy

### 8.1 Exception Propagation

```
Process throws exception
    ↓
Monitoring catches exception
    ↓
Monitoring marks event as failed
    ↓
Event proceeds to Fallback
    ↓
Fallback handles failed event
```

### 8.2 Error Tracking

Errors are tracked in `outgoingData`:

```kotlin
outgoingData["failed"] = true
outgoingData["error"] = "Error message"
outgoingData["errorType"] = "ExceptionType"
outgoingData["errorTime"] = timestamp
```

### 8.3 Dead-Letter Queue Pattern

Failed events are preserved in Fallback:

```kotlin
pipeline.onFailure { event ->
    val record = DLQRecord(
        originalData = event.incomingData,
        error = event.getError(),
        enrichedData = event.outgoingData,
        timestamp = System.currentTimeMillis()
    )
    deadLetterQueue.send(record)
}
```

## 9. Backpressure Management

### 9.1 Natural Backpressure Mechanism

The suspending nature of `proceed()` provides automatic backpressure:

```kotlin
intercept(Acquire) {
    while (true) {
        val message = webSocket.receive()
        val event = DataEvent(message)
        proceedWith(event) // SUSPENDS until event fully processed
        // Won't fetch next message until current one completes
    }
}
```

**Key Points**:
- Producer automatically throttled to consumer capacity
- No explicit buffering or queuing needed
- Prevents memory overflow in streaming scenarios

### 9.2 Early Termination Optimization

Invalid events are rejected early in Features phase:

```kotlin
intercept(Features) {
    if (!isValid(subject)) {
        finish() // Skip expensive Process phase
        return@intercept
    }
    proceed()
}
```

This conserves resources by avoiding unnecessary processing.

## 10. Testing Strategy

### 10.1 Unit Tests

Test each phase in isolation:
- Verify phase execution order
- Test state mutations in `outgoingData`
- Verify `incomingData` immutability
- Test control flow (`proceed`, `finish`, `proceedWith`)

### 10.2 Integration Tests

Test complete pipeline flows:
- Successful processing path
- Validation failure path
- Exception handling path
- Dead-letter queue operations

### 10.3 Property-Based Tests

Verify invariants:
- `incomingData` never changes
- Failed events always reach Fallback
- Phase order is always preserved

## 11. Performance Characteristics

### 11.1 Overhead

**Fixed Overhead per Event**:
- Phase traversal: ~5 function calls
- Context switching: Coroutine suspension points
- State allocation: DataEvent + outgoingData map

**Estimated**: <1ms overhead for typical 5-phase pipeline

### 11.2 Optimization Strategies

**Short-Circuiting**:
- Invalid events skip Process phase
- Saves expensive I/O operations

**Early Validation**:
- Features phase filters bad data
- Reduces downstream load

**Async I/O**:
- Suspending functions don't block threads
- High concurrency with low thread count

## 12. Dependencies

### 12.1 Required Dependencies

```kotlin
dependencies {
    // Ktor Pipeline utilities
    implementation("io.ktor:ktor-utils:2.3.7")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
```

## 13. Implementation Checklist

- [x] Core classes (DataEvent, DataPipeline, DataPipelinePhases)
- [x] Extension functions for common patterns
- [x] Comprehensive unit tests
- [x] Integration tests
- [x] Usage examples (Simple, WebSocket, Persistence)
- [x] Documentation (specification.md, design.md)
- [ ] Performance benchmarks
- [ ] CI/CD pipeline
- [ ] Release artifacts

## 14. Future Enhancements

### 14.1 Planned Features

- Metrics integration (Micrometer, Prometheus)
- Distributed tracing (OpenTelemetry)
- Configuration DSL for pipeline setup
- Plugin system for reusable interceptor bundles
- Reactive Streams integration

### 14.2 Optimization Opportunities

- Interceptor compilation/optimization
- Phase merging for common patterns
- Memory pooling for DataEvent objects
- Structured concurrency with coroutine scopes

## 15. References

- [Design Specifications](https://github.com/sanjaynagpal/go-learn/wiki)
- [Ktor Pipeline Documentation](https://ktor.io/docs/pipelines.html)
- [Kotlin Coroutines Guide](https://kotlinlang.org/docs/coroutines-guide.html)